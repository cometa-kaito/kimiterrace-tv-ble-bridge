package com.kimiterrace.tvbridge

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM 受信サービス（遠隔起動チャネル）。
 *
 * v2 の死活監視が 🔴(ダウン)を検知した際、高優先度データメッセージを送ると、端末が Doze 中でも
 * 起こされて本サービスが受信し、死んでいる常駐サービス(BleService=ConfigPoller/ScheduleManager)を
 * 起動し直す。FCM サービスは BleService とは独立した OS 管理コンポーネントなので、BleService が
 * 死んでいても受信できる＝「死んだ裏方を遠隔で起こす」を実現する。
 *
 * 新トークンは prefs に保存し、ConfigPoller が次回ポーリングで v2 へ報告する（v2 が送信先を知るため）。
 */
class FcmService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        Log.i(TAG, "FCM message received (data keys=${message.data.keys}) -> ensureRunning")
        // 用途は wake 専用。種別に関わらず常駐サービスを起動し直す。
        BleService.ensureRunning(applicationContext)
    }

    override fun onNewToken(token: String) {
        Log.i(TAG, "FCM new token issued -> saved for next poll report")
        Config.setFcmToken(applicationContext, token)
    }

    companion object {
        private const val TAG = "FcmService"
    }
}
