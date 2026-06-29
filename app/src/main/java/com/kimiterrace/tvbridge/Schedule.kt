package com.kimiterrace.tvbridge

import android.content.Context
import androidx.core.content.edit
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject

/**
 * サイネージ表示の 1 つの ON/OFF 窓（分単位）。`onHour:onMinute` 〜 `offHour:offMinute` に表示。
 * `contains` は v2 サーバ側 `isSignageOffHours` の窓判定と一致させる（同日窓 on<off / 日跨ぎ on>off /
 * 縮退 on==off=終日 ON）。複数窓は v2 が同日内（on<off）を保証するが、日跨ぎ窓も互換のため許容する。
 */
data class ScheduleWindow(
    val onHour: Int,
    val onMinute: Int,
    val offHour: Int,
    val offMinute: Int,
) {
    fun contains(curMinutes: Int): Boolean {
        val on = onHour * 60 + onMinute
        val off = offHour * 60 + offMinute
        return when {
            on == off -> true // 縮退 = 終日 ON
            on < off -> curMinutes in on until off
            else -> curMinutes >= on || curMinutes < off // 日跨ぎ窓
        }
    }
}

/**
 * TV ON/OFF スケジュールの設定モデル。
 *
 * - enabled: 全体オン/オフ
 * - onHour/onMinute: 朝 ON 時刻（黒画面を解除）。`windows` が空のときの単一窓（旧 APK 互換の包含窓）
 * - offHour/offMinute: 夜 OFF 時刻（黒画面表示）
 * - daysMask: ビットマスク。Calendar.SUNDAY..Calendar.SATURDAY（1..7）の bit を立てる
 * - windows: **複数の表示時間帯**（分単位）。非空ならこれが正準で、単一の onHour/offHour より優先する
 *   （v2 の `schedule_windows` 由来。昼休み消灯など）。空なら従来どおり単一窓で判定する（後方互換）。
 *
 * 既定値: 平日 月〜金、7:30 ON、22:00 OFF、複数窓なし。
 */
data class ScheduleConfig(
    val enabled: Boolean,
    val onHour: Int,
    val onMinute: Int,
    val offHour: Int,
    val offMinute: Int,
    val daysMask: Int,
    val windows: List<ScheduleWindow> = emptyList(),
) {
    fun isDayActive(dayOfWeek: Int): Boolean = (daysMask and (1 shl dayOfWeek)) != 0

    fun isCurrentlyInOffPeriod(now: Calendar): Boolean {
        if (!enabled) return false
        if (!isDayActive(now.get(Calendar.DAY_OF_WEEK))) {
            // 曜日対象外の場合、当日中はずっと OFF（休日も画面消す方針）
            return true
        }
        val curMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        // 複数窓: どれか 1 つでも窓内なら ON、どれにも入らなければ OFF。
        if (windows.isNotEmpty()) {
            return windows.none { it.contains(curMinutes) }
        }

        // 単一窓（後方互換）。OFF→ON が日付をまたがない前提（例: ON=07:30, OFF=22:00）。
        val onMinutes = onHour * 60 + onMinute
        val offMinutes = offHour * 60 + offMinute
        return if (onMinutes < offMinutes) {
            curMinutes < onMinutes || curMinutes >= offMinutes
        } else {
            // 万一 ON>=OFF の異常値ならアクティブにしない
            false
        }
    }

    /** 判定に使う実効的な窓リスト（複数窓があればそれ、無ければ単一窓を 1 件）。アラーム予約で使う。 */
    fun effectiveWindows(): List<ScheduleWindow> =
        if (windows.isNotEmpty()) windows
        else listOf(ScheduleWindow(onHour, onMinute, offHour, offMinute))

    companion object {
        private const val PREFS = "tv_schedule"
        private const val K_ENABLED = "enabled"
        private const val K_ON_H = "on_hour"
        private const val K_ON_M = "on_minute"
        private const val K_OFF_H = "off_hour"
        private const val K_OFF_M = "off_minute"
        private const val K_DAYS = "days_mask"
        private const val K_WINDOWS = "windows"

        // 既定: 平日のみ
        val DEFAULT_DAYS_MASK: Int =
            (1 shl Calendar.MONDAY) or
                (1 shl Calendar.TUESDAY) or
                (1 shl Calendar.WEDNESDAY) or
                (1 shl Calendar.THURSDAY) or
                (1 shl Calendar.FRIDAY)

        val DEFAULT = ScheduleConfig(
            enabled = false,
            onHour = 7, onMinute = 30,
            offHour = 22, offMinute = 0,
            daysMask = DEFAULT_DAYS_MASK,
        )

        fun load(context: Context): ScheduleConfig {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return ScheduleConfig(
                enabled = p.getBoolean(K_ENABLED, DEFAULT.enabled),
                onHour = p.getInt(K_ON_H, DEFAULT.onHour),
                onMinute = p.getInt(K_ON_M, DEFAULT.onMinute),
                offHour = p.getInt(K_OFF_H, DEFAULT.offHour),
                offMinute = p.getInt(K_OFF_M, DEFAULT.offMinute),
                daysMask = p.getInt(K_DAYS, DEFAULT.daysMask),
                windows = parseWindowsJson(p.getString(K_WINDOWS, "") ?: ""),
            )
        }

        fun save(context: Context, config: ScheduleConfig) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putBoolean(K_ENABLED, config.enabled)
                putInt(K_ON_H, config.onHour)
                putInt(K_ON_M, config.onMinute)
                putInt(K_OFF_H, config.offHour)
                putInt(K_OFF_M, config.offMinute)
                putInt(K_DAYS, config.daysMask)
                putString(K_WINDOWS, windowsToJson(config.windows))
            }
        }

        /** 複数窓を SharedPreferences 保存用 JSON 文字列へ（空なら "[]"）。 */
        fun windowsToJson(windows: List<ScheduleWindow>): String {
            val arr = JSONArray()
            for (w in windows) {
                arr.put(
                    JSONObject()
                        .put("on_hour", w.onHour)
                        .put("on_minute", w.onMinute)
                        .put("off_hour", w.offHour)
                        .put("off_minute", w.offMinute),
                )
            }
            return arr.toString()
        }

        /** JSON 文字列（または v2 の schedule_windows 配列文字列）を窓リストへ。壊れた値は空にフォールバック。 */
        fun parseWindowsJson(s: String): List<ScheduleWindow> {
            if (s.isBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(s)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    ScheduleWindow(
                        onHour = o.optInt("on_hour", 0),
                        onMinute = o.optInt("on_minute", 0),
                        offHour = o.optInt("off_hour", 0),
                        offMinute = o.optInt("off_minute", 0),
                    )
                }
            }.getOrDefault(emptyList())
        }
    }
}
