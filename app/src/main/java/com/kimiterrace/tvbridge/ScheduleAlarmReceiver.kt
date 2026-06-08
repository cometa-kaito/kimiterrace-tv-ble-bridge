package com.kimiterrace.tvbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * ScheduleManager から予約されたアラームを受信。
 * - ALARM_OFF: 画面 OFF（PowerController.screenOff＝Device Owner の lockNow が本命）
 *              ＋ BlackScreenActivity を全画面起動（見た目フォールバック）
 * - ALARM_ON:  画面 ON（PowerController.screenOn）＋黒画面解除＋サイネージ再表示
 *
 * 発火後は次の周期を再予約する（AlarmManager は1回切りなので）。
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "alarm fired: ${intent.action}")

        // 画面の点灯/消灯を実行（PowerController が内部で CEC も補助的に試みる）
        when (intent.action) {
            ScheduleManager.ACTION_ALARM_OFF -> PowerController.screenOff(context)
            ScheduleManager.ACTION_ALARM_ON -> PowerController.screenOn(context)
        }

        // 次の発火を予約し、現在時刻に応じた画面状態を反映
        //（OFF→黒画面オーバーレイ / ON→黒画面解除＋サイネージ再表示）
        ScheduleManager.rescheduleAll(context)
        ScheduleManager.applyCurrentState(context)
    }

    companion object {
        private const val TAG = "ScheduleAlarm"
    }
}
