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

        // Webhook URL が未設定なら BLE サービスは起動しない（初期セットアップが必要）
        if (Config.webhookUrl(context).isBlank()) {
            Log.w(TAG, "webhook_url not set, skipping BleService autostart")
            return
        }

        val svc = Intent(context, BleService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc)
        } else {
            context.startService(svc)
        }

        // 起動直後の画面状態を現在時刻に合わせる
        //（OFF 期間→黒画面 / ON 期間→サイネージ。判定は ScheduleManager に一元化）
        ScheduleManager.applyCurrentState(context)
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
