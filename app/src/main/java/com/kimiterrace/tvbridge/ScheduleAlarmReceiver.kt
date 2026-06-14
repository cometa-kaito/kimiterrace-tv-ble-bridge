package com.kimiterrace.tvbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * ScheduleManager から予約されたアラームを受信。
 * - ALARM_OFF:    画面 OFF（PowerController.screenOff＝Device Owner の lockNow が本命）
 *                 ＋ BlackScreenActivity を全画面起動（見た目フォールバック）
 * - ALARM_ON:     画面 ON（PowerController.screenOn）＋黒画面解除＋サイネージ再表示
 * - ALARM_WARMUP: 始業前ウォームアップ（ON の ~30 分前, #6）。画面状態は変えずに、
 *                 夜間に劣化した常駐サービス / no-sleep 設定を生徒登校前に蘇生させる。
 *
 * 発火後は次の周期を再予約する（AlarmManager は1回切りなので）。
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "alarm fired: ${intent.action}")

        // 自己回復: このアラームは setExactAndAllowWhileIdle で Doze を貫通して必ず発火する。
        // 発火の度に常駐サービス(BleService=ConfigPoller/ScheduleManager)を起動し直すことで、
        // 夜間にプロセスが殺されても朝7:00/夜17:00の遷移でポーリング/スケジュールが自動復活する。
        BleService.ensureRunning(context)

        when (intent.action) {
            ScheduleManager.ACTION_ALARM_OFF -> PowerController.screenOff(context)
            ScheduleManager.ACTION_ALARM_ON -> PowerController.screenOn(context)
            ScheduleManager.ACTION_ALARM_WARMUP -> {
                // まだ OFF 期間（始業前）。画面は触らず、no-sleep 設定を再適用し、
                // 前面状態を OFF 期間ルールで再アサート（= サイネージは起動しない）。
                // これで朝 ON 遷移時にはサービス健全・スリープ抑止が効いた状態になっている。
                KeepAwakeManager.applyNoSleepSettings(context)
                KeepAwakeManager.reassertForegroundIfNeeded(context)
                // 次回（翌営業日）のウォームアップを予約し直して終了。画面状態は変えない。
                ScheduleManager.rescheduleAll(context)
                Log.i(TAG, "warmup done (service revived, no-sleep re-applied)")
                return
            }
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
