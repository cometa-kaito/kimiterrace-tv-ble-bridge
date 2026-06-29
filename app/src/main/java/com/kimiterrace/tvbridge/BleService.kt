package com.kimiterrace.tvbridge

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * BLE スキャン常駐 Foreground Service。
 *
 * - 端末起動／アプリ起動から動作開始
 * - 対象 MAC の Advertisement を受信し、state 遷移時に Uploader へ渡す
 * - スリープ・低メモリ時もできるだけ維持されるよう PARTIAL_WAKE_LOCK 取得
 */
class BleService : Service() {

    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob)

    private lateinit var uploader: Uploader
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // ネット復帰トリガ（県 WiFi 断→再接続時の自己回復）用
    private var configPoller: ConfigPoller? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastNetRecoveryMs: Long = 0L

    @Volatile private var targetMac: String = ""
    @Volatile private var lastMotion: Boolean? = null

    @Volatile var stateText: String = "starting"
        private set

    @Volatile var lastEventText: String = "(none yet)"
        private set

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (r in results) handleScanResult(r)
        }

        override fun onScanFailed(errorCode: Int) {
            stateText = "scan_failed:$errorCode"
            Log.w(TAG, "scan failed: $errorCode")
            broadcastStatusUpdate()
        }
    }

    override fun onCreate() {
        super.onCreate()
        BleServiceHandle.current = this

        // Foreground 開始（5秒以内に startForeground しないと ANR）。失敗しても以降の生命線は止めない。
        runCatching {
            startForeground(NOTIFICATION_ID, buildNotification("starting up"), foregroundServiceType())
        }.onFailure { first ->
            // FGS 型(connectedDevice)に必要な前提(権限/起動文脈)が欠けて失敗した場合、型なしで再試行する。
            // startForegroundService で開始された以上、5 秒以内に startForeground を成立させないと OS に
            // kill され ConfigPoller も道連れになるため、型を落としてでも foreground 化を成立させる。
            Log.e(TAG, "startForeground(typed) failed, retrying without type", first)
            runCatching { startForeground(NOTIFICATION_ID, buildNotification("starting up")) }
                .onFailure { Log.e(TAG, "startForeground(plain) failed", it) }
        }

        // ★生命線を最優先で起動：設定/死活ポーリング（ConfigPoller）。これが回れば接続🟢と遠隔復帰が成立する。
        //  以降の周辺初期化(BLE/wakelock/keep-awake/uploader)が何で失敗しても、poller だけは必ず立ち上げる。
        //  旧実装は onCreate 内の周辺初期化が 1 つでも例外を投げると、poller 起動前にサービスごとクラッシュ→
        //  START_STICKY で再生成→再クラッシュの無限ループに陥り、端末が永久に無音化していた（同一環境でも
        //  prefs 差等で一部端末だけ死ぬ「不安定さ」の主因）。順序と例外隔離でこれを根治する。
        runCatching {
            if (configPoller == null) {
                configPoller = ConfigPoller(context = applicationContext, scope = scope)
            }
        }.onFailure { Log.e(TAG, "ConfigPoller start failed", it) }

        // ここから下は周辺機能。各々を独立に try で囲み、1 つの失敗が他や常駐を巻き込まないようにする。
        runCatching { targetMac = Config.targetMac(this) }
            .onFailure { Log.w(TAG, "targetMac read failed: ${it.message}") }
        runCatching { lastMotion = Config.lastMotion(this) }
            .onFailure { Log.w(TAG, "lastMotion read failed: ${it.message}") }
        runCatching {
            uploader = Uploader(
                context = applicationContext,
                webhookUrl = Config.webhookUrl(this),
                scope = scope,
            )
        }.onFailure { Log.w(TAG, "uploader init failed: ${it.message}") }
        runCatching {
            val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothAdapter = btManager.adapter
        }.onFailure { Log.w(TAG, "bluetooth adapter init failed: ${it.message}") }
        runCatching {
            // Wake lock（CPU を起こし続けるだけ。画面 ON 維持は別途 KeepAwakeManager が担う）
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TVBleBridge::scan").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.w(TAG, "wakelock init failed: ${it.message}") }
        // Wi-Fi lock（県 WiFi の夜間 省電力スリープ/disassociation を抑止＝接続セッションを腐らせない予防）。
        // 「接続済みなのに v2 不達(=L3 セッション死)」の主トリガである Wi-Fi 省電力断を防ぐ、非 DO の本命。
        runCatching {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TVBleBridge::wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "wifi lock acquired (FULL_HIGH_PERF)")
        }.onFailure { Log.w(TAG, "wifi lock init failed: ${it.message}") }
        // 画面オフ・スリープ・スクリーンセーバを無効化（権限があれば。再起動 revert 対策で常駐中も再適用）
        runCatching { KeepAwakeManager.applyNoSleepSettings(applicationContext) }
            .onFailure { Log.w(TAG, "applyNoSleepSettings failed: ${it.message}") }
        // Device Owner なら lock task（キオスク）許可リストへ自分を登録（非 Device Owner では no-op）
        runCatching { PowerController.allowLockTaskSelf(applicationContext) }
            .onFailure { Log.w(TAG, "allowLockTaskSelf failed: ${it.message}") }
        runCatching { startKeepAwakeLoop() }
            .onFailure { Log.w(TAG, "keep-awake loop start failed: ${it.message}") }
        // ネットワーク復帰を「復帰トリガ」として購読（県 WiFi 断→再接続時の自己回復）
        runCatching { registerNetworkCallback() }
            .onFailure { Log.w(TAG, "registerNetworkCallback failed: ${it.message}") }
        // FCM トークンを取得→保存（ConfigPoller が次回ポーリングで v2 へ報告＝遠隔起動プッシュの宛先）。
        runCatching {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { Config.setFcmToken(applicationContext, it) }
        }.onFailure { Log.d(TAG, "fcm token fetch skipped: ${it.message}") }
        // BLE スキャン開始（無効 MAC は startScan 内部で skip。例外も内部で握る）
        runCatching { startScan() }
            .onFailure { Log.w(TAG, "startScan failed: ${it.message}") }

        Log.i(TAG, "BleService onCreate done (poller=${configPoller != null}, target=$targetMac)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY  // 殺されたら復活
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // ランチャー等からタスクをスワイプ除去されても常駐を維持するため自分を再起動する
        try {
            val restart = Intent(applicationContext, BleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(restart)
            } else {
                applicationContext.startService(restart)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "onTaskRemoved restart failed: ${e.message}")
        }
        super.onTaskRemoved(rootIntent)
    }

    /**
     * 常駐キープアライブ。60 秒ごとに:
     *  - ON 時間帯にサイネージが前面から外れていれば前面へ戻す（FLAG_KEEP_SCREEN_ON 再付与）
     *  - 一定間隔で no-sleep 設定を再適用（実行中に設定が戻された場合の保険）
     */
    private fun startKeepAwakeLoop() {
        scope.launch(Dispatchers.Default) {
            var ticks = 0
            while (true) {
                delay(KEEP_AWAKE_INTERVAL_MS)
                try {
                    KeepAwakeManager.reassertForegroundIfNeeded(applicationContext)
                    if (++ticks % SETTINGS_REAPPLY_EVERY_TICKS == 0) {
                        KeepAwakeManager.applyNoSleepSettings(applicationContext)
                    }
                } catch (e: Throwable) {
                    Log.d(TAG, "keep-awake tick skipped: ${e.message}")
                }
            }
        }
    }

    /**
     * ネットワーク復帰を「復帰トリガ」として購読する（ON/OFF スイッチにはしない）。
     * 県 WiFi が深夜に切れて再接続した瞬間に自己回復させるのが狙い。
     * 表示の ON/OFF はあくまで時刻スケジュールが正本で、ネット状態では切り替えない。
     */
    private fun registerNetworkCallback() {
        runCatching {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    onNetworkRecovered()
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            connectivityManager = cm
            networkCallback = cb
            Log.i(TAG, "network callback registered (recovery trigger)")
        }.onFailure { Log.w(TAG, "registerNetworkCallback failed: ${it.message}") }
    }

    /**
     * ネット復帰時の自己回復:
     *  - no-sleep 設定を再適用（夜間に劣化した分の保険）
     *  - 前面状態を再アサート（ON 時間帯はサイネージ前面／OFF 時間帯は黒を維持）
     *  - 設定/コマンドを即時ポーリング（長時間断後の素早い再同期）
     * onAvailable はフラッピングで多発し得るのでスロットルを掛ける。
     */
    private fun onNetworkRecovered() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastNetRecoveryMs < NET_RECOVERY_THROTTLE_MS) return
        lastNetRecoveryMs = now
        Log.i(TAG, "network recovered -> re-assert no-sleep + foreground + immediate poll")
        runCatching { KeepAwakeManager.applyNoSleepSettings(applicationContext) }
        runCatching { KeepAwakeManager.reassertForegroundIfNeeded(applicationContext) }
        runCatching { configPoller?.pollNow() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.w(TAG, "BleService onDestroy")
        try { bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Throwable) {}
        runCatching { networkCallback?.let { cb -> connectivityManager?.unregisterNetworkCallback(cb) } }
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        supervisorJob.cancel()
        if (BleServiceHandle.current === this) BleServiceHandle.current = null
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            stateText = "ble_unavailable"
            updateNotification("BLE 利用不可")
            return
        }

        // target_mac が有効な MAC 形式でなければ BLE スキャンを行わない。サイネージ専用機（センサ未設置）や、
        // 旧 lp-config 由来の汚染値 target_mac="NULL"（JSON null → optString が "null"→"NULL" 化）では
        // setDeviceAddress が IllegalArgumentException を投げ、onCreate を貫通してサービスが起動時クラッシュ
        // ループに陥る。BLE はセンサ受信専用で、サイネージ表示と設定ポーリングには不要なため、無効時は安全に
        // skip して常駐（ConfigPoller/サイネージ）を維持する（fail-safe）。
        if (!BluetoothAdapter.checkBluetoothAddress(targetMac)) {
            stateText = "no_ble_target"
            Log.i(TAG, "startScan skipped: invalid target_mac='$targetMac' (signage + poll continue)")
            updateNotification("BLE 対象なし（サイネージ稼働中）")
            return
        }

        try {
            val filter = ScanFilter.Builder()
                .setDeviceAddress(targetMac)
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build()
            scanner.startScan(listOf(filter), settings, scanCallback)
            stateText = "scanning"
            updateNotification("BLE スキャン中: $targetMac")
        } catch (e: Throwable) {
            stateText = "start_failed:${e.javaClass.simpleName}"
            Log.e(TAG, "startScan failed", e)
            updateNotification("スキャン開始失敗: ${e.message}")
        }
    }

    private fun handleScanResult(result: ScanResult) {
        if (!::uploader.isInitialized) return  // uploader 初期化失敗時もイベントだけ捨てて常駐は維持
        val parsed = SwitchBotParser.parse(result) ?: return
        if (parsed.deviceMac != targetMac) return  // 念のため

        val motion = parsed.motionDetected ?: return
        if (motion == lastMotion) return  // 状態に変化なし

        val now = System.currentTimeMillis()
        val state = if (motion) "DETECTED" else "NOT_DETECTED"
        uploader.recordEvent(targetMac, state, now, parsed.batteryPercent)
        lastMotion = motion
        Config.setLastMotion(this, motion)

        lastEventText = "${formatTime(now)}  $state  battery=${parsed.batteryPercent}%  rssi=${parsed.rssi}dBm"
        Log.i(TAG, "EVENT $state battery=${parsed.batteryPercent}% rssi=${parsed.rssi}")
        updateNotification(lastEventText)
        broadcastStatusUpdate()
    }

    fun snapshotStatus(): StatusSnapshot {
        // uploader 初期化失敗時でもクラッシュさせない（診断UIを開いた瞬間に未初期化例外→自動再起動
        // ループに入るのを防ぐ。生命線=ConfigPoller とは無関係なので 0 値で返す）。
        val up = if (::uploader.isInitialized) uploader else null
        return StatusSnapshot(
            targetMac = targetMac,
            stateText = stateText,
            lastEventText = lastEventText,
            totalCount = up?.totalCount() ?: 0,
            pendingCount = up?.pendingCount ?: 0,
            lastSyncOk = up?.lastSyncOk ?: false,
            lastSyncTimeMs = up?.lastSyncTimeMs ?: 0L,
        )
    }

    private fun broadcastStatusUpdate() {
        // ローカルブロードキャストで MainActivity に更新通知
        sendBroadcast(Intent(ACTION_STATUS_UPDATED).setPackage(packageName))
    }

    private fun buildNotification(text: String): Notification {
        ensureNotificationChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("キミテラス TV ブリッジ")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "TV BLE Bridge",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "BLE センサ受信常駐サービス" }
                mgr.createNotificationChannel(ch)
            }
        }
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else 0

    private fun formatTime(ms: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return "%02d:%02d:%02d".format(
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND),
        )
    }

    data class StatusSnapshot(
        val targetMac: String,
        val stateText: String,
        val lastEventText: String,
        val totalCount: Int,
        val pendingCount: Int,
        val lastSyncOk: Boolean,
        val lastSyncTimeMs: Long,
    )

    companion object {
        /**
         * BleService(ConfigPoller=死活/設定ポーリング + ScheduleManager のホスト)を起動し直す。
         * 冪等: 生きていれば onStartCommand(START_STICKY) が再呼び出しされるだけ、死んでいれば復活する。
         * 自己回復の単一入口（ScheduleAlarmReceiver / BootReceiver / SignageActivity の JS ブリッジから呼ぶ）。
         */
        fun ensureRunning(context: Context) {
            try {
                val svc = Intent(context, BleService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svc)
                } else {
                    context.startService(svc)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "ensureRunning failed: ${e.message}")
            }
        }

        /**
         * 「プロセスは生存しているが poller が永久停止」した時の最終手段（Watchdog.tick から呼ぶ）。
         * 一旦サービスを停止し、数秒後の蘇生アラームで新インスタンスを起こす＝onCreate が再実行され
         * ConfigPoller が作り直される（ensureRunning は冪等で既存の死んだ poller を蘇生できないため）。
         * stopService→start のレースを避けるため、即時 ensureRunning ではなく restart アラームに委ねる。
         */
        fun forceRestart(context: Context) {
            runCatching { context.stopService(Intent(context, BleService::class.java)) }
            Watchdog.scheduleRestart(context, 2500L)
        }

        private const val TAG = "BleService"
        private const val CHANNEL_ID = "tv_ble_bridge"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STATUS_UPDATED = "com.kimiterrace.tvbridge.STATUS_UPDATED"

        private const val KEEP_AWAKE_INTERVAL_MS = 60_000L      // 前面チェック間隔（1分）
        private const val SETTINGS_REAPPLY_EVERY_TICKS = 15     // no-sleep 設定の再適用間隔（≒15分）
        private const val NET_RECOVERY_THROTTLE_MS = 20_000L    // ネット復帰トリガのフラッピング抑制（20秒）
    }
}
