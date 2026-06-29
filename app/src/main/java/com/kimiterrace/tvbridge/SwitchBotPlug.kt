package com.kimiterrace.tvbridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * SwitchBot Plug Mini を BLE GATT で on/off/cycle/toggle する「電源制御ゲートウェイ」。
 *
 * BleService の BLE スキャン（センサ広告の受動受信）とは別経路で、本クラスは plug への **能動 GATT 接続＋
 * コマンド書込み** を行う。当面のトリガは adb（[PlugCommandReceiver]）、将来は v2 の遠隔コマンド
 * （ConfigPoller.applyCommands の plug_*）。
 *
 * ## スキャン→接続（生 MAC 直 connect の status=133 回避）
 * Android BLE は「事前スキャンなしで生 MAC へ直接 connectGatt」すると多くの機種で **status=133 即失敗**する
 * （アドレスがコントローラ未知 / アドレス型不定）。よって **まず MAC 指定でスキャンして実機を捕捉**し、
 * 得られた {@link ScanResult#getDevice} へ connect する。スキャンで見つからなければ「未広告 / 圏外 / MAC 不一致」と
 * 切り分けられる（OP_TIMEOUT で打ち切り、ログに明示）。
 *
 * プロトコル出典: OpenWonderLabs/SwitchBotAPI-BLE（Plug Mini）。
 *  - service    cba20d00-224d-11e6-9fb8-0002a5d5c51b
 *  - write(RX)  cba20002-224d-11e6-9fb8-0002a5d5c51b
 *  - ON  = 57 0F 50 01 01 80 / OFF = 57 0F 50 01 01 00 / TOGGLE = 57 0F 50 01 02 80
 *  ※ BLE API に「スケジュール設定」コマンドは無い。プラグの定時スケジュールは SwitchBot アプリで
 *    ローカル設定する（こちらは on/off/cycle の遠隔制御＝モニタ生存時のフリート管理が対象）。
 *
 * ⚠ **自端末の電源プラグを off/cycle してはならない**（自分が落ちて on を送れず自滅する）。原則は
 *   「他端末／管理用プラグ」に対して使う。down 中の自己復旧はプラグのローカル定時スケジュールが正。
 */
object SwitchBotPlug {
    private const val TAG = "SwitchBotPlug"
    private val SERVICE_UUID = UUID.fromString("cba20d00-224d-11e6-9fb8-0002a5d5c51b")
    private val WRITE_UUID = UUID.fromString("cba20002-224d-11e6-9fb8-0002a5d5c51b")

    private val CMD_ON = byteArrayOf(0x57, 0x0f, 0x50, 0x01, 0x01, 0x80.toByte())
    private val CMD_OFF = byteArrayOf(0x57, 0x0f, 0x50, 0x01, 0x01, 0x00)
    private val CMD_TOGGLE = byteArrayOf(0x57, 0x0f, 0x50, 0x01, 0x02, 0x80.toByte())

    private const val OP_TIMEOUT_MS = 20_000L // スキャン＋接続＋書込みの全体打ち切り
    private const val CYCLE_GAP_MS = 5_000L // OFF→ON の間隔（電断とみなされる十分な時間）

    /**
     * プラグ制御を実行（非同期・fire-and-forget）。mac 不正 / BT 無効 / 未広告 / 接続失敗は安全に no-op（ログのみ）。
     * @param action "on" | "off" | "toggle" | "cycle"
     */
    @SuppressLint("MissingPermission")
    fun control(context: Context, mac: String, action: String) {
        if (!BluetoothAdapter.checkBluetoothAddress(mac)) {
            Log.w(TAG, "invalid plug mac '$mac' -> skip"); return
        }
        val commands: List<ByteArray> = when (action.trim().lowercase()) {
            "on" -> listOf(CMD_ON)
            "off" -> listOf(CMD_OFF)
            "toggle" -> listOf(CMD_TOGGLE)
            "cycle" -> listOf(CMD_OFF, CMD_ON)
            else -> { Log.w(TAG, "unknown plug action '$action' -> skip"); return }
        }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) { Log.w(TAG, "bluetooth unavailable -> skip"); return }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) { Log.w(TAG, "no BLE scanner -> skip"); return }

        val main = Handler(Looper.getMainLooper())
        val closed = booleanArrayOf(false)
        var gattRef: BluetoothGatt? = null
        var scanCb: ScanCallback? = null
        var idx = 0

        fun cleanup() {
            if (closed[0]) return
            closed[0] = true
            main.removeCallbacksAndMessages(null)
            runCatching { scanCb?.let { scanner.stopScan(it) } }
            runCatching { gattRef?.disconnect() }
            runCatching { gattRef?.close() }
        }

        val gattCb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Log.i(TAG, "onConnectionStateChange status=$status newState=$newState")
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    runCatching { gatt.discoverServices() }.onFailure { cleanup() }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.w(TAG, "disconnected (status=$status)")
                    cleanup()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                Log.i(TAG, "onServicesDiscovered status=$status")
                if (status != BluetoothGatt.GATT_SUCCESS) { cleanup(); return }
                writeAt(gatt)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
                status: Int,
            ) {
                Log.i(TAG, "onCharacteristicWrite status=$status (idx=$idx)")
                idx += 1
                if (idx < commands.size) {
                    main.postDelayed({ writeAt(gatt) }, CYCLE_GAP_MS)
                } else {
                    Log.i(TAG, "plug command(s) sent: $action")
                    cleanup()
                }
            }

            @Suppress("DEPRECATION")
            private fun writeAt(gatt: BluetoothGatt) {
                val ch = gatt.getService(SERVICE_UUID)?.getCharacteristic(WRITE_UUID)
                if (ch == null) { Log.w(TAG, "write characteristic not found"); cleanup(); return }
                val data = commands[idx]
                val ok = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(ch, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                            android.bluetooth.BluetoothStatusCodes.SUCCESS
                    } else {
                        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        ch.value = data
                        gatt.writeCharacteristic(ch)
                    }
                }.getOrDefault(false)
                if (!ok) { Log.w(TAG, "writeCharacteristic failed"); cleanup() }
            }
        }

        scanCb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (closed[0] || gattRef != null) return
                Log.i(TAG, "plug found in scan (rssi=${result.rssi}) -> connecting")
                runCatching { scanner.stopScan(this) }
                gattRef = runCatching {
                    result.device.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE)
                }.getOrNull()
                if (gattRef == null) { Log.w(TAG, "connectGatt failed -> skip"); cleanup() }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "scan failed: $errorCode"); cleanup()
            }
        }

        val filter = ScanFilter.Builder().setDeviceAddress(mac).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        Log.i(TAG, "plug '$action' -> scanning for $mac")
        val started = runCatching { scanner.startScan(listOf(filter), settings, scanCb); true }.getOrDefault(false)
        if (!started) { Log.w(TAG, "startScan failed -> skip"); return }
        main.postDelayed({
            if (gattRef == null) {
                Log.w(TAG, "plug NOT found in scan (未広告 / 圏外 / MAC 不一致 / ペアリングで別アドレス広告の可能性)")
            } else {
                Log.w(TAG, "plug gatt timeout")
            }
            cleanup()
        }, OP_TIMEOUT_MS)
    }
}
