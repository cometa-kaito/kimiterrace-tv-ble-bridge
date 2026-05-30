package com.kimiterrace.tvbridge

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar

/**
 * ScheduleConfig に基づき、AlarmManager で「次の ON 時刻」「次の OFF 時刻」を予約する。
 *
 * 起動時・設定変更時に rescheduleAll() を呼ぶ。
 * ReceiveAlarmReceiver が発火を受けて BlackScreenActivity の開閉を行う。
 */
object ScheduleManager {

    private const val TAG = "ScheduleManager"
    const val ACTION_ALARM_ON = "com.kimiterrace.tvbridge.ALARM_ON"
    const val ACTION_ALARM_OFF = "com.kimiterrace.tvbridge.ALARM_OFF"

    private const val REQ_ON = 1001
    private const val REQ_OFF = 1002

    fun rescheduleAll(context: Context) {
        val cfg = ScheduleConfig.load(context)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // 既存アラームクリア
        am.cancel(buildPendingIntent(context, ACTION_ALARM_ON, REQ_ON))
        am.cancel(buildPendingIntent(context, ACTION_ALARM_OFF, REQ_OFF))

        if (!cfg.enabled) {
            Log.i(TAG, "schedule disabled, no alarms set")
            return
        }

        val now = Calendar.getInstance()
        val nextOn = nextOccurrence(now, cfg, cfg.onHour, cfg.onMinute)
        val nextOff = nextOccurrence(now, cfg, cfg.offHour, cfg.offMinute)

        setExactAlarm(context, am, nextOn, ACTION_ALARM_ON, REQ_ON)
        setExactAlarm(context, am, nextOff, ACTION_ALARM_OFF, REQ_OFF)

        Log.i(TAG, "next ON  = ${nextOn.time}")
        Log.i(TAG, "next OFF = ${nextOff.time}")
    }

    /**
     * 指定時刻の「次回発火タイミング」を ScheduleConfig の曜日マスクを尊重して計算。
     * 当日該当曜日かつ未来の時刻ならその当日。さもなくば翌週内の最初の有効曜日へ。
     */
    private fun nextOccurrence(now: Calendar, cfg: ScheduleConfig, hour: Int, minute: Int): Calendar {
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        for (i in 0..7) {
            val candidate = (target.clone() as Calendar).apply {
                add(Calendar.DAY_OF_MONTH, i)
            }
            // 当日の場合は now より未来かをチェック
            if (i == 0 && !candidate.after(now)) continue
            if (cfg.isDayActive(candidate.get(Calendar.DAY_OF_WEEK))) {
                return candidate
            }
        }
        // どこにも該当しない（マスクが 0 など）→ 1 日後で適当に置く（実用上 0 マスクは UI で避ける）
        return (target.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
    }

    private fun setExactAlarm(
        context: Context,
        am: AlarmManager,
        at: Calendar,
        action: String,
        reqCode: Int,
    ) {
        val pi = buildPendingIntent(context, action, reqCode)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.timeInMillis, pi)
                } else {
                    // 正確なアラーム権限が無い場合は inexact で妥協
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.timeInMillis, pi)
                }
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.timeInMillis, pi)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "exact alarm denied, falling back to inexact: $e")
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.timeInMillis, pi)
        }
    }

    private fun buildPendingIntent(context: Context, action: String, reqCode: Int): PendingIntent {
        val intent = Intent(context, ScheduleAlarmReceiver::class.java).setAction(action)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(context, reqCode, intent, flags)
    }
}
