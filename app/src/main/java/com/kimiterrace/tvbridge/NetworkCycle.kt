package com.kimiterrace.tvbridge

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * 非 Device Owner でも使える「朝一 Wi-Fi 張り直し」: 機内モードを一瞬トグルして全ラジオ(Wi-Fi/BT)を
 * 再接続させる。
 *
 * ## なぜ機内モードか（非DOの制約）
 * `WifiManager.setWifiEnabled` / `disconnect` / `reconnect` は Android 10(API29)以降 DO/PO/システム限定で
 * 通常アプリは no-op。一方 **`AIRPLANE_MODE_ON` の設定書込みは `WRITE_SECURE_SETTINGS`（本アプリは provisioning で
 * adb 付与済）で可能**で、多くのベンダー機ではシステム側オブザーバが設定変更を検知してラジオをトグルする
 * （アプリからの system ブロードキャストは不要）。→ 県 Wi-Fi の夜間 L3 セッション死(DHCP/認証/経路)を
 * 「フレッシュなセッション取り直し」で復旧する **非DO 手段**。DO 機の graceful reboot より軽い予防策。
 *
 * ## 安全
 *  - アプリ内タイマで必ず OFF へ戻す（ラジオ OFF でもアプリプロセスは生存するので in-app 復帰は確実）。
 *  - 万一プロセスが kill され OFF が飛んで ON 固着しても、`Watchdog.tick` が [clearAirplaneIfStuck] で
 *    15 分以内に強制 OFF（`AIRPLANE_MODE_ON` は永続設定なので reboot しても固着しうる＝必須の安全網）。
 *  - 正規サイクル中の猶予 `airplaneCycleUntilMs` を持たせ、Watchdog が正規サイクルを誤って OFF にしない。
 *
 * ⚠ 効くか（設定書込みだけでラジオが切替わるか）は機種依存。効かなくても 8 秒後 OFF＋Watchdog で害は無い
 *   （no-op に終わるだけ）。実機で 1 回撃てば判明する。
 */
object NetworkCycle {
    private const val TAG = "NetworkCycle"
    private const val DEFAULT_HOLD_MS = 8_000L

    /** サイクル中とみなす猶予（Watchdog の安全 OFF がこの間は介入しない）。hold ＋ 余裕。 */
    private const val CYCLE_GRACE_MS = 30_000L

    /** Settings.Global の Wi-Fi 有効フラグキー（@hide のため文字列直書き。案B の wifi_on トグル用）。 */
    private const val KEY_WIFI_ON = "wifi_on"

