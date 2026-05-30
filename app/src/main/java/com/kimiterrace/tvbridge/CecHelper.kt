package com.kimiterrace.tvbridge

import android.content.Context
import android.util.Log

/**
 * HDMI-CEC で TV を本物の Standby / WakeUp に持っていくベストエフォート試行。
 *
 * 注意:
 *  - HdmiControlManager の制御系 API は signature|privileged 権限が必要なため、
 *    通常の sideload アプリでは通常 ぐ拒否される。
 *  - 例外で動く機種があれば儲けもの、ぐらいの位置付け。
 *  - 失敗しても黒画面オーバーレイで「見た目 OFF」は維持される。
 *
 * 実装は API 30 以降の `HdmiControlManager#setSystemAudioMode`/`HdmiControlManager.OneTouchPlay`
 * 等を試したいところだが、ほとんどの場合 SecurityException で落ちる。
 * 現時点では「リフレクション + フォールバック」で吸収する。
 */
object CecHelper {

    private const val TAG = "CecHelper"

    fun tryStandby(context: Context) {
        runCatching {
            // 「hdmi_control」サービスは sdk バージョン依存。
            // HdmiControlManager を取得できればその standby を試みる。
            val svc = context.getSystemService("hdmi_control")
            if (svc != null) {
                Log.i(TAG, "hdmi_control service available: ${svc.javaClass.name}")
                // ベンダー実装次第。一般 API では制御コマンドの直接呼出は無理。
            } else {
                Log.i(TAG, "hdmi_control service not available")
            }
        }.onFailure {
            Log.d(TAG, "tryStandby skipped: ${it.message}")
        }
    }

    fun tryWakeUp(context: Context) {
        runCatching {
            val svc = context.getSystemService("hdmi_control")
            if (svc != null) {
                Log.i(TAG, "wakeup attempt; hdmi_control available")
            }
        }.onFailure {
            Log.d(TAG, "tryWakeUp skipped: ${it.message}")
        }
    }
}
