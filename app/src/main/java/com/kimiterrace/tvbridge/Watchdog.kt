package com.kimiterrace.tvbridge

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * 常駐の「最後の砦」ウォッチドッグ。
 *
 * BleService(= ConfigPoller / ScheduleManager のホスト) が OEM の省電力・メモリ逼迫・想定外クラッシュで
 * 殺され、START_STICKY / BootReceiver / スケジュールアラームのどれでも復帰しなかった場合に備え、
 * AlarmManager で定期的に自己回復処理 [tick] を実行する。AlarmManager のアラームは **プロセスが死んでも
 * OS 側に残り**、`setExactAndAllowWhileIdle` で Doze も貫通して発火するため、プロセス全滅状態からでも復活
 * できる。スケジュール（朝 ON / 夜 OFF / warmup）の隙間（例: 深夜にプロセス死 → 翌朝まで無音）も 15 分間隔で埋める。
 *
 * [tick] は AlarmManager 経路（[WatchdogReceiver]）と WorkManager 経路（[KeepAliveWorker]）の両方から
 * 呼ばれる共通ロジック（経路の冗長化）。
 */
object Watchdog {
    private const val TAG = "Watchdog"
    const val ACTION_TICK = "com.kimiterrace.tvbridge.WATCHDOG_TICK"
    private const val REQ_TICK = 2001
    private const val REQ_RESTART = 2002

    /** 定期チェック間隔（15 分）。Doze の setExactAndAllowWhileIdle 最小間隔とも整合。 */
    private const val INTERVAL_MS = 15 * 60_000L

    /** ポーリングがこの時間 成功していなければ「プロセスは生きているが poller 停止」とみなす（20 分）。 */
    const val STALE_THRESHOLD_MS = 20 * 60_000L

    /** 強制再起動の最短間隔（再起動ループ防止のクールダウン、10 分）。 */
    const val RESTART_COOLDOWN_MS = 10 * 60_000L

    /** rung1: poll 不達がこの時間を超えたら reportNetworkConnectivity で再検証を促す（軽い第一手・全機、10分）。 */
    const val REVALIDATE_THRESHOLD_MS = 10 * 60_000L

    /**
     * L3 復帰 reboot（Device Owner 機のみ）の発火閾値。poll 成功がこの時間 無い＝サービス再起動でも
     * 直らないネットワーク層（DHCP/認証/DNS/経路）の死。STALE_THRESHOLD(20分)で先にサービス再起動を
     * 試した上で、なお回復しないこの 30 分超で端末再起動へエスカレーションする。
     */
    const val REBOOT_THRESHOLD_MS = 30 * 60_000L

    /** reboot のクールダウン（reboot ループ防止、60 分）。 */
    const val REBOOT_COOLDOWN_MS = 60 * 60_000L

    /** 次回ティックを予約する（onReceive から毎回再武装＝自己再帰）。 */
    fun schedule(context: Context) {
        setIdleAlarm(context, SystemClock.elapsedRealtime() + INTERVAL_MS, REQ_TICK)
        Log.i(TAG, "watchdog armed (+${INTERVAL_MS / 60_000}min)")
    }

    /** クラッシュ直後やサービス強制再起動時に、できるだけ早く一度だけ蘇生させる（既定 ~3 秒後）。 */
    fun scheduleRestart(context: Context, delayMs: Long = 3000L) {
        setIdleAlarm(context, SystemClock.elapsedRealtime() + delayMs, REQ_RESTART)
        Log.i(TAG, "restart alarm armed (+${delayMs}ms)")
    }

