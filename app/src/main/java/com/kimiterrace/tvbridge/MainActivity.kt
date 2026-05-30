package com.kimiterrace.tvbridge

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Google TV の TV ランチャーから開ける Activity。
 * リモコン操作前提の最小 UI:
 *  - 設定（MAC, Webhook URL）
 *  - ステータス（最終検知、累計件数、未送信件数、同期状態）
 *  - 開始 / 停止ボタン
 */
class MainActivity : AppCompatActivity() {

    private lateinit var macInput: EditText
    private lateinit var webhookInput: EditText
    private lateinit var statusText: TextView
    private lateinit var lastEventText: TextView
    private lateinit var statsText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            refreshHandler.postDelayed(this, 2000L)
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        macInput = findViewById(R.id.input_mac)
        webhookInput = findViewById(R.id.input_webhook)
        statusText = findViewById(R.id.text_status)
        lastEventText = findViewById(R.id.text_last_event)
        statsText = findViewById(R.id.text_stats)
        startButton = findViewById(R.id.button_start)
        stopButton = findViewById(R.id.button_stop)

        macInput.setText(Config.targetMac(this))
        webhookInput.setText(Config.webhookUrl(this))

        macInput.addTextChangedListener(simpleWatcher { Config.setTargetMac(this, it) })
        webhookInput.addTextChangedListener(simpleWatcher { Config.setWebhookUrl(this, it) })

        startButton.setOnClickListener { ensurePermissionsAndStart() }
        stopButton.setOnClickListener { stopService(Intent(this, BleService::class.java)) }

        // 起動時、必要権限が揃っていれば自動でサービス開始
        if (hasAllPermissions() && Config.webhookUrl(this).isNotBlank()) {
            startBleService()
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(BleService.ACTION_STATUS_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
        refreshHandler.post(refreshRunnable)
    }

    override fun onPause() {
        try { unregisterReceiver(statusReceiver) } catch (_: Throwable) {}
        refreshHandler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun ensurePermissionsAndStart() {
        val needed = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERMISSIONS)
            return
        }
        startBleService()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS && hasAllPermissions()) {
            startBleService()
        }
    }

    private fun hasAllPermissions(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requiredPermissions(): List<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list
    }

    private fun startBleService() {
        val svc = Intent(this, BleService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }
    }

    private fun refreshStatus() {
        // Service とは Binder 経由ではなく、Service プロセス内 static で直接参照する簡易方式。
        // PoC 用なので堅牢性は妥協。Production では BoundService 化を検討。
        val total: Int
        val pending: Int
        var statusStr = "(service not running)"
        var lastEv = "-"
        var syncedAt = 0L
        var syncOk = false
        if (com.kimiterrace.tvbridge.BleServiceHandle.current != null) {
            val snap = com.kimiterrace.tvbridge.BleServiceHandle.current!!.snapshotStatus()
            statusStr = snap.stateText
            lastEv = snap.lastEventText
            total = snap.totalCount
            pending = snap.pendingCount
            syncedAt = snap.lastSyncTimeMs
            syncOk = snap.lastSyncOk
        } else {
            total = 0
            pending = 0
        }
        statusText.text = "状態: $statusStr"
        lastEventText.text = "最終検知: $lastEv"
        val syncStr = if (syncedAt > 0) {
            val t = SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(Date(syncedAt))
            if (syncOk) "$t (OK)" else "$t (失敗→蓄積中)"
        } else "未試行"
        statsText.text = "累計: ${total}件   未送信: ${pending}件   最終同期: $syncStr"
    }

    private fun simpleWatcher(onChange: (String) -> Unit): TextWatcher {
        return object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
    }

    companion object {
        private const val REQ_PERMISSIONS = 100
    }
}
