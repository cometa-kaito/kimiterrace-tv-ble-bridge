package com.kimiterrace.tvbridge

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar

/**
 * ScheduleConfig に基づき、AlarmManager で「次の ON 時刻」「次の OFF 時刻」を予約する。
 *
 * 起動時・設定変更時に rescheduleAll() を呼ぶ。
 * ReceiveAlarmReceiver が発火を受けて BlackScreenActivity の開閉を行う。
 */
object ScheduleManager {

    private const val TAG = "ScheduleManager"
    const val ACTION_ALARM_ON = "com.kimiterrace.tvbridge.ALARM_ON"
    const val ACTION_ALARM_OFF = "com.kimiterrace.tvbridge.ALARM_OFF"
    // 始業前ウォームアップ: ON 時刻の少し前に常駐サービス/no-sleep 設定を蘇生させ、
    // 夜間に劣化したプロセス状態を生徒登校前にリセットする（#6）。
    const val ACTION_ALARM_WARMUP = "com.kimiterrace.tvbridge.ALARM_WARMUP"
    // 朝5時の定時ネット復旧（表示スケジュールと独立・毎日）。夜間の L3 セッション死を始業前に取り直す。
    const val ACTION_ALARM_RECOVERY = "com.kimiterrace.tvbridge.ALARM_RECOVERY"

    private const val REQ_ON = 1001
    private const val REQ_OFF = 1002
    private const val REQ_WARMUP = 1003
    private const val REQ_RECOVERY = 1004

    /** ON 時刻の何分前にウォームアップを発火させるか。 */
    private const val WARMUP_LEAD_MINUTES = 30

    /** 朝の定時ネット復旧の時刻（毎日 05:00・表示スケジュールと独立）。 */
    private const val RECOVERY_HOUR = 5
    private const val RECOVERY_MINUTE = 0

    fun rescheduleAll(context: Context) {
        val cfg = ScheduleConfig.load(context)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // 既存アラームクリア
        am.cancel(buildPendingIntent(context, ACTION_ALARM_ON, REQ_ON))
        am.cancel(buildPendingIntent(context, ACTION_ALARM_OFF, REQ_OFF))
        am.cancel(buildPendingIntent(context, ACTION_ALARM_WARMUP, REQ_WARMUP))
        am.cancel(buildPendingIntent(context, ACTION_ALARM_RECOVERY, REQ_RECOVERY))

        val now = Calendar.getInstance()

        // 朝5時の定時ネット復旧は **表示スケジュールと独立** に毎日必ず予約する（夜間の L3 セッション死は
        // 表示 ON/OFF と無関係。schedule 無効・休日でもネット復旧は要る）。
        val recovery = nextDailyAt(now, RECOVERY_HOUR, RECOVERY_MINUTE)
        setExactAlarm(context, am, recovery, ACTION_ALARM_RECOVERY, REQ_RECOVERY)
        Log.i(TAG, "next RECOVERY = ${recovery.time}")

        if (!cfg.enabled) {
            Log.i(TAG, "schedule disabled, display alarms not set (recovery still armed)")
            return
        }

        // 複数窓対応: 全窓の点灯/消灯エッジの中から「次に来る最も早いもの」を ON/OFF アラームに使う。
        // 発火のたび receiver が rescheduleAll を呼ぶため、毎回その先のエッジへ自動で再武装される
        // （2 アラーム構成のまま N 窓を巡回できる）。窓が無ければ単一窓 onHour/offHour にフォールバック。
        val windows = cfg.effectiveWindows()
        val nextOn = windows
            .map { nextOccurrence(now, cfg, it.onHour, it.onMinute) }
            .minByOrNull { it.timeInMillis } ?: nextOccurrence(now, cfg, cfg.onHour, cfg.onMinute)
        val nextOff = windows
            .map { nextOccurrence(now, cfg, it.offHour, it.offMinute) }
            .minByOrNull { it.timeInMillis } ?: nextOccurrence(now, cfg, cfg.offHour, cfg.offMinute)

        setExactAlarm(context, am, nextOn, ACTION_ALARM_ON, REQ_ON)
        setExactAlarm(context, am, nextOff, ACTION_ALARM_OFF, REQ_OFF)

        // ウォームアップ = 次回 ON の WARMUP_LEAD_MINUTES 分前。
        // 既に過ぎていたら（= 起動が ON 直前だった等）は予約せずスキップ。
        val warmup = (nextOn.clone() as Calendar).apply {
            add(Calendar.MINUTE, -WARMUP_LEAD_MINUTES)
        }
        if (warmup.after(now)) {
            setExactAlarm(context, am, warmup, ACTION_ALARM_WARMUP, REQ_WARMUP)
            Log.i(TAG, "next WARMUP = ${warmup.time}")
        } else {
            Log.i(TAG, "warmup window already passed, skipped")
        }

        Log.i(TAG, "next ON  = ${nextOn.time}")
        Log.i(TAG, "next OFF = ${nextOff.time}")
    }

