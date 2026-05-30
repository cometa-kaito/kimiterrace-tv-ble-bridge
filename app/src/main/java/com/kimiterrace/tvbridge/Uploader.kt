package com.kimiterrace.tvbridge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Vercel /api/switchbot-webhook へ JSON POST する Uploader。
 *
 * ローカルファースト方針:
 *  - 状態遷移検知時、まず SQLite に永続化（絶対失敗しない）
 *  - その後、別コルーチンで HTTP POST 試行
 *  - 失敗時はローカルに残し、次の周期で再送
 *  - ネット完全断でも蓄積継続、復帰時に自動キャッチアップ
 */
class Uploader(
    private val context: Context,
    private val webhookUrl: String,
    private val syncIntervalMs: Long = 30_000L,
    scope: CoroutineScope,
) {
    private val db: SQLiteDatabase
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    @Volatile
    var lastSyncTimeMs: Long = 0
        private set

    @Volatile
    var lastSyncOk: Boolean = false
        private set

    @Volatile
    var pendingCount: Int = 0
        private set

    init {
        val helper = object : SQLiteOpenHelper(context, "sensor.db", null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE motion_events_local (
                        id              INTEGER PRIMARY KEY AUTOINCREMENT,
                        device_mac      TEXT    NOT NULL,
                        detection_state TEXT    NOT NULL,
                        detected_at_ms  INTEGER NOT NULL,
                        raw_payload     TEXT    NOT NULL,
                        synced_at_ms    INTEGER
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX ix_unsynced ON motion_events_local(synced_at_ms) WHERE synced_at_ms IS NULL")
                db.execSQL("CREATE INDEX ix_detected ON motion_events_local(detected_at_ms)")
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }
        db = helper.writableDatabase
        refreshPending()

        // バックグラウンド同期ループ
        scope.launch(Dispatchers.IO) {
            while (true) {
                delay(syncIntervalMs)
                try {
                    flushUnsent()
                } catch (e: Throwable) {
                    Log.w(TAG, "sync loop error", e)
                }
            }
        }
    }

    /**
     * 状態遷移を保存。即時返却（HTTP は別経路で送る）。
     */
    fun recordEvent(deviceMac: String, detectionState: String, detectedAtMs: Long, battery: Int?) {
        val payload = JSONObject().apply {
            put("eventType", "changeReport")
            put("eventVersion", "1")
            val ctx = JSONObject().apply {
                put("deviceType", "WoPresence")
                put("deviceMac", deviceMac)
                put("detectionState", detectionState)
                put("timeOfSample", detectedAtMs)
                if (battery != null) put("battery", battery)
                put("source", "tv-ble-bridge")
            }
            put("context", ctx)
        }.toString()

        db.execSQL(
            "INSERT INTO motion_events_local (device_mac, detection_state, detected_at_ms, raw_payload) VALUES (?, ?, ?, ?)",
            arrayOf(deviceMac, detectionState, detectedAtMs, payload)
        )
        refreshPending()
    }

    /**
     * 未送信レコードを最大50件まで HTTP POST。
     * 1件でも失敗したら以降は次の周期に回す（ネット切断時の無駄打ち抑制）。
     */
    fun flushUnsent() {
        val cursor = db.rawQuery(
            "SELECT id, raw_payload FROM motion_events_local WHERE synced_at_ms IS NULL ORDER BY id LIMIT 50",
            null
        )
        val toSend = mutableListOf<Pair<Long, String>>()
        cursor.use { c ->
            while (c.moveToNext()) {
                toSend.add(c.getLong(0) to c.getString(1))
            }
        }
        if (toSend.isEmpty()) {
            lastSyncTimeMs = System.currentTimeMillis()
            return
        }

        var syncedCount = 0
        for ((id, payload) in toSend) {
            val ok = try {
                val req = Request.Builder()
                    .url(webhookUrl)
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                httpClient.newCall(req).execute().use { resp -> resp.isSuccessful }
            } catch (e: Throwable) {
                Log.d(TAG, "POST failed: ${e.javaClass.simpleName}: ${e.message}")
                false
            }

            if (ok) {
                db.execSQL(
                    "UPDATE motion_events_local SET synced_at_ms = ? WHERE id = ?",
                    arrayOf(System.currentTimeMillis(), id)
                )
                syncedCount += 1
            } else {
                break  // 1件失敗したら以降諦め、次の周期へ
            }
        }

        lastSyncTimeMs = System.currentTimeMillis()
        lastSyncOk = syncedCount == toSend.size
        if (syncedCount > 0) {
            Log.i(TAG, "synced $syncedCount/${toSend.size} events")
        }
        refreshPending()
    }

    fun totalCount(): Int {
        db.rawQuery("SELECT COUNT(*) FROM motion_events_local", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun lastEventTimestamp(): Long? {
        db.rawQuery(
            "SELECT detected_at_ms FROM motion_events_local ORDER BY id DESC LIMIT 1",
            null
        ).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else null
        }
    }

    private fun refreshPending() {
        db.rawQuery(
            "SELECT COUNT(*) FROM motion_events_local WHERE synced_at_ms IS NULL",
            null
        ).use { c ->
            pendingCount = if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    companion object {
        private const val TAG = "Uploader"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