    /**
     * 機内モードを ON →(holdMs)→ OFF にトグルしてラジオを張り直す。`WRITE_SECURE_SETTINGS` 必須
     * （未付与なら ON 書込みが SecurityException → no-op でログのみ）。
     */
    fun cycleViaAirplane(context: Context, holdMs: Long = DEFAULT_HOLD_MS) {
        val cr = context.contentResolver
        val started = runCatching {
            Settings.Global.putInt(cr, Settings.Global.AIRPLANE_MODE_ON, 1)
        }.onFailure {
            Log.w(TAG, "airplane ON write failed (WRITE_SECURE_SETTINGS 未付与?): ${it.message}")
        }.isSuccess
        if (!started) return

        // 正規サイクル中の猶予を記録（Watchdog の安全 OFF がこの間は割り込まないように）。
        Config.setAirplaneCycleUntilMs(context, System.currentTimeMillis() + holdMs + CYCLE_GRACE_MS)
        Log.i(TAG, "airplane ON (radios cycling) -> OFF in ${holdMs}ms")

        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { Settings.Global.putInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) }
                .onSuccess { Log.i(TAG, "airplane OFF (radios re-associating)") }
                .onFailure { Log.w(TAG, "airplane OFF write failed: ${it.message}") }
            Config.setAirplaneCycleUntilMs(context, 0L)
        }, holdMs)
    }

    /**
     * 案B（非DO・WRITE_SECURE_SETTINGS）: Settings.Global "wifi_on" を 0 →(holdMs)→ 1 にして **Wi-Fi だけ**
     * 張り直す。機内モード(案A)と違い BT を巻き込まない利点があるが、近年は Wi-Fi 有効状態が内部管理化され
     * このキーを無視する OEM も多い（要実機検証）。安全は案Aと同じ（in-app 復帰＋Watchdog の clearStuckRadioOff）。
     */
    fun cycleViaWifiSetting(context: Context, holdMs: Long = DEFAULT_HOLD_MS) {
        val cr = context.contentResolver
        val started = runCatching {
            Settings.Global.putInt(cr, KEY_WIFI_ON, 0)
        }.onFailure {
            Log.w(TAG, "wifi_on=0 write failed (WRITE_SECURE_SETTINGS 未付与?): ${it.message}")
        }.isSuccess
        if (!started) return

        Config.setAirplaneCycleUntilMs(context, System.currentTimeMillis() + holdMs + CYCLE_GRACE_MS)
        Log.i(TAG, "wifi_on=0 (Wi-Fi off attempt) -> on in ${holdMs}ms")

        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { Settings.Global.putInt(cr, KEY_WIFI_ON, 1) }
                .onSuccess { Log.i(TAG, "wifi_on=1 (Wi-Fi re-enabling)") }
                .onFailure { Log.w(TAG, "wifi_on=1 write failed: ${it.message}") }
            Config.setAirplaneCycleUntilMs(context, 0L)
        }, holdMs)
    }

    /**
     * 案C（Device Owner 専用・実APIで確実）: `WifiManager.setWifiEnabled(false→true)` で Wi-Fi を実際に
     * トグルする。通常アプリは API29 以降 no-op だが **Device Owner / システムは setWifiEnabled が許可**
     * されており、設定値書込み(案A/案B)を無視する OEM でも **実 API なら radio が落ちる**。本HKC TVのように
     * 案A/案B が効かず reboot もstandbyに落ちる端末の、DO機での"端末を止めない"唯一の復旧手段。
     * 戻り値 false = 非DO/拒否で no-op。安全は in-app タイマ＋Watchdog の clearStuckRadioOff（Wi-Fi 再有効化）。
     */
    @SuppressLint("MissingPermission")
    fun cycleViaWifiManager(context: Context, holdMs: Long = DEFAULT_HOLD_MS) {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm == null) { Log.w(TAG, "no WifiManager -> skip"); return }
        val off = runCatching { wm.setWifiEnabled(false) }.getOrDefault(false)
        Log.i(TAG, "setWifiEnabled(false) allowed=$off (false=非DO/拒否で no-op) -> on in ${holdMs}ms")
        Config.setAirplaneCycleUntilMs(context, System.currentTimeMillis() + holdMs + CYCLE_GRACE_MS)
        Handler(Looper.getMainLooper()).postDelayed({
            val on = runCatching { wm.setWifiEnabled(true) }.getOrDefault(false)
            Log.i(TAG, "setWifiEnabled(true) allowed=$on")
            Config.setAirplaneCycleUntilMs(context, 0L)
        }, holdMs)
    }

    /**
     * 安全網: 正規サイクル外なのにラジオが OFF 固着（機内モード ON / wifi_on=0 / Wi-Fi 無効）していたら強制復帰
     * （kill 等で in-app の復帰が飛んだ場合の救済）。Watchdog.tick（Alarm/WorkManager の二重経路・プロセス死でも
     * 復活）から毎 tick 呼ぶ。永続設定/Wi-Fi 無効は reboot しても固着しうる＝必須の網。
     */
    @SuppressLint("MissingPermission")
    fun clearStuckRadioOff(context: Context) {
        runCatching {
            val cr = context.contentResolver
            if (System.currentTimeMillis() < Config.airplaneCycleUntilMs(context)) return // 正規サイクル中は触らない
            if (Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) == 1) {
                Settings.Global.putInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0)
                Log.w(TAG, "airplane stuck ON outside cycle -> forced OFF (safety)")
            }
            if (Settings.Global.getInt(cr, KEY_WIFI_ON, 1) == 0) {
                Settings.Global.putInt(cr, KEY_WIFI_ON, 1)
                Log.w(TAG, "wifi_on stuck 0 outside cycle -> forced 1 (safety)")
            }
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null && !wm.isWifiEnabled) {
                wm.setWifiEnabled(true)
                Log.w(TAG, "wifi disabled outside cycle -> setWifiEnabled(true) (safety・DO機)")
            }
        }.onFailure { Log.d(TAG, "clearStuckRadioOff skip: ${it.message}") }
    }

    /**
     * rung1（特権不要・非DO）: OS に「現在のネットは不通」と申告し、内蔵 NetworkMonitor の**再検証**
     * （captive portal / 検証プローブの再実行）を誘発する。検証が通れば `NET_CAPABILITY_VALIDATED` が
     * 復活し、framework が当該ネットを使えるものとして再採用する。**ただし経路/DHCP が実際に死んでいる場合は
     * 再検証も失敗し、L2 切断→再 DHCP までは保証されない**（その時は rung2 = [cycleViaAirplane]）。
     * 安全・冪等で権限不要なので、poll 不達時の「第一手（軽い再検証）」として叩く。
     */
    fun revalidate(context: Context) {
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork
            if (net != null) {
                cm.reportNetworkConnectivity(net, false)
                Log.i(TAG, "revalidate: reportNetworkConnectivity(false) -> NetworkMonitor 再検証を誘発")
            } else {
                Log.w(TAG, "revalidate: active network 無し（接続自体が無い＝再検証対象なし）")
            }
        }.onFailure { Log.w(TAG, "revalidate failed: ${it.message}") }
    }
}
