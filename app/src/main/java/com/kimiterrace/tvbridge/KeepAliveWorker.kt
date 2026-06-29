package com.kimiterrace.tvbridge

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * WorkManager 経由の常駐保証 Worker。AlarmManager ウォッチドッグ（[WatchdogReceiver]）と同じ
 * [Watchdog.tick]（常駐の存在保証 + poll liveness 自己診断 → 必要なら強制再起動）を、OS 管理の
 * 独立スケジューラから実行する **第2経路**。`TvBridgeApp` が最短 15 分間隔・KEEP で enqueue する。
 *
 * AlarmManager 経路が OEM の省電力に握り潰されても本経路（JobScheduler ベースで OS が永続配信）が拾い、
 * 逆に WorkManager が殺されても AlarmManager が拾う相互保険。
 */
class KeepAliveWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result {
        return try {
            Watchdog.tick(applicationContext)
            Result.success()
        } catch (e: Throwable) {
            // 失敗しても次回 periodic で再実行されるので success で握る（retry バックオフに乗せない）。
            Result.success()
        }
    }
}
