package com.kimiterrace.tvbridge

import android.content.Context
import androidx.core.content.edit
import java.util.Calendar

/**
 * TV ON/OFF スケジュールの設定モデル。
 *
 * - enabled: 全体オン/オフ
 * - onHour/onMinute: 朝 ON 時刻（黒画面を解除）
 * - offHour/offMinute: 夜 OFF 時刻（黒画面表示）
 * - daysMask: ビットマスク。Calendar.SUNDAY..Calendar.SATURDAY（1..7）の bit を立てる
 *
 * 既定値: 平日 月〜金、7:30 ON、22:00 OFF。
 */
data class ScheduleConfig(
    val enabled: Boolean,
    val onHour: Int,
    val onMinute: Int,
    val offHour: Int,
    val offMinute: Int,
    val daysMask: Int,
) {
    fun isDayActive(dayOfWeek: Int): Boolean = (daysMask and (1 shl dayOfWeek)) != 0

    fun isCurrentlyInOffPeriod(now: Calendar): Boolean {
        if (!enabled) return false
        if (!isDayActive(now.get(Calendar.DAY_OF_WEEK))) {
            // 曜日対象外の場合、当日中はずっと OFF（休日も画面消す方針）
            return true
        }
        val curMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val onMinutes = onHour * 60 + onMinute
        val offMinutes = offHour * 60 + offMinute

        // OFF→ON が日付をまたがない前提（例: ON=07:30, OFF=22:00）
        return if (onMinutes < offMinutes) {
            curMinutes < onMinutes || curMinutes >= offMinutes
        } else {
            // 万一 ON>=OFF の異常値ならアクティブにしない
            false
        }
    }

    companion object {
        private const val PREFS = "tv_schedule"
        private const val K_ENABLED = "enabled"
        private const val K_ON_H = "on_hour"
        private const val K_ON_M = "on_minute"
        private const val K_OFF_H = "off_hour"
        private const val K_OFF_M = "off_minute"
        private const val K_DAYS = "days_mask"

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
            }
        }
    }
}
