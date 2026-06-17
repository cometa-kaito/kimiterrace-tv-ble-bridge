package com.kimiterrace.tvbridge

import android.app.Application
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * アプリ全体のエントリ。注入プログラムの「絶対に死んだままにしない」防御の親玉。
 *
 *  1. **未捕捉例外フック**: どのスレッドで想定外の例外が起きても、プロセスが落ちる直前に
 *     「数秒後に蘇生する」アラームを仕込む。これで *あらゆる* クラッシュが「一瞬落ちて自動復帰」に
 *     縮退し、復帰不能の沈黙（同一環境なのに一部端末だけ無音化する不安定さ）を根絶する。
 *  2. **プロセス生成時の武装**: 何かのきっかけ（boot / FCM / alarm / ランチャー起動）で
 *     プロセスが生きた瞬間に、常駐サービスとウォッチドッグ（AlarmManager + WorkManager の二重経路）を
 *     必ず起動/再武装する。
 *
 * Firebase は FirebaseInitProvider、WorkManager は androidx.startup の各 ContentProvider が本 onCreate より
 * 前に自動初期化するため、ここでの追加初期化は不要。
 */
class TvBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashRestartHandler()
        // 常駐サービスとウォッチドッグを起動（ensureRunning は内部で例外を握るので落ちない）。
        runCatching { BleService.ensureRunning(this) }
            .onFailure { Log.w(TAG, "ensureRunning failed: ${it.message}") }
        runCatching { Watchdog.schedule(this) }
            .onFailure { Log.w(TAG, "watchdog schedule failed: ${it.message}") }
        // WorkManager periodic を AlarmManager とは独立した第2経路として常駐保証に使う
        //（OEM が exact alarm を握り潰しても OS 管理の Work が拾う。最短15分・KEEP で重複登録を回避）。
        runCatching {
            val req = PeriodicWorkRequestBuilder<KeepAliveWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(this)
                .enqueueUniquePeriodicWork(KEEPALIVE_WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }.onFailure { Log.w(TAG, "workmanager enqueue failed: ${it.message}") }
    }

    /**
     * 全スレッドの未捕捉例外ハンドラ。落ちる直前に [Watchdog.scheduleRestart] で蘇生アラームを仕込み、
     * 既存（Android のクラッシュ処理）に委譲してプロセスを終わらせる。アラームは OS 側に残るので
     * プロセス終了後に発火し、BleService を起こし直す。
     */
    private fun installCrashRestartHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                Log.e(TAG, "uncaught exception on '${thread.name}' -> scheduling self-restart", throwable)
                Watchdog.scheduleRestart(this, 3000L)
            }
            // 既定ハンドラへ委譲（クラッシュダイアログ/ANR 連携を壊さない）。無ければ自プロセスを落とす。
            val prev = previous
            if (prev != null) {
                prev.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            }
        }
    }

    companion object {
        private const val TAG = "TvBridgeApp"
        const val KEEPALIVE_WORK = "tvbridge-keepalive"
    }
}
