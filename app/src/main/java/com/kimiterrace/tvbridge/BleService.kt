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
import android.content.Intent
import android.content.pm.ServiceInfo
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

        // Foreground 開始（5秒以内に startForeground しないと ANR）
        startForeground(NOTIFICATION_ID, buildNotification("starting up"), foregroundServiceType())

        // 設定取得
        targetMac = Config.targetMac(this)
        lastMotion = Config.lastMotion(this)
        val webhookUrl = Config.webhookUrl(this)

        // BLE アダプタ
        val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter

        // Wake lock（CPU を起こし続けるだけ。画面 ON 維持は別途 KeepAwakeManager が担う）
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TVBleBridge::scan").apply {
            setReferenceCounted(false)
            acquire()
        }

        // 画面オフ・スリープ・スクリーンセーバを無効化（権限があれば。再起動 revert 対策で常駐中も再適用）
        KeepAwakeManager.applyNoSleepSettings(applicationContext)
        // Device Owner なら lock task（キオスク）許可リストへ自分を登録しておく
        //（SignageActivity.startLockTask の前提。Device Owner でなければ no-op）
        PowerController.allowLockTaskSelf(applicationContext)
        startKeepAwakeLoop()

        // Uploader
        uploader = Uploader(
            context = applicationContext,
            webhookUrl = webhookUrl,
            scope = scope,
        )

        // ConfigPoller — リモート設定を定期取得
        ConfigPoller(
            context = applicationContext,
            scope = scope,
        )

        Log.i(TAG, "BleService onCreate: target=$targetMac webhook=${webhookUrl.take(60)}...")

        startScan()
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.w(TAG, "BleService onDestroy")
        try { bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Throwable) {}
        wakeLock?.let { if (it.isHeld) it.release() }
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

        val filter = ScanFilter.Builder()
            .setDeviceAddress(targetMac)
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        try {
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
        return StatusSnapshot(
            targetMac = targetMac,
            stateText = stateText,
            lastEventText = lastEventText,
            totalCount = uploader.totalCount(),
            pendingCount = uploader.pendingCount,
            lastSyncOk = uploader.lastSyncOk,
            lastSyncTimeMs = uploader.lastSyncTimeMs,
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
        private const val TAG = "BleService"
        private const val CHANNEL_ID = "tv_ble_bridge"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STATUS_UPDATED = "com.kimiterrace.tvbridge.STATUS_UPDATED"

        private const val KEEP_AWAKE_INTERVAL_MS = 60_000L      // 前面チェック間隔（1分）
        private const val SETTINGS_REAPPLY_EVERY_TICKS = 15     // no-sleep 設定の再適用間隔（≒15分）
    }
}
