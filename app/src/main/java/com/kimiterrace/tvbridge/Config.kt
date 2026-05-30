package com.kimiterrace.tvbridge

import android.content.Context
import androidx.core.content.edit

/**
 * 設定値の永続ストア（MainActivity のフォームで編集、Service が読み出す）。
 *
 * - target_mac     : 対象センサーの MAC（DC:A5:B3:C2:98:D7）
 * - webhook_url    : Vercel /api/switchbot-webhook のフル URL（?key=... 含む）
 * - last_motion    : 起動時に最終既知状態を復元するため
 */
object Config {
    private const val PREFS_NAME = "tv_ble_bridge"
    private const val KEY_TARGET_MAC = "target_mac"
    private const val KEY_WEBHOOK_URL = "webhook_url"
    private const val KEY_LAST_MOTION = "last_motion"
    private const val KEY_SIGNAGE_URL = "signage_url"
    private const val KEY_CONFIG_ENDPOINT = "config_endpoint"
    private const val KEY_CONFIG_VERSION = "config_version"
    private const val KEY_AUTOLAUNCH_SIGNAGE = "autolaunch_signage"

    fun targetMac(context: Context): String {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return p.getString(KEY_TARGET_MAC, BuildConfig.DEFAULT_TARGET_MAC)!!.uppercase()
    }

    fun setTargetMac(context: Context, mac: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_TARGET_MAC, mac.uppercase())
        }
    }

    fun webhookUrl(context: Context): String {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return p.getString(KEY_WEBHOOK_URL, "")!!
    }

    fun setWebhookUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_WEBHOOK_URL, url)
        }
    }

    fun lastMotion(context: Context): Boolean? {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return if (!p.contains(KEY_LAST_MOTION)) null
        else p.getBoolean(KEY_LAST_MOTION, false)
    }

    fun setLastMotion(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_LAST_MOTION, value)
        }
    }

    // ---------- サイネージ表示 URL（WebView キオスク用） ----------

    fun signageUrl(context: Context): String {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return p.getString(KEY_SIGNAGE_URL, "")!!
    }

    fun setSignageUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_SIGNAGE_URL, url)
        }
    }

    fun autoLaunchSignage(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return p.getBoolean(KEY_AUTOLAUNCH_SIGNAGE, false)
    }

    fun setAutoLaunchSignage(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_AUTOLAUNCH_SIGNAGE, enabled)
        }
    }

    // ---------- リモート設定エンドポイント ----------

    fun configEndpoint(context: Context): String {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return p.getString(KEY_CONFIG_ENDPOINT, "")!!
    }

    fun setConfigEndpoint(context: Context, url: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_CONFIG_ENDPOINT, url)
        }
    }

    fun configVersion(context: Context): Long {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return p.getLong(KEY_CONFIG_VERSION, 0L)
    }

    fun setConfigVersion(context: Context, version: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putLong(KEY_CONFIG_VERSION, version)
        }
    }
}
