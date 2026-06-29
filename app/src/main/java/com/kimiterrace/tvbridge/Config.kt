package com.kimiterrace.tvbridge

import android.bluetooth.BluetoothAdapter
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
    // no-DO 運用フラグ。true=キオスク（前面強制復帰 + lock-task ピン）, false=抜けられる（Device Owner 無し機種向け）。
    private const val KEY_KIOSK_ENABLED = "kiosk_enabled"
    // Phase 4: マルチデバイス対応
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_SCHOOL_ID = "school_id"
    private const val KEY_GRADE_ID = "grade_id"
    private const val KEY_DEPARTMENT_ID = "department_id"
    private const val KEY_CLASS_ID = "class_id"
    private const val KEY_DEVICE_LABEL = "device_label"
    private const val KEY_FCM_TOKEN = "fcm_token"
    // 夜間 OFF の方式（復帰不能スリープ対策）。値は overlay / lock のいずれか。
    private const val KEY_NIGHT_OFF_MODE = "night_off_mode"

    // 夜間 OFF の方式の定数。
    const val NIGHT_OFF_MODE_OVERLAY = "overlay"  // 既定: 擬似黒（KEEP_SCREEN_ON）で朝復帰を保証
    const val NIGHT_OFF_MODE_LOCK = "lock"        // 真の消灯（lockNow）。復帰確認済み機種向け

    fun targetMac(context: Context): String {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = p.getString(KEY_TARGET_MAC, BuildConfig.DEFAULT_TARGET_MAC)!!.uppercase()
        // 旧 lp-config の null→optString "null" 化や手動設定ミスで "NULL"/空/不正文字列が prefs に焼かれる
        // ことがある。無効な MAC をそのまま返すと BleService の ScanFilter.setDeviceAddress が
        // IllegalArgumentException を投げ起動時クラッシュの火種になる。無効値は既定 MAC に倒し、
        // 呼び出し側が常に有効な MAC を得られるようにする（fail-safe・二重防御の片側）。
        return if (BluetoothAdapter.checkBluetoothAddress(raw)) raw
        else BuildConfig.DEFAULT_TARGET_MAC.uppercase()
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

    /**
     * キオスク挙動の有効/無効（no-DO 運用フラグ）。既定 **true**＝従来どおり（前面強制復帰 + lock-task でピン）。
     *
     * `false` にすると「**抜けられるサイネージ**」になる: KeepAwakeManager の前面強制復帰をしない
     * （HOME/Back で離れられ、引き戻されない。復帰は reboot の起動時自動表示に委ねる）／lock-task もしない。
     * **Device Owner 化できる機種が少ない**ため no-DO 機での既定運用に使う（[[project_kimiteras_signage_no_device_owner_direction]]）。
     * 起動時の自動表示（{@link autoLaunchSignage}）とは**独立**＝`kiosk=false` でも boot 表示は autoLaunch 任せで残せる。
     */
    fun kioskEnabled(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return p.getBoolean(KEY_KIOSK_ENABLED, true)
    }

    fun setKioskEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_KIOSK_ENABLED, enabled)
        }
    }

    // ---------- 夜間 OFF の方式（復帰不能スリープ対策） ----------

    /**
     * 夜間 OFF の方式。
     * - "overlay"（既定）: 黒オーバーレイ(明るさ0)＋FLAG_KEEP_SCREEN_ON でパネルを起こしたまま擬似黒にする。
     *   lockNow を使わないので朝 ON は「オーバーレイを消すだけ」＝パネル再点灯不要で必ず復帰する。
     *   no-sleep 設定が効かない／firmware が深いスリープに落とすメーカーでも朝戻る、最も安全な既定。
     * - "lock": Device Owner の lockNow() でバックライトごと消す（真の消灯）。復帰が確認できた機種向け。
     *   復帰不能スリープに落ちるメーカーでは朝戻らないリスクがある。
     * 未知の値は overlay 扱い（安全側）にフォールバックする。
     */
    fun nightOffMode(context: Context): String {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val v = p.getString(KEY_NIGHT_OFF_MODE, NIGHT_OFF_MODE_OVERLAY)
        // "lock"（真の消灯=lockNow）は Device Owner でしか効かない。非DO機で lock を設定すると lockNow が
        // no-op になり、黒オーバーレイの FLAG_KEEP_SCREEN_ON も張られないまま画面だけ消える → スリープして
        // 朝まで戻らない事故になりうる。非DO機ではリモート設定の誤りでも必ず安全側 overlay に倒す（足元の地雷除去）。
        return if (v == NIGHT_OFF_MODE_LOCK && PowerController.isDeviceOwner(context)) {
            NIGHT_OFF_MODE_LOCK
        } else {
            NIGHT_OFF_MODE_OVERLAY
        }
    }

    /** overlay（擬似黒・復帰優先）モードか。 */
    fun isNightOffOverlay(context: Context): Boolean = nightOffMode(context) == NIGHT_OFF_MODE_OVERLAY

    fun setNightOffMode(context: Context, mode: String) {
        val normalized = if (mode == NIGHT_OFF_MODE_LOCK) NIGHT_OFF_MODE_LOCK else NIGHT_OFF_MODE_OVERLAY
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_NIGHT_OFF_MODE, normalized)
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

    // ---------- FCM トークン（遠隔起動プッシュの宛先。ConfigPoller が v2 へ報告） ----------

    fun fcmToken(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_FCM_TOKEN, "") ?: ""

    fun setFcmToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_FCM_TOKEN, token)
        }
    }

    // ---------- ポーリング死活の自己診断（liveness watchdog 用） ----------

    private const val KEY_LAST_POLL_OK_MS = "last_poll_ok_ms"
    private const val KEY_LAST_FORCE_RESTART_MS = "last_force_restart_ms"

    /** 直近で lp-config ポーリングに成功した壁時計時刻(ms)。0=未成功。Watchdog の staleness 判定に使う。 */
    fun lastPollSuccessMs(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(KEY_LAST_POLL_OK_MS, 0L)

    fun setLastPollSuccessMs(context: Context, ms: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putLong(KEY_LAST_POLL_OK_MS, ms) }
    }

    /** 直近で Watchdog が強制再起動を実行した壁時計時刻(ms)。再起動ループ抑止のクールダウン用。 */
    fun lastForceRestartMs(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(KEY_LAST_FORCE_RESTART_MS, 0L)

    fun setLastForceRestartMs(context: Context, ms: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putLong(KEY_LAST_FORCE_RESTART_MS, ms) }
    }

    private const val KEY_LAST_REBOOT_MS = "last_reboot_ms"

    /** 直近で Watchdog が Device Owner reboot を実行した壁時計時刻(ms)。reboot ループ抑止のクールダウン用。 */
    fun lastRebootMs(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(KEY_LAST_REBOOT_MS, 0L)

    fun setLastRebootMs(context: Context, ms: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putLong(KEY_LAST_REBOOT_MS, ms) }
    }

    // ---------- SwitchBot プラグ電源制御（BLE ゲートウェイ。on/off/cycle） ----------

    private const val KEY_PLUG_MAC = "plug_mac"

    /** 制御対象 SwitchBot Plug Mini の MAC（コロン区切り）。未設定（空）ならプラグ制御は no-op。 */
    fun plugMac(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_PLUG_MAC, "") ?: ""

    fun setPlugMac(context: Context, mac: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putString(KEY_PLUG_MAC, mac) }
    }

    // ---------- 機内モード Wi-Fi サイクル（非DOのL3復旧）の進行管理 ----------

    private const val KEY_AIRPLANE_CYCLE_UNTIL_MS = "airplane_cycle_until_ms"

    /** 正規の機内モードサイクルが進行中とみなす期限(ms)。Watchdog の安全OFFがこの間は割り込まないため。 */
    fun airplaneCycleUntilMs(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(KEY_AIRPLANE_CYCLE_UNTIL_MS, 0L)

    fun setAirplaneCycleUntilMs(context: Context, ms: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putLong(KEY_AIRPLANE_CYCLE_UNTIL_MS, ms) }
    }

    // ---------- 朝の予防的 reboot（Device Owner 機・opt-in） ----------

    private const val KEY_MORNING_REBOOT = "morning_reboot_enabled"

    /**
     * WARMUP（点灯30分前）に Device Owner 機を予防的に graceful reboot するか（既定 false＝opt-in）。
     * true かつ Device Owner のときだけ WARMUP で reboot（電源は切らずL3セッションをまっさらに取り直す）。
     * 非DO機や false のときは reboot せず Wi-Fi トグルで張り直す。
     */
    fun morningRebootEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_MORNING_REBOOT, false)

    fun setMorningRebootEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putBoolean(KEY_MORNING_REBOOT, enabled) }
    }

    // ---------- 朝5時の定時ネット復旧（表示スケジュールと独立） ----------

    private const val KEY_DAILY_RECOVERY = "daily_recovery_enabled"
    private const val KEY_RECOVERY_USE_WIFI = "recovery_use_wifi_toggle"

    /** 朝5時の定時ネット復旧を有効にするか（既定 true）。false で 05:00 復旧アラームの処理をスキップ。 */
    fun dailyRecoveryEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_DAILY_RECOVERY, true)

    fun setDailyRecoveryEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putBoolean(KEY_DAILY_RECOVERY, enabled) }
    }

    /**
     * 5時復旧の「非DO/非reboot」時に案B(`wifi_on` トグル)を使うか（既定 false＝案A 機内モード）。
     * 実機テストで効く方が判明したら adb で切り替える（SET_RECOVERY_WIFI）。
     */
    fun recoveryUseWifiToggle(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_RECOVERY_USE_WIFI, false)

    fun setRecoveryUseWifiToggle(context: Context, useWifi: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putBoolean(KEY_RECOVERY_USE_WIFI, useWifi) }
    }
}
