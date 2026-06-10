package com.kimiterrace.tvbridge

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * リモート設定のポーリング。
 *
 * 一定間隔で `Config.configEndpoint(context)` に GET し、返ってきた JSON で
 * SharedPreferences と動作中の Activity を更新する。エンドポイント未設定や
 * 通信失敗時は何もせず次の周期へ。
 *
 * レスポンス形式（version は monotonic な整数 or timestamp）:
 * ```json
 * {
 *   "version": 1717070000000,
 *   "config": {
 *     "target_mac": "DC:A5:B3:C2:98:D7",
 *     "webhook_url": "https://.../api/switchbot-webhook?key=...",
 *     "signage_url": "https://...",
 *     "schedule": {
 *       "enabled": true,
 *       "on_hour": 7, "on_minute": 30,
 *       "off_hour": 22, "off_minute": 0,
 *       "days_mask": 124
 *     }
 *   },
 *   "commands": {
 *     "signage_reload": false,
 *     "signage_open": false,
 *     "signage_exit": false,
 *     "wake": false,
 *     "service_restart": false
 *   }
 * }
 * ```
 *
 * - config.* は値が変わったときだけ反映（不要な書き込み回避）
 * - commands.* は true なら 1 回だけ実行（version が同じなら再実行しない）
 */
class ConfigPoller(
    private val context: Context,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    scope: CoroutineScope,
) {
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    init {
        scope.launch(Dispatchers.IO) {
            // 起動直後にも一度叩く
            try { pollOnce() } catch (_: Throwable) {}
            while (true) {
                delay(pollIntervalMs)
                try {
                    pollOnce()
                } catch (e: Throwable) {
                    Log.d(TAG, "poll skipped: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    private fun pollOnce() {
        val rawEndpoint = Config.configEndpoint(context)
        if (rawEndpoint.isBlank()) {
            return  // 未設定なら何もしない（PoC 初期は config endpoint なしでも動作）
        }

        // device_id を query に追加（既にある場合は重複しないようマージ）
        val deviceId = Config.deviceId(context)
        val endpoint = if (rawEndpoint.contains("device_id=")) {
            rawEndpoint
        } else if (rawEndpoint.contains("?")) {
            "$rawEndpoint&device_id=$deviceId"
        } else {
            "$rawEndpoint?device_id=$deviceId"
        }

        // FCM トークンがあれば query に付与して v2 へ報告（v2 が遠隔起動プッシュの宛先を知るため）。
        val endpointWithFcm = Config.fcmToken(context).let { t ->
            if (t.isNotBlank()) "$endpoint&fcmToken=$t" else endpoint
        }
        val req = Request.Builder().url(endpointWithFcm).get().build()
        val resp = httpClient.newCall(req).execute()
        resp.use { r ->
            if (!r.isSuccessful) {
                Log.d(TAG, "endpoint returned ${r.code}")
                return
            }
            val body = r.body?.string() ?: return
            try {
                applyResponse(JSONObject(body))
            } catch (e: Throwable) {
                Log.w(TAG, "parse failed", e)
            }
        }
    }

    private fun applyResponse(json: JSONObject) {
        val version = json.optLong("version", 0L)
        val lastVersion = Config.configVersion(context)
        val isNew = version > lastVersion

        // 設定の適用（常に差分検出して更新）
        val cfgJson = json.optJSONObject("config")
        if (cfgJson != null) {
            applyConfigFields(cfgJson)
        }

        // コマンドの適用（version が新しい時だけ）
        if (isNew) {
            val cmdJson = json.optJSONObject("commands")
            if (cmdJson != null) {
                applyCommands(cmdJson)
            }
            Config.setConfigVersion(context, version)
        }
    }

    private fun applyConfigFields(cfg: JSONObject) {
        cfg.optString("target_mac").takeIf { it.isNotBlank() }?.let { mac ->
            if (mac != Config.targetMac(context)) {
                Config.setTargetMac(context, mac)
                Log.i(TAG, "target_mac updated -> $mac")
            }
        }
        cfg.optString("webhook_url").takeIf { it.isNotBlank() }?.let { url ->
            if (url != Config.webhookUrl(context)) {
                Config.setWebhookUrl(context, url)
                Log.i(TAG, "webhook_url updated")
            }
        }
        cfg.optString("signage_url").takeIf { it.isNotBlank() }?.let { url ->
            if (url != Config.signageUrl(context)) {
                Config.setSignageUrl(context, url)
                Log.i(TAG, "signage_url updated -> $url")
                // 既に SignageActivity が動いていれば URL を差し替え
                context.sendBroadcast(
                    Intent(SignageActivity.ACTION_UPDATE_URL)
                        .setPackage(context.packageName)
                        .putExtra(SignageActivity.EXTRA_URL, url)
                )
            }
        }
        cfg.optJSONObject("schedule")?.let { sched ->
            val existing = ScheduleConfig.load(context)
            val newSched = ScheduleConfig(
                enabled = sched.optBoolean("enabled", existing.enabled),
                onHour = sched.optInt("on_hour", existing.onHour),
                onMinute = sched.optInt("on_minute", existing.onMinute),
                offHour = sched.optInt("off_hour", existing.offHour),
                offMinute = sched.optInt("off_minute", existing.offMinute),
                daysMask = sched.optInt("days_mask", existing.daysMask),
            )
            if (newSched != existing) {
                ScheduleConfig.save(context, newSched)
                ScheduleManager.rescheduleAll(context)
                // スケジュール変更を即時に画面へ反映（OFF時間帯のさなかの有効化でも黒画面化）
                ScheduleManager.applyCurrentState(context)
                Log.i(TAG, "schedule updated")
            }
        }
    }

    private fun applyCommands(cmd: JSONObject) {
        if (cmd.optBoolean("signage_reload", false)) {
            Log.i(TAG, "command: signage_reload")
            context.sendBroadcast(
                Intent(SignageActivity.ACTION_RELOAD).setPackage(context.packageName)
            )
        }
        if (cmd.optBoolean("signage_open", false)) {
            Log.i(TAG, "command: signage_open")
            context.startActivity(
                Intent(context, SignageActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
        if (cmd.optBoolean("signage_exit", false)) {
            Log.i(TAG, "command: signage_exit")
            context.sendBroadcast(
                Intent(SignageActivity.ACTION_EXIT).setPackage(context.packageName)
            )
        }
        if (cmd.optBoolean("wake", false)) {
            // 管理側から送る復帰信号：no-sleep 設定を再適用し、サイネージを前面へ戻す
            Log.i(TAG, "command: wake")
            KeepAwakeManager.forceWake(context)
        }
        // service_restart 等はリスクが高いので段階的に追加
    }

    companion object {
        private const val TAG = "ConfigPoller"
        const val DEFAULT_POLL_INTERVAL_MS: Long = 60_000L  // 1分
    }
}
