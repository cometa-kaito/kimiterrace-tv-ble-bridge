package com.kimiterrace.tvbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 端末起動時に BleService を自動起動する。
 * Google TV が再起動した後でも自動でセンサ受信を再開する。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "boot intent: ${intent.action}")

        // スケジュール再予約は webhook 設定の有無に関係なく実施
        try {
            ScheduleManager.rescheduleAll(context)
        } catch (e: Throwable) {
            Log.w(TAG, "schedule reschedule failed on boot", e)
        }

        // 再起動で戻る no-sleep 設定（screen_off_timeout / sleep_timeout / screensaver）を毎回適用し直す
        try {
            KeepAwakeManager.applyNoSleepSettings(context)
        } catch (e: Throwable) {
            Log.w(TAG, "applyNoSleepSettings failed on boot", e)
        }

        // ConfigPoller（設定/死活/スケジュール ポーリング）は BleService が常駐して回す。
        // webhook_url（センサ用）の有無に関係なく必要なので、ここで必ず BleService を起動する。
        // （旧実装は webhook 未設定だと起動せず＝サイネージ専用運用で死活/スケジュール/設定syncが止まっていた）
        try {
            val svc = Intent(context, BleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        } catch (e: Throwable) {
            // Android 12+ のバックグラウンド FGS 起動制限等で弾かれても落とさず、直後に再試行を仕込む。
            Log.w(TAG, "start BleService on boot failed; scheduling retry", e)
            try { Watchdog.scheduleRestart(context, 5000L) } catch (_: Throwable) {}
        }

        // 常駐の最後の砦（定期ウォッチドッグ）を武装し直す。OEM kill / 想定外停止からの自動蘇生用。
        try {
            Watchdog.schedule(context)
        } catch (e: Throwable) {
            Log.w(TAG, "watchdog schedule failed on boot", e)
        }

        // 起動直後の画面状態を現在時刻に合わせる
        //（OFF 期間→黒画面 / ON 期間→サイネージ。判定は ScheduleManager に一元化）
        try {
            ScheduleManager.applyCurrentState(context)
        } catch (e: Throwable) {
            Log.w(TAG, "applyCurrentState failed on boot", e)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
