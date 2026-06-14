package com.kimiterrace.tvbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

/**
 * OFF 時間中、画面全体を真っ黒に覆う最前面 Activity。
 *
 * 位置付けの変更（2026-06-08）:
 *  夜間の本命は PowerController.screenOff()（Device Owner の lockNow＝バックライト OFF）。
 *  この黒画面は **lockNow が効かない機種向けの「見た目」フォールバック** に降格した。
 *  したがって FLAG_KEEP_SCREEN_ON は付けない（付けると lockNow / 画面 OFF と綱引きになり、
 *  バックライトが点いたままになる）。明るさ 0 の黒オーバーレイのみを担う。
 *
 * - 全画面 + システムバー非表示
 * - スクリーン明るさ 0
 * - FLAG_KEEP_SCREEN_ON は付けない（消灯を妨げないため）
 * - ACTION_DISMISS broadcast で finish
 */
class BlackScreenActivity : AppCompatActivity() {

    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_DISMISS) {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 黒一色の全画面ビュー
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        setContentView(root)

        // 全画面 + システムバー非表示
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.hide(
                android.view.WindowInsets.Type.systemBars()
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }

        // スクリーン明るさを 0 に（ハードウェアバックライト OFF を試行）
        val lp = window.attributes
        lp.screenBrightness = 0.0f
        window.attributes = lp

        // 注意: FLAG_KEEP_SCREEN_ON は付けない。
        // 夜間消灯の本命は PowerController.screenOff()（lockNow による画面 OFF）。
        // ここで画面 ON を維持すると lockNow と綱引きになりバックライトが消えないため、
        // この Activity は「黒オーバーレイ（見た目 OFF）」のフォールバックに徹する。

        // dismiss receiver 登録
        val filter = IntentFilter(ACTION_DISMISS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dismissReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(dismissReceiver, filter)
        }
    }

    override fun onBackPressed() {
        // BackButton を無効化（ユーザーが誤って解除しないように）
        // 緊急解除はホームボタン or アプリの停止で可能
    }

    override fun onDestroy() {
        try { unregisterReceiver(dismissReceiver) } catch (_: Throwable) {}
        super.onDestroy()
    }

    companion object {
        const val ACTION_DISMISS = "com.kimiterrace.tvbridge.BLACK_DISMISS"
    }
}
