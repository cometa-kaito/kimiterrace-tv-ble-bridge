package com.kimiterrace.tvbridge

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
        try {
            val now = System.currentTimeMillis()
            val lastPoll = Config.lastPollSuccessMs(context)
            val lastRestart = Config.lastForceRestartMs(context)
            if (lastPoll > 0L &&
                now - lastPoll > STALE_THRESHOLD_MS &&
                now - lastRestart > RESTART_COOLDOWN_MS
            ) {
                Log.w(TAG, "poll stale ${(now - lastPoll) / 60_000}min -> force restart service")
                Config.setLastForceRestartMs(context, now)
                BleService.forceRestart(context)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "liveness check failed: ${e.message}")
        }
    }

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
