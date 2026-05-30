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

        // ベストエフォート: HDMI-CEC で本物の standby / wake も試す
        when (intent.action) {
            ScheduleManager.ACTION_ALARM_OFF -> CecHelper.tryStandby(context)
            ScheduleManager.ACTION_ALARM_ON -> CecHelper.tryWakeUp(context)
        }

        // 次の発火を予約し、現在時刻に応じた画面状態を反映
        //（OFF→黒画面 / ON→黒画面解除＋サイネージ再表示）
        ScheduleManager.rescheduleAll(context)
        ScheduleManager.applyCurrentState(context)
    }

    companion object {
        private const val TAG = "ScheduleAlarm"
    }
}