    /**
     * 現在時刻に応じて画面状態を反映する。
     * - OFF 期間内（休日含む）→ 黒画面 Activity を前面化
     * - ON 期間内 → 黒画面を解除し、（autoLaunch 有効なら）サイネージを前面化
     * - スケジュール無効 → 黒画面が出ていれば解除
     *
     * 起動時・スケジュール変更時・アラーム発火後に呼ぶことで
     * 「設定は反映済みなのに画面状態が追従しない」状態を防ぐ。
     * バックグラウンドからの startActivity 成立には SYSTEM_ALERT_WINDOW 付与が前提。
     */
    fun applyCurrentState(context: Context) {
        val cfg = ScheduleConfig.load(context)
        if (!cfg.enabled) {
            context.sendBroadcast(
                Intent(BlackScreenActivity.ACTION_DISMISS).setPackage(context.packageName)
            )
            return
        }
        if (cfg.isCurrentlyInOffPeriod(Calendar.getInstance())) {
            runCatching {
                context.startActivity(
                    Intent(context, BlackScreenActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            }.onFailure { Log.w(TAG, "applyCurrentState: black overlay launch failed (SYSTEM_ALERT_WINDOW 未付与?): ${it.message}") }
            Log.i(TAG, "applyCurrentState: OFF period -> black screen")
        } else {
            context.sendBroadcast(
                Intent(BlackScreenActivity.ACTION_DISMISS).setPackage(context.packageName)
            )
            if (Config.autoLaunchSignage(context) && Config.signageUrl(context).isNotBlank()) {
                runCatching {
                    context.startActivity(
                        Intent(context, SignageActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure { Log.w(TAG, "applyCurrentState: signage launch failed (SYSTEM_ALERT_WINDOW 未付与?): ${it.message}") }
            }
            Log.i(TAG, "applyCurrentState: ON period -> dismiss black / show signage")
        }
    }

    /**
     * 指定時刻の「次回発火タイミング」を ScheduleConfig の曜日マスクを尊重して計算。
     * 当日該当曜日かつ未来の時刻ならその当日。さもなくば翌週内の最初の有効曜日へ。
     */
    private fun nextOccurrence(now: Calendar, cfg: ScheduleConfig, hour: Int, minute: Int): Calendar {
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        for (i in 0..7) {
            val candidate = (target.clone() as Calendar).apply {
                add(Calendar.DAY_OF_MONTH, i)
            }
            // 当日の場合は now より未来かをチェック
            if (i == 0 && !candidate.after(now)) continue
            if (cfg.isDayActive(candidate.get(Calendar.DAY_OF_WEEK))) {
                return candidate
            }
        }
        // どこにも該当しない（マスクが 0 など）→ 1 日後で適当に置く（実用上 0 マスクは UI で避ける）
        return (target.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
    }

    /** 曜日マスクを無視した「次回 hour:minute」（当日が未来ならその当日、過ぎていれば翌日）。定時ネット復旧用。 */
    private fun nextDailyAt(now: Calendar, hour: Int, minute: Int): Calendar {
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (!target.after(now)) target.add(Calendar.DAY_OF_MONTH, 1)
        return target
    }

    private fun setExactAlarm(
        context: Context,
        am: AlarmManager,
        at: Calendar,
        action: String,
        reqCode: Int,
    ) {
        val pi = buildPendingIntent(context, action, reqCode)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.timeInMillis, pi)
                } else {
                    // 正確なアラーム権限が無い場合は inexact で妥協
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.timeInMillis, pi)
                }
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.timeInMillis, pi)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "exact alarm denied, falling back to inexact: $e")
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.timeInMillis, pi)
        }
    }

    private fun buildPendingIntent(context: Context, action: String, reqCode: Int): PendingIntent {
        val intent = Intent(context, ScheduleAlarmReceiver::class.java).setAction(action)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(context, reqCode, intent, flags)
    }
}
