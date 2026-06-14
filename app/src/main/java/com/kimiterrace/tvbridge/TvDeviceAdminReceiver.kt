package com.kimiterrace.tvbridge

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device Owner（端末所有者）として登録するための DeviceAdminReceiver。
 *
 * プロビジョニング時に一度だけ:
 *   adb shell dpm set-device-owner com.kimiterrace.tvbridge/.TvDeviceAdminReceiver
 * を実行して端末所有者に昇格する（アカウント未追加が条件。詳細は dist/provision-googletv.md）。
 *
 * 端末所有者になると、以下が「PC 不要・自走」で可能になる:
 *  - lockNow() による画面 OFF（バックライト OFF。夜間の消灯に使う＝[[PowerController.screenOff]]）
 *  - lock task（キオスク）でホーム/戻るによる離脱を抑止
 *
 * この Receiver 自体は最小実装（ライフサイクルをログするだけ）。実際の権限行使は
 * DevicePolicyManager 経由で PowerController / SignageActivity から行う。
 */
class TvDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "device admin disabled")
    }

    companion object {
        private const val TAG = "TvDeviceAdmin"
    }
}
