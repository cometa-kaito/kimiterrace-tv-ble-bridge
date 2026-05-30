package com.kimiterrace.tvbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * ScheduleManager から予約されたアラームを受信。
 * - ALARM_OFF: BlackScreenActivity を全画面起動
 * - ALARM_ON:  BlackScreenActivity に終了 broadcast 送信
 *
 * 発火後は次の周期を再予約する（AlarmManager は1回切りなので）。
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "alarm fired: ${intent.action}")

        when (intent.action) {
            ScheduleManager.ACTION_ALARM_OFF -> {
                // 黒画面 Activity 起動
                val launch = Intent(context, BlackScreenActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                try {
                    context.startActivity(launch)
                } catch (e: Throwable) {
                    Log.e(TAG, "startActivity failed", e)
                }
                // ベストエフォート: HDMI-CEC で本物の standby も試す
                CecHelper.tryStandby(context)
            }

            ScheduleManager.ACTION_ALARM_ON -> {
                context.sendBroadcast(
                    Intent(BlackScreenActivity.ACTION_DISMISS).setPackage(context.packageName)
                )
                CecHelper.tryWakeUp(context)
            }
        }

        // 次の発火を予約
        ScheduleManager.rescheduleAll(context)
    }

    companion object {
        private const val TAG = "ScheduleAlarm"
    }
}
