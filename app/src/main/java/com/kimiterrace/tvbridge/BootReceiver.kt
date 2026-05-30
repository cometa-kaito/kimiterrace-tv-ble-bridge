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

        // Webhook URL が未設定なら起動しない（初期セットアップが必要）
        if (Config.webhookUrl(context).isBlank()) {
            Log.w(TAG, "webhook_url not set, skipping autostart")
            return
        }

        val svc = Intent(context, BleService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc)
        } else {
            context.startService(svc)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
