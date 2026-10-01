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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.TimePicker
import java.util.Calendar
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

    // スケジュール UI
    private lateinit var scheduleSwitch: Switch
    private lateinit var onPicker: TimePicker
    private lateinit var offPicker: TimePicker
    private lateinit var dayCheckboxes: Array<CheckBox>
    private lateinit var saveScheduleButton: Button
    private lateinit var testBlackButton: Button
    private lateinit var scheduleNextText: TextView

    // サイネージ / リモート設定 UI
    private lateinit var signageUrlInput: EditText
    private lateinit var autoLaunchCheckbox: CheckBox
    private lateinit var launchSignageButton: Button
    private lateinit var saveSignageButton: Button
    private lateinit var configEndpointInput: EditText

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

        // スケジュール UI 取得
        scheduleSwitch = findViewById(R.id.switch_schedule)
        onPicker = findViewById(R.id.picker_on_time)
        offPicker = findViewById(R.id.picker_off_time)
        dayCheckboxes = arrayOf(
            findViewById(R.id.cb_sun),  // index 1 = SUNDAY
            findViewById(R.id.cb_mon),
            findViewById(R.id.cb_tue),
            findViewById(R.id.cb_wed),
            findViewById(R.id.cb_thu),
            findViewById(R.id.cb_fri),
            findViewById(R.id.cb_sat),
        )
        saveScheduleButton = findViewById(R.id.button_save_schedule)
        testBlackButton = findViewById(R.id.button_test_black)
        scheduleNextText = findViewById(R.id.text_schedule_next)

        onPicker.setIs24HourView(true)
        offPicker.setIs24HourView(true)

        loadScheduleIntoUI()

        saveScheduleButton.setOnClickListener {
            saveScheduleFromUI()
            ScheduleManager.rescheduleAll(this)
            refreshScheduleNext()
        }
        testBlackButton.setOnClickListener {
            startActivity(Intent(this, BlackScreenActivity::class.java))
        }

        // サイネージ / リモート設定 UI 取得
        signageUrlInput = findViewById(R.id.input_signage_url)
        autoLaunchCheckbox = findViewById(R.id.cb_autolaunch)
        launchSignageButton = findViewById(R.id.button_launch_signage)
        saveSignageButton = findViewById(R.id.button_save_signage)
        configEndpointInput = findViewById(R.id.input_config_endpoint)

        signageUrlInput.setText(Config.signageUrl(this))
        autoLaunchCheckbox.isChecked = Config.autoLaunchSignage(this)
        configEndpointInput.setText(Config.configEndpoint(this))

        signageUrlInput.addTextChangedListener(simpleWatcher {
            Config.setSignageUrl(this, it)
            Config.extractAndSaveClassroomContext(this, it)
        })
        configEndpointInput.addTextChangedListener(simpleWatcher { Config.setConfigEndpoint(this, it) })
        autoLaunchCheckbox.setOnCheckedChangeListener { _, isChecked ->
            Config.setAutoLaunchSignage(this, isChecked)
        }

        launchSignageButton.setOnClickListener {
            val url = signageUrlInput.text.toString().trim()
            if (url.isBlank()) {
                statusText.text = "サイネージURLを入力してください"
                return@setOnClickListener
            }
            Config.setSignageUrl(this, url)
            startActivity(Intent(this, SignageActivity::class.java))
        }
        saveSignageButton.setOnClickListener {
            val url = signageUrlInput.text.toString().trim()
            Config.setSignageUrl(this, url)
            Config.extractAndSaveClassroomContext(this, url)
            Config.setConfigEndpoint(this, configEndpointInput.text.toString().trim())
            Config.setAutoLaunchSignage(this, autoLaunchCheckbox.isChecked)
        }

        // 起動時、必要権限が揃っていれば自動でサービス開始
        // ConfigPoller（設定/死活/スケジュール）は webhook 無しでも必要なので webhook 条件は外す
        if (hasAllPermissions()) {
            startBleService()
        }

        // 起動時にスケジュール再予約
        ScheduleManager.rescheduleAll(this)
        refreshScheduleNext()
    }

    private fun loadScheduleIntoUI() {
        val cfg = ScheduleConfig.load(this)
        scheduleSwitch.isChecked = cfg.enabled
        onPicker.hour = cfg.onHour
        onPicker.minute = cfg.onMinute
        offPicker.hour = cfg.offHour
        offPicker.minute = cfg.offMinute
        // index 0=SUN(Calendar.SUNDAY=1), 1=MON(2), ... 6=SAT(7)
        for (i in 0..6) {
            val calDay = i + 1  // Calendar.SUNDAY..SATURDAY = 1..7
            dayCheckboxes[i].isChecked = cfg.isDayActive(calDay)
        }
    }

    private fun saveScheduleFromUI() {
        var mask = 0
        for (i in 0..6) {
            val calDay = i + 1
            if (dayCheckboxes[i].isChecked) mask = mask or (1 shl calDay)
        }
        val cfg = ScheduleConfig(
            enabled = scheduleSwitch.isChecked,
            onHour = onPicker.hour,
            onMinute = onPicker.minute,
            offHour = offPicker.hour,
            offMinute = offPicker.minute,
            daysMask = mask,
        )
        ScheduleConfig.save(this, cfg)
    }

    private fun refreshScheduleNext() {
        val cfg = ScheduleConfig.load(this)
        if (!cfg.enabled) {
            scheduleNextText.text = "スケジュール: 無効"
            return
        }
        val now = Calendar.getInstance()
        val nextOn = nextOccurrenceForUi(now, cfg, cfg.onHour, cfg.onMinute)
        val nextOff = nextOccurrenceForUi(now, cfg, cfg.offHour, cfg.offMinute)
        val fmt = java.text.SimpleDateFormat("MM/dd(E) HH:mm", java.util.Locale.JAPAN)
        scheduleNextText.text = "次回 ON: ${fmt.format(nextOn.time)}   次回 OFF: ${fmt.format(nextOff.time)}"
    }

    private fun nextOccurrenceForUi(now: Calendar, cfg: ScheduleConfig, hour: Int, minute: Int): Calendar {
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        for (i in 0..7) {
            val candidate = (target.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, i) }
            if (i == 0 && !candidate.after(now)) continue
            if (cfg.isDayActive(candidate.get(Calendar.DAY_OF_WEEK))) return candidate
        }
        return (target.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(BleService.ACTION_STATUS_UPDATED)
        // 送信元は自アプリ（BleService）のみ。API 33 未満でも ContextCompat が署名権限付きで非公開登録する
        // （他アプリからの偽ブロードキャストを受けない）。adb の am broadcast に依存する runbook は無い。
        ContextCompat.registerReceiver(this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
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
        } else {
            // Android 11 以下: BLE スキャンに位置情報権限が必須
            list.add(Manifest.permission.ACCESS_FINE_LOCATION)
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
