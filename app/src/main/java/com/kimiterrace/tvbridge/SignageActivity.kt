package com.kimiterrace.tvbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Fully Kiosk を置き換える、キミテラス TV ブリッジ内蔵のキオスク表示 Activity。
 *
 * 機能:
 *  - フルスクリーン WebView で signage_url を表示
 *  - システムバー（ステータスバー/ナビゲーションバー）を完全に隠す Immersive
 *  - 戻るボタンを無効化（ホーム抜けは OS の制約上完全には防げないがランチャ設定で制御）
 *  - 画面常時 ON、明るさ最大保持
 *  - ウォッチドッグ: 一定時間ごとに WebView 自体をリロード（メモリリーク・固まり対策）
 *  - 外部 broadcast (ACTION_RELOAD / ACTION_UPDATE_URL) で動的更新
 *
 * 起動経路:
 *  - MainActivity 設定画面の「サイネージ起動」ボタン
 *  - BootReceiver 経由（signage_url が設定されていればブート後自動）
 *  - ConfigPoller がリモート設定で commands.open_signage = true を受けたとき
 */
class SignageActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private var currentUrl: String = ""
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            Log.i(TAG, "watchdog: reload triggered (interval=${WATCHDOG_INTERVAL_MS / 1000}s)")
            safeReload()
            watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_RELOAD -> {
                    Log.i(TAG, "external reload requested")
                    safeReload()
                }
                ACTION_UPDATE_URL -> {
                    val newUrl = intent.getStringExtra(EXTRA_URL) ?: return
                    if (newUrl.isNotBlank() && newUrl != currentUrl) {
                        Log.i(TAG, "url change requested: $newUrl")
                        currentUrl = newUrl
                        webView.loadUrl(newUrl)
                    }
                }
                ACTION_EXIT -> {
                    Log.i(TAG, "external exit requested")
                    finish()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 画面常時 ON ＋ 点灯 ＋ ロック画面上に表示 ＋ フルスクリーン flag
        // FLAG_TURN_SCREEN_ON: 起動時にバックライトを点ける（朝 ON / wake からの復帰で点灯）
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        // API 27+ の新 API（FLAG_* の後継）。lockNow 後でも画面を起こして前面表示するため。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            runCatching {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            }
        }

        // ルートビューを黒地で構築
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        // WebView
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.BLACK)
            configureWebView(this)
        }
        root.addView(webView)

        // ステータス表示（URL 未設定や初回ロード前）
        statusText = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = android.view.Gravity.CENTER }
            setTextColor(Color.WHITE)
            textSize = 28f
            setPadding(40, 40, 40, 40)
        }
        root.addView(statusText)

        setContentView(root)

        // Immersive モードは setContentView 後に呼ぶ（insetsController は DecorView 確定後に有効）
        enterImmersiveMode()

        // URL ロード
        currentUrl = Config.signageUrl(this)
        if (currentUrl.isBlank()) {
            statusText.text = "サイネージURLが未設定です\n設定画面でURLを入力してください"
            statusText.visibility = View.VISIBLE
        } else {
            statusText.visibility = View.GONE
            webView.loadUrl(currentUrl)
        }

        // ウォッチドッグ起動
        watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
    }

    private fun configureWebView(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            allowContentAccess = true
            allowFileAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // 一部端末で必要
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true

        // 遠隔起動 / 自己回復チャネル: 裏方サービス(BleService)が死んでも、前面で生存する WebView から
        // 蘇生できる。signage ページ(v2・自社ドメインのみ読込・外部ナビは shouldOverrideUrlLoading で遮断)が
        // 読み込み毎に window.AndroidKiosk.ensureService() を呼ぶ想定。
        wv.addJavascriptInterface(KioskBridge(applicationContext), "AndroidKiosk")

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                // ナビゲーションは全部 WebView 内部で完結（外部ブラウザに飛ばさない）
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                Log.i(TAG, "page loaded: $url")
                statusText.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                val msg = "ロードエラー: ${error?.description ?: "unknown"} (${request?.url})"
                Log.w(TAG, msg)
                // メインフレームのエラーのみ表示
                if (request?.isForMainFrame == true) {
                    statusText.text = msg
                    statusText.visibility = View.VISIBLE
                }
            }

            // WebView のレンダラプロセスが落ちた（OOM 等）場合の回復。未処理だと白画面固着、または
            // Activity ごとクラッシュになる。true を返してシステムに kill させず、死んだ WebView を破棄して
            // Activity を作り直す（同じ WebView を使い続けると以後ずっと白画面のまま）。
            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?,
            ): Boolean {
                Log.w(TAG, "WebView renderer gone (crashed=${detail?.didCrash()}) -> recreate activity")
                runCatching {
                    (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                    webView.destroy()
                }
                runCatching { recreate() }
                return true
            }
        }
        wv.webChromeClient = WebChromeClient()
    }

    /**
     * WebView から呼べる遠隔/自己回復ブリッジ。WebView は裏方サービスが死んでも前面で生き続けるため、
     * ここから BleService を起動し直せる。`@JavascriptInterface` は信頼ページ(自社 signage)のみが呼ぶ
     * （WebView は signage_url=app.school-signage.net のみ読込・外部ナビ遮断）。露出は ensureService のみ。
     */
    private class KioskBridge(private val appContext: Context) {
        @JavascriptInterface
        fun ensureService() {
            BleService.ensureRunning(appContext)
        }
    }

    private fun safeReload() {
        try {
            webView.reload()
        } catch (e: Throwable) {
            Log.w(TAG, "reload failed", e)
        }
    }

    private fun enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                window.setDecorFitsSystemWindows(false)
            } catch (_: Throwable) {}
            window.insetsController?.let {
                try {
                    it.hide(android.view.WindowInsets.Type.systemBars())
                    it.systemBarsBehavior =
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } catch (_: Throwable) {}
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    /**
     * Device Owner ならキオスク（lock task）を開始してホーム/戻るによる離脱を抑止する。
     *
     * 防御的:
     *  - Device Owner でなければ何もしない（開発機を lock task で固めてブリックさせない）。
     *  - 既に lock task 中なら二重開始しない。
     *  - 例外は握りつぶす（非対応・権限不足でクラッシュさせない）。
     */
    private fun maybeStartLockTask() {
        runCatching {
            if (!Config.kioskEnabled(this)) return  // no-DO/kiosk=off: ピン留めしない（HOME/Back で抜けられる）
            if (!PowerController.isDeviceOwner(this)) return  // 開発機を固めない
            // 念のため許可リストへ自分を登録（Device Owner のみ有効）
            PowerController.allowLockTaskSelf(this)

            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val alreadyLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE
            } else {
                @Suppress("DEPRECATION")
                am.isInLockTaskMode
            }
            if (!alreadyLocked) {
                startLockTask()
                Log.i(TAG, "lock task started (kiosk)")
            }
        }.onFailure { Log.w(TAG, "startLockTask skipped: ${it.message}") }
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        enterImmersiveMode()
        maybeStartLockTask()

        val filter = IntentFilter().apply {
            addAction(ACTION_RELOAD)
            addAction(ACTION_UPDATE_URL)
            addAction(ACTION_EXIT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(controlReceiver, filter)
        }

        // URL が外から変更された場合のキャッチアップ
        val latestUrl = Config.signageUrl(this)
        if (latestUrl.isNotBlank() && latestUrl != currentUrl) {
            currentUrl = latestUrl
            webView.loadUrl(latestUrl)
        }
    }

    override fun onPause() {
        isForeground = false
        try { unregisterReceiver(controlReceiver) } catch (_: Throwable) {}
        super.onPause()
    }

    override fun onDestroy() {
        watchdogHandler.removeCallbacks(watchdogRunnable)
        try {
            webView.stopLoading()
            webView.destroy()
        } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onBackPressed() {
        // 戻るボタンで WebView 内ナビゲーションのみ。アプリ終了は不可
        if (webView.canGoBack()) {
            webView.goBack()
        }
        // super.onBackPressed() を呼ばずに離脱を防ぐ
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // ホームボタン以外のすべての特殊キーを WebView に流す
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val TAG = "SignageActivity"
        private const val WATCHDOG_INTERVAL_MS = 3_600_000L  // 1時間ごとに自動リロード

        /** サイネージが前面で可視か。KeepAwakeManager が「前面取りこぼし」判定に使う。 */
        @JvmStatic
        @Volatile
        var isForeground: Boolean = false

        const val ACTION_RELOAD = "com.kimiterrace.tvbridge.SIGNAGE_RELOAD"
        const val ACTION_UPDATE_URL = "com.kimiterrace.tvbridge.SIGNAGE_UPDATE_URL"
        const val ACTION_EXIT = "com.kimiterrace.tvbridge.SIGNAGE_EXIT"
        const val EXTRA_URL = "url"
    }
}
