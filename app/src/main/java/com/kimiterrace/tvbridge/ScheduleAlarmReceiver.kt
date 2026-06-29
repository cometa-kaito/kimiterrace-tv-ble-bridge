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
        // onReceive は表示 ON/OFF 遷移と 5am 復旧の hot path。ここで未捕捉例外を投げると
        // BroadcastReceiver からプロセスへ伝播してプロセス死＝最悪「ON 遷移で画面が点かず暗いまま」になる。
        // どこで落ちても次の周期だけは必ず再武装するよう全体を try/catch で包む（crash 復活網の二重化）。
        try {
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
            ScheduleManager.ACTION_ALARM_RECOVERY -> {
                // 朝5時の定時ネット復旧（表示スケジュールと独立・毎日）。夜間に腐った L3 セッション
                // (DHCP/認証/経路) を始業前にまっさらに取り直す。
                //   DO機 + morningReboot opt-in → graceful reboot（最も確実・電源は切らない）
                //   それ以外 → rung1 再検証 ＋ アクセシビリティ Wi-Fi 張り直し（実証済・本命）。
                //              a11y 未有効時のみ案A/案B トグルへフォールバック。
                if (Config.dailyRecoveryEnabled(context)) {
                    if (Config.morningRebootEnabled(context) && PowerController.isDeviceOwner(context)) {
                        ScheduleManager.rescheduleAll(context) // 念のため（boot後も BootReceiver が再武装）
                        Log.i(TAG, "5am recovery -> graceful reboot (Device Owner, opt-in)")
                        PowerController.rebootIfOwner(context)
                        return
                    }
                    NetworkCycle.revalidate(context)
                    // Wi-Fi 張り直しは「実際に poll が滞っている時だけ」発火。正常な朝に毎回トグルすると
                    // 不要な Wi-Fi 断（＋機種により寝落ち）を招くため staleness でゲート（再検証は常時実行済）。
                    val lastPoll = Config.lastPollSuccessMs(context)
                    val pollStale = lastPoll <= 0L ||
                        System.currentTimeMillis() - lastPoll > RECOVERY_POLL_STALE_MS
                    if (pollStale) {
                        // 非DO 本命: アクセシビリティで Wi-Fi トグル張り直し。未有効なら旧トグルへフォールバック。
                        if (!WifiRecoveryAccessibilityService.trigger(context)) {
                            if (Config.recoveryUseWifiToggle(context)) {
                                NetworkCycle.cycleViaWifiSetting(context) // 案B（Wi-Fi のみ）
                            } else {
                                NetworkCycle.cycleViaAirplane(context) // 案A（全ラジオ）
                            }
                        }
                    } else {
                        Log.i(TAG, "5am recovery: poll fresh -> skip wifi toggle (revalidate only)")
                    }
                }
                ScheduleManager.rescheduleAll(context)
                Log.i(TAG, "5am recovery done")
                return
            }
        }

        // 次の発火を予約し、現在時刻に応じた画面状態を反映
        //（OFF→黒画面オーバーレイ / ON→黒画面解除＋サイネージ再表示）
        ScheduleManager.rescheduleAll(context)
        ScheduleManager.applyCurrentState(context)
        } catch (e: Throwable) {
            // hot path のどこかで落ちても、次の ON/OFF/復旧アラームだけは必ず再武装して自己回復に繋ぐ。
            Log.w(TAG, "onReceive failed: ${e.message}")
            runCatching { ScheduleManager.rescheduleAll(context) }
        }
    }

    companion object {
        private const val TAG = "ScheduleAlarm"

        /** 5am 復帰で Wi-Fi トグルを発火させる poll 滞留閾値（これ未満＝正常なら toggle しない・寝落ち回避）。 */
        private const val RECOVERY_POLL_STALE_MS = 10 * 60_000L
    }
}
