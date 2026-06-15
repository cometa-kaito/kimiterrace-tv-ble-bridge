package com.kimiterrace.tvbridge

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import java.util.Calendar

/**
 * 「画面が勝手に黒くなる → 放置でスリープ」対策の一元管理。
 *
 * 症状の主因（2026-05-30 切り分け）:
 *  - SignageActivity の FLAG_KEEP_SCREEN_ON は *前面で可視のときだけ* 効く。
 *    ランチャ・システムダイアログ・スクリーンセーバ(Daydream) が覆うと失効し、
 *    OS の screen_off_timeout → sleep_timeout が走って画面が消える。
 *  - ADB で入れた settings（runbook §4.5）は *再起動で元に戻る*（位置情報トグルと同型）。
 *    さらに screensaver(Daydream) の無効化が抜けていた。
 *
 * 二段構えで吸収する:
 *  1) システム設定を *アプリ自身が* 無効化（恒久・再起動耐性）
 *     screen_off_timeout / sleep_timeout / screensaver を OS レベルで殺す。
 *     起動時(BootReceiver) と常駐中(BleService) に再適用するので、再起動 revert を解消。
 *     - Settings.Secure / Settings.Global  … WRITE_SECURE_SETTINGS が必要
 *     - Settings.System(screen_off_timeout) … WRITE_SETTINGS が必要
 *     いずれも未付与なら SecurityException を握りつぶし no-op（他の防御に委ねる）。
 *     付与は一度きりの ADB セットアップ（runbook §4.6）で完了し、以後は PC 不要で自走。
 *
 *  2) 前面の取りこぼしを補正
 *     ON 時間帯なのにサイネージが前面に居なければ前面へ戻す（FLAG_KEEP_SCREEN_ON を再付与）。
 *     既に前面なら何もしない（チラつき回避）。OFF 時間帯は黒画面維持のため触らない。
 *
 * リモートの commands.wake（= 管理側から送る復帰信号）からも forceWake() を呼べる。
 */
object KeepAwakeManager {
    private const val TAG = "KeepAwake"

    // Settings.Secure / Settings.Global のキー名（@hide 定数があるため文字列直書きで安定化）
    private const val KEY_SLEEP_TIMEOUT = "sleep_timeout"
    private const val KEY_SCREENSAVER_ENABLED = "screensaver_enabled"
    private const val KEY_SCREENSAVER_ON_SLEEP = "screensaver_activate_on_sleep"
    private const val KEY_SCREENSAVER_ON_DOCK = "screensaver_activate_on_dock"
    // 一部 Android TV のベンダーキー（存在すれば無効化／無ければ no-op）
    private const val KEY_TV_AUTO_OFF = "hdmi_control_auto_device_off_enabled"
    private const val KEY_NO_SIGNAL_OFF = "no_signal_auto_power_off"

    /**
     * 画面オフ・スリープ・スクリーンセーバを OS レベルで無効化する（ベストエフォート）。
     * 個々の書き込みは独立に try する（権限の付き方が機種で違うため、一部成功でも前進）。
     */
    fun applyNoSleepSettings(context: Context) {
        val cr = context.contentResolver

        // 画面オフを実質無効化（System: WRITE_SETTINGS）
        runCatching {
            Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, Int.MAX_VALUE)
        }.onSuccess { Log.i(TAG, "screen_off_timeout=MAX") }
            .onFailure { Log.d(TAG, "screen_off_timeout skip: ${it.message}") }

        // 画面オフ後のスリープ無効化（Secure: WRITE_SECURE_SETTINGS）
        putSecureInt(cr, KEY_SLEEP_TIMEOUT, -1)

        // スクリーンセーバ(Daydream) 無効化 — 「画面だけ黒くなる」の最有力主因
        putSecureInt(cr, KEY_SCREENSAVER_ENABLED, 0)
        putSecureInt(cr, KEY_SCREENSAVER_ON_SLEEP, 0)
        putSecureInt(cr, KEY_SCREENSAVER_ON_DOCK, 0)

        // 給電中は起きたまま（USB 給電の TV ボックス対策。非対応機なら素通り）
        runCatching {
            Settings.Global.putInt(cr, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 7)
        }.onFailure { Log.d(TAG, "stay_on_while_plugged_in skip: ${it.message}") }

        // ベンダー固有の自動電源オフ（あれば無効化）
        putGlobalInt(cr, KEY_TV_AUTO_OFF, 0)
        putGlobalInt(cr, KEY_NO_SIGNAL_OFF, 0)
    }

    private fun putSecureInt(cr: ContentResolver, key: String, value: Int) {
        runCatching { Settings.Secure.putInt(cr, key, value) }
            .onSuccess { Log.i(TAG, "secure $key=$value") }
            .onFailure { Log.d(TAG, "secure $key skip: ${it.message}") }
    }

    private fun putGlobalInt(cr: ContentResolver, key: String, value: Int) {
        runCatching { Settings.Global.putInt(cr, key, value) }
            .onFailure { Log.d(TAG, "global $key skip: ${it.message}") }
    }

    /**
     * 周期的な再アサート（BleService / ConfigPoller が ~1-2 分ごとに呼ぶ）。
     *
     * - ON 時間帯: サイネージが前面に居なければ前面へ戻す（FLAG_KEEP_SCREEN_ON 再付与）。
     * - OFF 時間帯: **絶対にサイネージを再起動しない**（夜間に画面を点け直してしまうため）。
     *   代わりに、もし画面が点いているように見える場合に備えて screenOff を best-effort で再発行する。
     *
     * autoLaunch 無効構成では ON 側の前面化はしない（触らない）。
     */
    fun reassertForegroundIfNeeded(context: Context) {
        val sched = ScheduleConfig.load(context)
        if (sched.isCurrentlyInOffPeriod(Calendar.getInstance())) {
            // OFF 期間: サイネージが万一前面に出ていたら OFF 状態へ戻す（サイネージ再起動は決してしない）。
            // screenOff がモードに応じて overlay 起動 / lockNow を行うので、ここはそれに委ねる。
            if (SignageActivity.isForeground) {
                Log.i(TAG, "signage foreground during OFF period -> re-assert screenOff")
                PowerController.screenOff(context)
            }
            return  // OFF は黒/消灯を維持（再起動禁止）
        }
        if (!Config.autoLaunchSignage(context)) return                   // 自動表示しない構成は触らない
        if (Config.signageUrl(context).isBlank()) return
        if (SignageActivity.isForeground) return                         // 既に前面なら何もしない
        Log.i(TAG, "signage not foreground during ON period -> re-assert")
        launchSignage(context)
    }

    /** リモート wake コマンド等からの強制復帰：設定を再適用し、サイネージを前面へ。 */
    fun forceWake(context: Context) {
        applyNoSleepSettings(context)
        if (Config.signageUrl(context).isNotBlank()) launchSignage(context)
    }

    private fun launchSignage(context: Context) {
        // バックグラウンドからの startActivity は SYSTEM_ALERT_WINDOW 付与が前提（runbook §4）。
        runCatching {
            context.startActivity(
                Intent(context, SignageActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }.onFailure { Log.w(TAG, "launchSignage failed (SYSTEM_ALERT_WINDOW 未付与?): ${it.message}") }
    }
}
