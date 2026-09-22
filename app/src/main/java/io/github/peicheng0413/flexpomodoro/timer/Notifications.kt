package io.github.peicheng0413.flexpomodoro.timer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.peicheng0413.flexpomodoro.MainActivity
import io.github.peicheng0413.flexpomodoro.R
import io.github.peicheng0413.flexpomodoro.ui.formatDuration
import io.github.peicheng0413.flexpomodoro.ui.formatDurationWords

object Notifications {
    const val ONGOING_ID = 1
    private const val ALERT_ID = 2
    private const val AUTO_END_ID = 3

    private const val CH_ONGOING = "timer"
    private const val CH_BREAK_END = "break_end"
    private const val CH_OVERTIME = "overtime"
    private const val CH_AUTO_END = "auto_end"

    // Android 16 Live Updates：讓常駐通知在狀態列顯示成計時膠囊
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    private const val COLOR_WORK = 0xFF9E9E9E.toInt()
    private const val COLOR_BREAK = 0xFF43A047.toInt()
    private const val COLOR_OVERTIME = 0xFFE53935.toInt()

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(CH_ONGOING, "計時中", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "專注進行中的常駐通知"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
            NotificationChannel(CH_BREAK_END, "休息結束", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "休息倒數到 0 時提醒"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 150, 300)
            },
            NotificationChannel(CH_OVERTIME, "休息超時", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "休息超時後每 5 分鐘輕震一次"
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 120)
            },
            NotificationChannel(CH_AUTO_END, "自動結束", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "暫停或超時太久而自動結束專注時通知"
                setSound(null, null)
            },
        ))
    }

    fun placeholder(context: Context): Notification =
        NotificationCompat.Builder(context, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("彈性蕃茄鐘")
            .setOngoing(true)
            .setSilent(true)
            .build()

    fun ongoing(context: Context, s: TimerSnapshot, now: Long): Notification {
        val name = s.session.name.ifEmpty { "未命名" }
        val b = NotificationCompat.Builder(context, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentText("$name · 第 ${s.round} 輪")
            .addExtras(android.os.Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })

        when (s.phase) {
            Phase.WORKING -> b.setContentTitle("工作中")
                .setColor(COLOR_WORK)
                .setUsesChronometer(true)
                .setWhen(s.workBase)
                .setShowWhen(true)
                .addAction(0, "暫停", action(context, TimerService.ACTION_PAUSE))
                .addAction(0, "休息", action(context, TimerService.ACTION_BREAK))

            Phase.PAUSED -> b.setContentTitle("已暫停 ${formatDuration(s.workMs(now))}")
                .setColor(COLOR_WORK)
                .setShowWhen(false)
                .addAction(0, "繼續", action(context, TimerService.ACTION_RESUME))
                .addAction(0, "休息", action(context, TimerService.ACTION_BREAK))

            Phase.BREAK -> {
                val over = s.breakRemainingMs(now) <= 0
                b.setContentTitle(if (over) "超時" else "休息")
                    .setColor(if (over) COLOR_OVERTIME else COLOR_BREAK)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true)
                    .setWhen(s.breakEndAt)
                    .setShowWhen(true)
                    .addAction(0, "開始工作", action(context, TimerService.ACTION_WORK))
            }
        }
        return b.build()
    }

    fun showBreakEnded(context: Context) = notify(context, ALERT_ID,
        NotificationCompat.Builder(context, CH_BREAK_END)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("休息結束")
            .setContentText("準備開始下一輪工作")
            .setColor(COLOR_BREAK)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(openApp(context))
            .addAction(0, "開始工作", action(context, TimerService.ACTION_WORK))
            .build())

    fun showOvertime(context: Context, overtimeMinutes: Long) = notify(context, ALERT_ID,
        NotificationCompat.Builder(context, CH_OVERTIME)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("已超時 $overtimeMinutes 分鐘")
            .setContentText("休息時間已經結束")
            .setColor(COLOR_OVERTIME)
            .setContentIntent(openApp(context))
            .addAction(0, "開始工作", action(context, TimerService.ACTION_WORK))
            .build())

    fun cancelAlert(context: Context) =
        context.getSystemService(NotificationManager::class.java).cancel(ALERT_ID)

    fun showAutoEnded(context: Context, summary: SessionSummary) = notify(context, AUTO_END_ID,
        NotificationCompat.Builder(context, CH_AUTO_END)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("已自動結束")
            .setContentText("${summary.session.name.ifEmpty { "未命名" }}：本次工作 ${formatDurationWords(summary.workMs)}")
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build())

    fun update(context: Context, notification: Notification) = notify(context, ONGOING_ID, notification)

    private fun notify(context: Context, id: Int, notification: Notification) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.areNotificationsEnabled()) nm.notify(id, notification)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(context: Context, action: String): PendingIntent = PendingIntent.getForegroundService(
        context, action.hashCode(),
        Intent(context, TimerService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
