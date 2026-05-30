package com.kimiterrace.tvbridge

import android.bluetooth.le.ScanResult
import android.os.ParcelUuid
import java.util.UUID

/**
 * SwitchBot 人感センサ (WoPresence, PIR 方式) の BLE Advertisement パーサ。
 *
 * pyswitchbot の adv_parsers/motion.py と仕様互換。
 *
 * Service Data UUID: 0000FD3D-0000-1000-8000-00805F9B34FB
 *   byte 0: 'M' (0x73) device type identifier
 *   byte 1:
 *     bit 7 = tested
 *     bit 6 = motion_detected
 *   byte 2:
 *     bit 0-6 = battery %
 *   byte 5:
 *     bit 1 = is_light
 *     others = led / iot / sense_distance / light_intensity
 *
 * Manufacturer Data Company ID 0x0969 (SwitchBot):
 *   byte 0-5: device MAC
 *   byte 7:
 *     bit 6 = motion_detected
 *     bit 5 = is_light
 */
object SwitchBotParser {

    val FD3D_PARCEL_UUID: ParcelUuid = ParcelUuid(
        UUID.fromString("0000fd3d-0000-1000-8000-00805f9b34fb")
    )
    const val SWITCHBOT_COMPANY_ID: Int = 0x0969

    data class ParsedAdvertisement(
        val motionDetected: Boolean?,
        val isLight: Boolean?,
        val batteryPercent: Int?,
        val rssi: Int,
        val deviceMac: String,
    )

    fun parse(scanResult: ScanResult): ParsedAdvertisement? {
        val mac = scanResult.device.address.uppercase()
        val record = scanResult.scanRecord ?: return null

        var motionDetected: Boolean? = null
        var isLight: Boolean? = null
        var batteryPercent: Int? = null

        // Service Data (FD3D)
        val serviceData = record.getServiceData(FD3D_PARCEL_UUID)
        if (serviceData != null && serviceData.size >= 3) {
            // byte 1 の bit 6 = motion_detected
            motionDetected = (serviceData[1].toInt() and 0b01000000) != 0
            // byte 2 の bits 0-6 = battery %
            batteryPercent = serviceData[2].toInt() and 0b01111111
            if (serviceData.size >= 6) {
                // byte 5 の bit 1 = is_light
                isLight = (serviceData[5].toInt() and 0b00000010) != 0
            }
        }

        // Manufacturer Data (SwitchBot company)
        val mfrData = record.getManufacturerSpecificData(SWITCHBOT_COMPANY_ID)
        if (mfrData != null && mfrData.size >= 8) {
            // byte 7 の bit 6 = motion_detected (上書き)
            motionDetected = (mfrData[7].toInt() and 0b01000000) != 0
            // byte 7 の bit 5 = is_light
            isLight = (mfrData[7].toInt() and 0b00100000) != 0
        }

        if (motionDetected == null) return null

        return ParsedAdvertisement(
            motionDetected = motionDetected,
            isLight = isLight,
            batteryPercent = batteryPercent,
            rssi = scanResult.rssi,
            deviceMac = mac,
        )
    }
}
