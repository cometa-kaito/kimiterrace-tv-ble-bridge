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
    // Phase 4: マルチデバイス対応
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_SCHOOL_ID = "school_id"
    private const val KEY_GRADE_ID = "grade_id"
    private const val KEY_DEPARTMENT_ID = "department_id"
    private const val KEY_CLASS_ID = "class_id"
    private const val KEY_DEVICE_LABEL = "device_label"

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

    /**
     * リモート設定ポーリング先。
     * 既定は v2（GCP）の LP 互換エンドポイント。
     * 秘密鍵はソースに焼かないため、プロビジョニングで `?key=<V2_TV_POLL_SECRET>` を含む
     * 完全な URL を prefs に書き込む（[[dist/provision-googletv.md]] §5 / SAFE FALLBACK）。
     * key 無しの既定でも GET は飛ぶが、サーバ側で 401 等になり実害なく次周期へ。
     */
    fun configEndpoint(context: Context): String {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return p.getString(KEY_CONFIG_ENDPOINT, BuildConfig.DEFAULT_CONFIG_ENDPOINT)!!
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

    // ---------- マルチデバイス対応：device_id + 教室コンテキスト ----------

    /**
     * 端末識別子（初回読み出し時に UUIDv4 を生成して永続化）。
     * 教室移動・APK 再インストールしてもなるべく維持されるが、
     * アンインストール→クリーン再インストールでは新規発行される。
     */
    fun deviceId(context: Context): String {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = p.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val fresh = java.util.UUID.randomUUID().toString()
        p.edit { putString(KEY_DEVICE_ID, fresh) }
        return fresh
    }

    fun schoolId(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SCHOOL_ID, null)?.takeIf { it.isNotBlank() }

    fun gradeId(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_GRADE_ID, null)?.takeIf { it.isNotBlank() }

    fun departmentId(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEPARTMENT_ID, null)?.takeIf { it.isNotBlank() }

    fun classId(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CLASS_ID, null)?.takeIf { it.isNotBlank() }

    fun deviceLabel(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEVICE_LABEL, null)?.takeIf { it.isNotBlank() }

    /**
     * signage URL から school/grade/department/class クエリパラメータを抽出して保存。
     * URL が変わった瞬間に呼ぶ。
     */
    fun extractAndSaveClassroomContext(context: Context, signageUrl: String) {
        if (signageUrl.isBlank()) return
        val parsed = SignageUrlParser.parse(signageUrl) ?: return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            parsed.schoolId?.let { putString(KEY_SCHOOL_ID, it) }
            parsed.gradeId?.let { putString(KEY_GRADE_ID, it) }
            parsed.departmentId?.let { putString(KEY_DEPARTMENT_ID, it) }
            parsed.classId?.let { putString(KEY_CLASS_ID, it) }
        }
    }

    fun setDeviceLabel(context: Context, label: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_DEVICE_LABEL, label)
        }
    }
}
