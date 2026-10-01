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
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

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
 *
 * ポーリング間隔（#5）:
 * - 成功時: pollIntervalMs（既定 60 秒）を維持。
 * - 失敗時: 上限付き指数バックオフ + ±20% ジッタ。ネットワーク断は短い上限で頻繁に再試行。
 *
 * TODO(#5 follow-up): v2 への能動的ヘルスハートビート（outbound health POST）は
 * v2 側の受け口エンドポイントが必要なため本 APK では未実装。エンドポイント実装後に追加する。
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
            // 連続失敗回数。成功で 0 に戻す。バックオフの指数に使う。
            var failureStreak = 0
            // 起動直後にも一度叩く（結果でバックオフ初期値を決める）
            var lastResult = runCatching { pollOnce() }.getOrElse { e ->
                if (e is IOException) PollResult.NETWORK_LOSS else PollResult.FAILURE
            }
            failureStreak = updateStreak(failureStreak, lastResult)
            while (true) {
                // 成功時は steady-state（pollIntervalMs）。失敗時はバックオフ + ジッタ。
                delay(nextDelayMs(failureStreak, lastResult))
                lastResult = try {
                    pollOnce()
                } catch (e: Throwable) {
                    Log.d(TAG, "poll skipped: ${e.javaClass.simpleName}: ${e.message}")
                    if (e is IOException) PollResult.NETWORK_LOSS else PollResult.FAILURE
                }
                failureStreak = updateStreak(failureStreak, lastResult)
            }
        }
    }

    /** 成功なら連続失敗を 0 に、失敗なら +1（上限でクランプして桁あふれ回避）。 */
    private fun updateStreak(current: Int, result: PollResult): Int =
        if (result == PollResult.SUCCESS) 0 else (current + 1).coerceAtMost(MAX_STREAK)

    /**
     * 次回ポーリングまでの待機時間。
     * - 直近が成功（streak=0）: steady-state の pollIntervalMs を維持。
     * - 失敗継続: base = min(pollIntervalMs * 2^(streak-1), 上限) で頭打ちの指数バックオフ。
     *   ・通常失敗（HTTP 4xx/5xx・parse 失敗）: 上限 = MAX_BACKOFF_MS（15分）。
     *   ・NETWORK_LOSS（接続不可）: 復帰を早く拾うため上限 = NETWORK_LOSS_MAX_BACKOFF_MS（2分）に抑える。
     * いずれも ±JITTER_RATIO のジッタを掛け、複数端末の同時再試行（thundering herd）を散らす。
     */
    private fun nextDelayMs(failureStreak: Int, lastResult: PollResult): Long {
        if (failureStreak <= 0) return withJitter(pollIntervalMs)
        val ceiling = if (lastResult == PollResult.NETWORK_LOSS) {
            NETWORK_LOSS_MAX_BACKOFF_MS
        } else {
            MAX_BACKOFF_MS
        }
        val base = (pollIntervalMs.toDouble() * Math.pow(2.0, (failureStreak - 1).toDouble()))
            .toLong()
            .coerceAtMost(ceiling)
        return withJitter(base)
    }

    /** ±JITTER_RATIO のランダムジッタを掛ける（下限 1 秒）。 */
    private fun withJitter(baseMs: Long): Long {
        val delta = (baseMs * JITTER_RATIO).toLong()
        val low = (baseMs - delta).coerceAtLeast(1000L)
        // until は low より必ず大きくする（Random.nextLong の from<until 制約）。
        val until = (baseMs + delta + 1).coerceAtLeast(low + 1)
        return Random.nextLong(low, until)
    }

    private enum class PollResult { SUCCESS, FAILURE, NETWORK_LOSS }

    private fun pollOnce(): PollResult {
        val rawEndpoint = Config.configEndpoint(context)
        if (rawEndpoint.isBlank()) {
            // 未設定なら何もしない（PoC 初期は config endpoint なしでも動作）。
            // 設定待ちでバックオフを焚いても意味がないので「成功扱い」で steady-state を保つ。
            return PollResult.SUCCESS
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
        // execute() は接続不可で IOException を投げる → 呼び出し側で NETWORK_LOSS 扱い。
        val resp = httpClient.newCall(req).execute()
        return resp.use { r ->
            if (!r.isSuccessful) {
                Log.d(TAG, "endpoint returned ${r.code}")
                return@use PollResult.FAILURE
            }
            val body = r.body?.string() ?: return@use PollResult.FAILURE
            try {
                applyResponse(JSONObject(body))
                PollResult.SUCCESS
            } catch (e: Throwable) {
                Log.w(TAG, "parse failed", e)
                PollResult.FAILURE
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
                Log.i(TAG, "signage_url updated -> ${NavigationPolicy.redactForLog(url)}")
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
        const val DEFAULT_POLL_INTERVAL_MS: Long = 60_000L  // 1分（成功時の steady-state）

        // 失敗時の指数バックオフ上限（連続失敗が続いてもこれ以上は空けない）。
        private const val MAX_BACKOFF_MS: Long = 15 * 60_000L   // 15分

        // ネットワーク断時の上限（短め）。復帰検知を早めるため通常失敗より頻繁に再試行する。
        private const val NETWORK_LOSS_MAX_BACKOFF_MS: Long = 2 * 60_000L   // 2分

        // 失敗連続回数の上限クランプ（2^streak の桁あふれ防止。実害は上限到達で頭打ち）。
        private const val MAX_STREAK: Int = 16

        // ジッタ比率（±20%）。複数 TV の同時再試行を散らす。
        private const val JITTER_RATIO: Double = 0.20
    }
}
