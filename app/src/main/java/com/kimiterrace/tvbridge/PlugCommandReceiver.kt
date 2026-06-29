package com.kimiterrace.tvbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * SwitchBot プラグ制御のローカルトリガ（v2 連携前の検証用 / 将来の内部利用）。
 *
 * adb から直接叩ける:
 * ```
 * adb shell am broadcast -a com.kimiterrace.tvbridge.PLUG_CYCLE --es mac AA:BB:CC:DD:EE:FF
 * adb shell am broadcast -a com.kimiterrace.tvbridge.PLUG_ON
 * ```
 * `--es mac` を渡すと Config に永続化し、次回以降は省略できる。mac 未設定なら no-op（ログのみ）。
 *
 * ⚠ 検証は「制御するモニタ自身の電源を取っていないプラグ」で行うこと（自分の電源を cycle/off すると自滅する）。
 */
class PlugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 非DOの「Wi-Fi 張り直し」テストトリガ（機内モード一瞬トグル）。adb 検証用 / 将来の内部利用。
        if (intent.action == ACTION_NET_REVALIDATE) {
            Log.i(TAG, "broadcast net_revalidate -> reportNetworkConnectivity(false) [rung1]")
            NetworkCycle.revalidate(context)
            return
        }
        if (intent.action == ACTION_NET_CYCLE) {
            Log.i(TAG, "broadcast net_cycle -> airplane toggle (Wi-Fi 張り直し) [rung2/案A]")
            NetworkCycle.cycleViaAirplane(context)
            return
        }
        if (intent.action == ACTION_NET_WIFI_TOGGLE) {
            Log.i(TAG, "broadcast net_wifi_toggle -> wifi_on 0->1 (Wi-Fi のみ張り直し) [案B]")
            NetworkCycle.cycleViaWifiSetting(context)
            return
        }
        if (intent.action == ACTION_NET_WIFI_API) {
            Log.i(TAG, "broadcast net_wifi_api -> setWifiEnabled false->true (DO実API) [案C]")
            NetworkCycle.cycleViaWifiManager(context)
            return
        }
        if (intent.action == ACTION_NET_WIFI_A11Y) {
            Log.i(TAG, "broadcast net_wifi_a11y -> アクセシビリティでWi-FiトグルOFF→ON（非DO・全機種）[案B]")
            val ok = WifiRecoveryAccessibilityService.trigger(context)
            if (!ok) Log.w(TAG, "a11y未有効: settings put secure enabled_accessibility_services …/.WifiRecoveryAccessibilityService で有効化")
            return
        }
        if (intent.action == ACTION_REBOOT_NOW) {
            Log.i(TAG, "broadcast reboot_now -> DevicePolicyManager.reboot()（DO機のみ・非DOはno-op）")
            PowerController.rebootIfOwner(context)
            return
        }
        if (intent.action == ACTION_SET_MORNING_REBOOT) {
            val on = intent.getStringExtra("on")?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: true
            Config.setMorningRebootEnabled(context, on)
            Log.i(TAG, "broadcast set_morning_reboot -> $on")
            return
        }
        if (intent.action == ACTION_SET_DAILY_RECOVERY) {
            val on = intent.getStringExtra("on")?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: true
            Config.setDailyRecoveryEnabled(context, on)
            Log.i(TAG, "broadcast set_daily_recovery -> $on")
            return
        }
        if (intent.action == ACTION_SET_RECOVERY_WIFI) {
            val on = intent.getStringExtra("on")?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: true
            Config.setRecoveryUseWifiToggle(context, on)
            Log.i(TAG, "broadcast set_recovery_wifi -> $on")
            return
        }
        val action = when (intent.action) {
            ACTION_PLUG_ON -> "on"
            ACTION_PLUG_OFF -> "off"
            ACTION_PLUG_CYCLE -> "cycle"
            ACTION_PLUG_TOGGLE -> "toggle"
            else -> return
        }
        // --es mac で渡されれば永続化も兼ねる（次回以降は省略可）。
        intent.getStringExtra("mac")?.takeIf { it.isNotBlank() }?.let { Config.setPlugMac(context, it) }
        val mac = Config.plugMac(context)
        if (mac.isBlank()) {
            Log.w(TAG, "plug mac unset -> pass --es mac AA:BB:CC:DD:EE:FF once, or Config.setPlugMac")
            return
        }
        Log.i(TAG, "broadcast plug '$action' -> $mac")
        SwitchBotPlug.control(context, mac, action)
    }

    companion object {
        private const val TAG = "PlugCmd"
        const val ACTION_PLUG_ON = "com.kimiterrace.tvbridge.PLUG_ON"
        const val ACTION_PLUG_OFF = "com.kimiterrace.tvbridge.PLUG_OFF"
        const val ACTION_PLUG_CYCLE = "com.kimiterrace.tvbridge.PLUG_CYCLE"
        const val ACTION_PLUG_TOGGLE = "com.kimiterrace.tvbridge.PLUG_TOGGLE"
        const val ACTION_NET_CYCLE = "com.kimiterrace.tvbridge.NET_CYCLE"
        const val ACTION_NET_REVALIDATE = "com.kimiterrace.tvbridge.NET_REVALIDATE"
        const val ACTION_NET_WIFI_TOGGLE = "com.kimiterrace.tvbridge.NET_WIFI_TOGGLE"
        const val ACTION_NET_WIFI_API = "com.kimiterrace.tvbridge.NET_WIFI_API"
        const val ACTION_NET_WIFI_A11Y = "com.kimiterrace.tvbridge.NET_WIFI_A11Y"
        const val ACTION_REBOOT_NOW = "com.kimiterrace.tvbridge.REBOOT_NOW"
        const val ACTION_SET_MORNING_REBOOT = "com.kimiterrace.tvbridge.SET_MORNING_REBOOT"
        const val ACTION_SET_DAILY_RECOVERY = "com.kimiterrace.tvbridge.SET_DAILY_RECOVERY"
        const val ACTION_SET_RECOVERY_WIFI = "com.kimiterrace.tvbridge.SET_RECOVERY_WIFI"
    }
}
