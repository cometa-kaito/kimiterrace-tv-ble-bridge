package com.kimiterrace.tvbridge

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 非DO機の Wi-Fi 自動張り直し（"疑似 Wi-Fi 再起動"）。
 *
 * ## 背景
 * 県ネットの夜間 L3 セッション死（接続済みなのに外向き不達。app.school-signage.net も無関係な外部も
 * SocketTimeout・別ネットや同SSID再接続で即復旧）は、**Wi-Fi を OFF→ON してフレッシュなセッションを
 * 取り直すと復旧する**（2026-06-24 1年/2年/3年で実機確認）。
 *
 * ## なぜアクセシビリティ・サービスか
 * 非DOアプリは `setWifiEnabled`/`disconnect`/`reconnect` を呼べず（API29+）、設定値書込み(機内/wifi_on)も
 * 一部 OEM（HKC 等）が無視し、HKC は setWifiEnabled(DO実API) すら no-op。**唯一全機種で効くのは「人間が
 * Settings UI で Wi-Fi をトグルする操作」**で、それを本サービスが Settings の "Wi-Fi" 行クリックで再現する。
 *
 * ## 流れ（状態認識＝必ず ON で終える）
 * [trigger] → Wi-Fi 設定を開く → "Wi-Fi" 行を探す（詳細ページなら BACK で親へ）→
 * **実 Wi-Fi 状態(WifiManager.isWifiEnabled)を読み**、ON なら OFF にして HOLD 待ち → **ON になるまでクリック＋verify
 * を最大 MAX_ON_ATTEMPTS 回**（必ず ON で終える＝OFF 固定での恒久オフラインを防ぐ）→ スケジュール状態へ復帰。
 * **Wi-Fi OFF 中も本サービスは端末上で生存**（adb 駆動だと OFF の瞬間に切れて戻せない＝サービス化が必須）。
 *
 * ## 安全
 * - 復帰中は SCREEN_BRIGHT ウェイクロック保持＋ finish で no-sleep 再アサート（非DO機の寝落ち防止）。
 * - 復帰中は `Config.airplaneCycleUntilMs` を張り、Watchdog.clearStuckRadioOff が OFF 窓を横取りしないようにする。
 * - 多重起動ガード（running）＋ MAX_SEQUENCE_MS 安全網（必ず finish で後始末：猶予解除/no-sleep/キオスク復帰）。
 * - DO 機はロックタスク許可リストに設定アプリを一時追加して Settings を開き、finish で**キオスクのみへ戻す**（脱出窓を閉じる）。
 * - 行が見つからなければ何もしない（"Scanning always available" 等の別スイッチ誤爆を避けるため危険なフォールバックはしない）。
 * - 有効化は adb で一度きり（`settings put secure enabled_accessibility_services …`）。未有効なら trigger は no-op。
 */
class WifiRecoveryAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    // 復帰実行ごとの世代。start で ++ し、各 postDelayed コールバックは捕捉した世代と一致する時だけ進む。
    // run N の遅延コールバック（安全網/各 step）が run N 完了後や run N+1 開始後に発火しても no-op になり、
    // 「stale 安全網が次の復帰を途中で finish させる」「stale step が次の復帰に割り込む」事故を防ぐ。
    @Volatile
    private var epoch = 0

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "accessibility service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // イベント駆動は使わず、trigger() からの状態機械（Handler 遅延）で進める。
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    /** 復帰シーケンス開始（多重起動はガード）。 */
    private fun startRecovery() {
        if (running) {
            Log.i(TAG, "recovery already running -> skip")
            return
        }
        running = true
        val myEpoch = ++epoch
        acquireScreenLock() // 復帰中の端末/画面スリープ抑止（非DO機が Settings 表示中に寝落ちするのを防ぐ）
        // 復帰中は Watchdog.clearStuckRadioOff が Wi-Fi を勝手に戻さないよう猶予を張る（OFF 窓の横取り防止）。
        runCatching {
            Config.setAirplaneCycleUntilMs(applicationContext, System.currentTimeMillis() + MAX_SEQUENCE_MS + 5_000L)
        }
        Log.w(TAG, "wifi recovery START (open wifi settings)")
        // 安全網: finish 未到達でも MAX_SEQUENCE_MS で必ず後始末（猶予解除/no-sleep 再アサート/キオスク復帰）。
        main.postDelayed({
            if (running && epoch == myEpoch) {
                Log.w(TAG, "wifi recovery safety-net -> finish")
                finish()
            }
        }, MAX_SEQUENCE_MS)
        // DO 機（ロックタスク）でも Settings を開けるよう許可リストに設定アプリを加える（非DOは no-op）。
        runCatching { PowerController.allowSettingsInLockTask(applicationContext) }
        runCatching {
            startActivity(
                Intent(Settings.ACTION_WIFI_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }.onFailure { Log.w(TAG, "open wifi settings failed: ${it.message}") }
        main.postDelayed({ stepToggleOff(0, myEpoch) }, OPEN_SETTLE_MS)
    }

    /** Step1: ネットワーク一覧へ（詳細ページなら BACK）→ Wi-Fi が ON なら OFF にする（既に OFF ならスキップ）。 */
    private fun stepToggleOff(attempt: Int, ep: Int) {
        if (!running || epoch != ep) return // 別世代の stale コールバックは捨てる
        val row = findWifiToggleRow()
        if (row == null) {
            if (attempt < MAX_BACK_ATTEMPTS) {
                Log.i(TAG, "wifi row not found -> BACK (${attempt + 1})")
                performGlobalAction(GLOBAL_ACTION_BACK)
                main.postDelayed({ stepToggleOff(attempt + 1, ep) }, NAV_SETTLE_MS)
            } else {
                Log.w(TAG, "wifi row not found after back attempts -> abort (wifi untouched)")
                finish()
            }
            return
        }
        if (isWifiOn()) {
            Log.w(TAG, "wifi ON -> ACTION_CLICK (toggle OFF)")
            clickRow(row)
            main.postDelayed({ stepToggleOn(0, ep) }, HOLD_MS) // OFF 保持後に ON へ
        } else {
            Log.w(TAG, "wifi already OFF -> skip OFF, ensure ON")
            main.postDelayed({ stepToggleOn(0, ep) }, NAV_SETTLE_MS)
        }
    }

    /** Step2: Wi-Fi を**確実に ON で終える**。OFF ならクリック→verify を最大 MAX_ON_ATTEMPTS 回繰り返す。 */
    private fun stepToggleOn(attempt: Int, ep: Int) {
        if (!running || epoch != ep) return // 別世代の stale コールバックは捨てる
        if (isWifiOn()) {
            Log.w(TAG, "wifi ON confirmed -> finish")
            main.postDelayed({ if (running && epoch == ep) finish() }, AFTER_ON_MS)
            return
        }
        if (attempt >= MAX_ON_ATTEMPTS) {
            // ここまでで ON にできず＝今回は諦め、次の Watchdog/5am 再試行に委ねる（OFF のまま finish しても
            // 次サイクルの stepToggleOff が「既に OFF」を検知して ON 化を試みる＝OFF 固定にはならない。
            // Watchdog は !wm.isWifiEnabled を見て 15分以内に a11y を再起動するので 5am を待たない）。
            Log.w(TAG, "wifi still OFF after $MAX_ON_ATTEMPTS attempts -> finish (retry next cycle)")
            finish()
            return
        }
        val row = findWifiToggleRow()
        if (row != null) {
            Log.w(TAG, "wifi OFF -> ACTION_CLICK (toggle ON) attempt ${attempt + 1}")
            clickRow(row)
        } else {
            Log.w(TAG, "wifi row not found for ON (attempt ${attempt + 1})")
        }
        main.postDelayed({ stepToggleOn(attempt + 1, ep) }, ON_VERIFY_MS)
    }

    /** 後始末（正常経路・安全網の両方から呼ばれる。二重実行ガードあり）。 */
    private fun finish() {
        if (!running) return // 二重実行ガード
        running = false
        runCatching { Config.setAirplaneCycleUntilMs(applicationContext, 0L) } // 復帰猶予を解除
        // no-sleep 再アサート＋スケジュール状態へ復帰（ON時間=サイネージ / OFF時間=黒オーバーレイ。共に KEEP_SCREEN_ON で
        // 端末を起こし続ける＝非DO寝落ち対策。夜間に画面を点けっぱなしにしない）。
        runCatching { KeepAwakeManager.applyNoSleepSettings(applicationContext) }
        // Settings 画面から確実に離脱する。a11y の HOME はバックグラウンド startActivity と違い
        // SYSTEM_ALERT_WINDOW 不要なので、下の applyCurrentState の前面化が throttle/拒否されても
        // 「設定画面に張り付いたまま＝サイネージに戻らない」を防ぐ belt-and-suspenders（DOロックタスク中は
        // HOME は抑止され no-op＝害なし。許可リスト復元後にキオスクが再ピンする）。
        runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }
        runCatching { ScheduleManager.applyCurrentState(applicationContext) }
        // ロックタスク許可リストをキオスクのみへ戻す（DO機。Settings を許可したままの脱出窓を閉じる。非DOは no-op）。
        runCatching { PowerController.allowLockTaskSelf(applicationContext) }
        // ブライト wakelock の明示 release は**遅延**させる。applyCurrentState の startActivity（黒オーバーレイ /
        // サイネージ）は非同期で、前面化して FLAG_KEEP_SCREEN_ON を握り直すまで数百ms〜数秒かかりうる。
        // ここで同期 release するとその隙にスリープ抑止がどちらも無い瞬間が生まれ、非DO機が寝落ちしうる
        // （04:59 事故の隙）。前面ウィンドウが keep-screen-on を握り直すまで待ってから手放す。wakelock 自身の
        // タイムアウト(MAX_SEQUENCE_MS+5s)が最終バックストップ。次の復帰が握り直した wakelock を誤解放しない
        // よう、対象インスタンスを捕捉してからフィールドを null にする。
        val wlToRelease = wakeLock
        wakeLock = null
        main.postDelayed({
            runCatching { wlToRelease?.let { if (it.isHeld) it.release() } }
        }, RELEASE_LOCK_DELAY_MS)
        Log.w(TAG, "wifi recovery DONE")
    }

    /** Wi-Fi の**実状態**（UI スイッチでなく WifiManager の実体）。読めなければ ON 扱い（無駄なトグルを避ける）。 */
    private fun isWifiOn(): Boolean = runCatching {
        (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
    }.getOrDefault(true)

    /**
     * "Wi-Fi" preference 行の clickable 祖先を返す。タイトルは正規化して英字だけにし "wifi" 完全一致で判定
     * （"Wi-Fi"/"Wi‑Fi"(非改行ハイフン)/"WiFi" の表記ゆれを吸収）。詳細ページ等で見つからなければ null。
     * **危険なフォールバック（最上段スイッチ＝"Scanning always available" 等の誤爆）はしない**。
     */
    private fun findWifiToggleRow(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val candidates = runCatching { root.findAccessibilityNodeInfosByText("Wi") }.getOrNull().orEmpty()
        for (t in candidates) {
            if (t.viewIdResourceName == "android:id/title" && looksLikeWifi(t.text?.toString())) {
                clickableAncestor(t)?.let { return it }
            }
        }
        return null
    }

    /** "Wi-Fi" 等の表記ゆれを正規化（英字のみ・小文字）して "wifi" と一致するか。 */
    private fun looksLikeWifi(s: String?): Boolean {
        if (s == null) return false
        return s.lowercase().replace(Regex("[^a-z]"), "") == "wifi"
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var n = node
        var hops = 0
        while (n != null && hops < 8) {
            if (n.isClickable) return n
            n = n.parent
            hops++
        }
        return null
    }

    /** clickable 行をクリック。ダメなら focus してから再クリック（leanback 対策）。 */
    private fun clickRow(row: AccessibilityNodeInfo) {
        if (!row.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.w(TAG, "ACTION_CLICK=false -> focus then click")
            row.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            row.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    /** 復帰中の端末/画面スリープ抑止（タイムアウト付き＝取りこぼしても自動解放）。 */
    @Suppress("DEPRECATION")
    private fun acquireScreenLock() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "TVBleBridge::wifiRecovery",
            )
            wl.setReferenceCounted(false)
            wl.acquire(MAX_SEQUENCE_MS + 5_000L)
            wakeLock = wl
            Log.i(TAG, "recovery screen wakelock acquired")
        }.onFailure { Log.w(TAG, "acquireScreenLock failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "WifiRecoveryA11y"
        private const val OPEN_SETTLE_MS = 2_500L // 設定画面の描画待ち
        private const val NAV_SETTLE_MS = 1_200L // BACK / 画面遷移の描画待ち
        private const val HOLD_MS = 8_000L // OFF→ON 保持（フレッシュ取り直し）
        private const val AFTER_ON_MS = 4_000L // ON 確定後の再接続待ち→復帰
        private const val ON_VERIFY_MS = 4_000L // ON クリック後の反映待ち（再 verify まで）
        private const val MAX_BACK_ATTEMPTS = 2
        private const val MAX_ON_ATTEMPTS = 3 // 必ず ON で終えるための再試行上限
        private const val MAX_SEQUENCE_MS = 45_000L // 安全網（state-aware で伸びうるので余裕）
        private const val RELEASE_LOCK_DELAY_MS = 3_000L // finish 後、前面の keep-screen-on 確立を待ってから wakelock を手放す

        @Volatile
        private var instance: WifiRecoveryAccessibilityService? = null

        /**
         * 外部（PlugCommandReceiver / Watchdog / 5amアラーム）から Wi-Fi 復帰を起動。
         * サービス未有効なら no-op（ログのみ）＝有効化は adb で一度きり。
         * @return true=サービスに依頼できた / false=未有効
         */
        fun trigger(context: Context): Boolean {
            val svc = instance
            if (svc == null) {
                Log.w(TAG, "trigger: service not enabled/connected -> no-op")
                return false
            }
            svc.main.post { svc.startRecovery() }
            return true
        }

        fun isConnected(): Boolean = instance != null
    }
}
