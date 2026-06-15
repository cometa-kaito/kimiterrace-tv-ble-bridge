package com.kimiterrace.tvbridge

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log

/**
 * 夜間 OFF / 朝 ON の「画面だけ消す・点ける」を Device Owner 権限で実現する。
 *
 * 設計判断（2026-06-08）:
 *  - 端末を *深いスリープ* に落とすと、ポーリングが止まりリモート wake も届かず **復帰不能** になる
 *    （ユーザー確認済み）。よって端末本体は 24h 起こしたまま、**画面（バックライト）だけ** を消す。
 *  - 画面 OFF の本命は DevicePolicyManager.lockNow()（Device Owner / アクティブな端末管理者なら可）。
 *    これは画面ロック＝バックライト OFF を起こすが、CPU/サービス/ポーリングは生きたまま。
 *  - HDMI-CEC（CecHelper）は privileged 権限が要りほぼ no-op なので、あくまで *補助* として併用。
 *  - BlackScreenActivity（黒オーバーレイ）は lockNow が効かない機種向けの *見た目* フォールバック。
 *
 * すべて runCatching で包み、Device Owner でない／非対応機でも例外を投げずに穏当に劣化する。
 */
object PowerController {
    private const val TAG = "PowerController"

    /** wake 用ウェイクロックの保持時間（短時間で画面を起こすだけ。常時保持はしない）。 */
    private const val WAKE_TIMEOUT_MS = 3_000L

    /** DevicePolicyManager を取得（API 経由。null になり得る）。 */
    private fun dpm(context: Context): DevicePolicyManager? =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager

    private fun adminComponent(context: Context): ComponentName =
        ComponentName(context.applicationContext, TvDeviceAdminReceiver::class.java)

    /**
     * 端末所有者（Device Owner）か。lockTask の許可リスト設定など強い操作の前提。
     */
    fun isDeviceOwner(context: Context): Boolean = runCatching {
        dpm(context)?.isDeviceOwnerApp(context.packageName) == true
    }.getOrDefault(false)

    /**
     * lockNow() を呼べる状態か（Device Owner もしくはアクティブな端末管理者）。
     */
    private fun canLock(context: Context): Boolean = runCatching {
        val manager = dpm(context) ?: return false
        manager.isDeviceOwnerApp(context.packageName) ||
            manager.isAdminActive(adminComponent(context))
    }.getOrDefault(false)

    /**
     * 画面 OFF（夜間消灯の本命）。
     * Device Owner / アクティブ端末管理者なら lockNow() でバックライトごと消す。
     * 端末本体は起きたまま（ポーリング継続）。CEC standby も補助的に試行。
     */
    fun screenOff(context: Context) {
        // overlay モード（既定）: lockNow を使わず、黒オーバーレイ(明るさ0+FLAG_KEEP_SCREEN_ON)で擬似黒にする。
        // パネルを起こしたまま擬似黒にするので、朝 ON は「オーバーレイ解除」だけで必ず復帰する
        //（lockNow が復帰不能スリープを誘発するメーカー対策。lockNow バックストップは復帰不能化するため敢えて使わない）。
        // applyCurrentState 任せにせず screenOff 自身でもオーバーレイ起動を試みる＝二経路の冗長化。
        // どちらかが BAL/SYSTEM_ALERT_WINDOW で落ちても黒画面が出る（多重起動は singleTask で無害）。
        if (Config.isNightOffOverlay(context)) {
            Log.i(TAG, "screenOff: overlay mode -> show black overlay (keep-screen-on), lockNow skipped")
            runCatching {
                context.startActivity(
                    Intent(context, BlackScreenActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            }.onFailure { Log.w(TAG, "screenOff: black overlay launch failed (SYSTEM_ALERT_WINDOW 未付与?): ${it.message}") }
            return
        }

        val locked = runCatching {
            if (canLock(context)) {
                dpm(context)?.lockNow()
                Log.i(TAG, "screenOff: lockNow() issued")
                true
            } else {
                Log.w(TAG, "screenOff: not device owner / admin -> lockNow skipped")
                false
            }
        }.onFailure { Log.w(TAG, "screenOff: lockNow failed: ${it.message}") }
            .getOrDefault(false)

        // 補助: HDMI-CEC standby（多くの機種で no-op だが効けば儲けもの）
        runCatching { CecHelper.tryStandby(context) }

        if (!locked) {
            Log.i(TAG, "screenOff: relying on BlackScreenActivity overlay fallback")
        }
    }

    /**
     * 画面 ON（朝の点灯／リモート wake）。
     * 1) PowerManager のウェイクロック（SCREEN_BRIGHT | ACQUIRE_CAUSES_WAKEUP | ON_AFTER_RELEASE）を
     *    短時間取得してディスプレイを起こす。
     * 2) SignageActivity を turnScreenOn フラグ付きで前面化する。
     * 3) 補助で CEC wake も試行。
     */
    @Suppress("DEPRECATION")
    fun screenOn(context: Context) {
        // 1) ディスプレイを起こすウェイクロック（短時間で release）
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "TVBleBridge::screenOn",
            )
            wl.acquire(WAKE_TIMEOUT_MS)
            // タイムアウトでも自動 release されるが、明示 release も入れて確実に手放す
            try {
                if (wl.isHeld) wl.release()
            } catch (_: Throwable) {}
            Log.i(TAG, "screenOn: SCREEN_BRIGHT wakelock acquired (${WAKE_TIMEOUT_MS}ms)")
        }.onFailure { Log.w(TAG, "screenOn: wakelock failed: ${it.message}") }

        // 2) SignageActivity を前面化（turnScreenOn=true + window フラグで画面点灯）
        runCatching {
            context.startActivity(
                Intent(context, SignageActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                    ),
            )
        }.onFailure { Log.w(TAG, "screenOn: launch signage failed (SYSTEM_ALERT_WINDOW 未付与?): ${it.message}") }

        // 3) 補助: HDMI-CEC wake
        runCatching { CecHelper.tryWakeUp(context) }
    }

    /**
     * Device Owner なら自分自身を lock task（キオスク）許可リストへ登録する。
     * SignageActivity.startLockTask() の前提。Device Owner でなければ no-op。
     */
    fun allowLockTaskSelf(context: Context) {
        runCatching {
            val manager = dpm(context) ?: return
            if (manager.isDeviceOwnerApp(context.packageName)) {
                manager.setLockTaskPackages(
                    adminComponent(context),
                    arrayOf(context.packageName),
                )
                Log.i(TAG, "lock task package whitelisted: ${context.packageName}")
            }
        }.onFailure { Log.w(TAG, "allowLockTaskSelf failed: ${it.message}") }
    }
}