    /**
     * 1 ティック分の自己回復処理（AlarmManager / WorkManager の両経路から呼ぶ共通ロジック）。
     *  1. 常駐サービスの存在を保証（死んでいれば蘇生・冪等）。
     *  2. **liveness 自己診断**: プロセスは生きていても poller だけ永久停止している場合
     *     （ensureRunning は冪等で死んだ poller を蘇生できない）を、最終成功時刻の staleness で検知し、
     *     クールダウン付きでサービスを強制再生成（onCreate 再実行 → poller 作り直し）する。
     */
    fun tick(context: Context) {
        try {
            BleService.ensureRunning(context)
        } catch (e: Throwable) {
            Log.w(TAG, "ensureRunning failed: ${e.message}")
        }
        // 安全網: Wi-Fi サイクル等でラジオが OFF 固着（機内モード ON / wifi_on=0）していたら強制復帰
        // （プロセス kill で in-app 復帰が飛んだ場合の救済。永続設定なので reboot しても固着しうる＝この網が必須）。
        runCatching { NetworkCycle.clearStuckRadioOff(context) }
        // 非DO 救済（恒久オフライン防止の最重要網）: Wi-Fi が OFF 固着していると下の (A) は hasActiveNetwork=false で
        // 発火せず、clearStuckRadioOff の setWifiEnabled(true) も非DOでは no-op。a11y の Settings トグルだけが ON に
        // 戻せる唯一の手段なので、正規サイクル外で Wi-Fi OFF を見つけたらここで直接 a11y を起こす（5am アラームを
        // 待たずに 15分以内で復帰）。in-progress 復帰中の OFF 窓は airplaneCycleUntilMs 猶予で抑止し、a11y 側の
        // running ガードと二重で多重起動を防ぐ。a11y 未有効なら trigger は no-op で素通り（旧フォールバックは下の (A)）。
        runCatching {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null && !wm.isWifiEnabled &&
                System.currentTimeMillis() >= Config.airplaneCycleUntilMs(context)
            ) {
                if (WifiRecoveryAccessibilityService.trigger(context)) {
                    Log.w(TAG, "wifi disabled outside cycle -> a11y toggle ON (非DO救済・5amを待たない)")
                }
            }
        }
        try {
            val now = System.currentTimeMillis()
            val lastPoll = Config.lastPollSuccessMs(context)
            if (lastPoll <= 0L) return // 未だ一度も poll 成功なし（新規 / 未設定）→ 触らない
            val pollStaleMs = now - lastPoll

            // rung1（軽い・特権不要・全機）: poll 不達が続くなら、まず OS に再検証を促す
            // （reportNetworkConnectivity）。検証状態の固着/一過性ならこれで戻る。毎 tick 冪等・無害。
            if (pollStaleMs > REVALIDATE_THRESHOLD_MS) {
                runCatching { NetworkCycle.revalidate(context) }
            }

            // (A) 重い L3 復帰（接続はあるが poll 不達＝サービス再起動でも直らないネットワーク層の死に限る）。
            //     DO/非DO 共通でアクセシビリティの Wi-Fi トグル張り直し（OFF→ON で fresh セッション取り直し）。
            //     DO 機（HKC 等）も startRecovery が設定アプリをロックタスク許可リストに加えて Settings を開く。
            //     reboot は HKC で電源 OFF に落ちる（今夜実証）ため自動復帰では使わない。
            //     a11y 未有効なら旧フォールバック（機内モードトグル。OEM が無視しうるが害なし）。
            //     AP ごと無接続の時は無駄なので hasActiveNetwork に限定。60分クールダウンを共有（lastRebootMs）。
            if (pollStaleMs > REBOOT_THRESHOLD_MS &&
                now - Config.lastRebootMs(context) > REBOOT_COOLDOWN_MS &&
                hasActiveNetwork(context)
            ) {
                if (WifiRecoveryAccessibilityService.trigger(context)) {
                    Config.setLastRebootMs(context, now) // a11y 起動成功時のみ 60分クールダウン消費
                    Log.w(TAG, "poll stale ${pollStaleMs / 60_000}min -> a11y Wi-Fi 張り直し (DO/非DO)")
                } else {
                    // a11y 未バインド（プロセス再起動直後等）→ 旧トグルfallback。クールダウンは消費せず次tickで a11y 再試行。
                    Log.w(TAG, "poll stale ${pollStaleMs / 60_000}min (a11y未有効) -> airplane cycle fallback")
                    NetworkCycle.cycleViaAirplane(context)
                }
                return
            }

            // (B) poller 自己診断（既存）: プロセス生存だが poller 停止 → サービス強制再生成。
            if (pollStaleMs > STALE_THRESHOLD_MS &&
                now - Config.lastForceRestartMs(context) > RESTART_COOLDOWN_MS
            ) {
                Log.w(TAG, "poll stale ${pollStaleMs / 60_000}min -> force restart service")
                Config.setLastForceRestartMs(context, now)
                BleService.forceRestart(context)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "liveness check failed: ${e.message}")
        }
    }

    /** 既定ネットワーク（Wi-Fi 等）に接続しているか。reboot を「接続あり・経路死」のケースに限るためのガード。 */
    private fun hasActiveNetwork(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetwork != null
    }.getOrDefault(false)

    private fun setIdleAlarm(context: Context, triggerAtElapsed: Long, reqCode: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = buildPendingIntent(context, reqCode)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsed, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsed, pi)
            }
        } catch (e: SecurityException) {
            // 正確アラーム権限が無ければ inexact で妥協（蘇生が多少遅れても止めない）。
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsed, pi)
        }
    }

    private fun buildPendingIntent(context: Context, reqCode: Int): PendingIntent {
        val intent = Intent(context, WatchdogReceiver::class.java).setAction(ACTION_TICK)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, reqCode, intent, flags)
    }
}

/** ウォッチドッグ発火受信。共通の [Watchdog.tick] を実行し、次回を再武装する（どちらも例外で止めない）。 */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Watchdog.tick(context)
        try {
            Watchdog.schedule(context)
        } catch (e: Throwable) {
            Log.w("Watchdog", "reschedule failed: ${e.message}")
        }
    }
}
