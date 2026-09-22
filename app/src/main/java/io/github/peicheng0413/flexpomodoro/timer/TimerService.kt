package io.github.peicheng0413.flexpomodoro.timer

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.peicheng0413.flexpomodoro.app
import kotlinx.coroutines.launch

/**
 * 專注進行中時常駐的前景服務：維持常駐通知、排程休息結束／超時／自動結束的鬧鐘。
 * 計時本身完全以資料庫裡的時間戳記計算，服務被殺掉重啟也不會跑掉。
 */
class TimerService : LifecycleService() {
    private var lastNotification: Notification? = null

    override fun onCreate() {
        super.onCreate()
        lifecycleScope.launch {
            app.sessions.active.collect { onState(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // startForegroundService 之後必須在幾秒內呼叫 startForeground
        ServiceCompat.startForeground(
            this, Notifications.ONGOING_ID,
            lastNotification ?: Notifications.placeholder(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        val sessions = app.sessions
        when (intent?.action) {
            ACTION_PAUSE -> app.appScope.launch { sessions.pause() }
            ACTION_RESUME -> app.appScope.launch { sessions.resume() }
            ACTION_BREAK -> app.appScope.launch { sessions.takeBreak() }
            ACTION_WORK -> app.appScope.launch { sessions.startWork() }
        }
        return START_STICKY
    }

    private fun onState(s: TimerSnapshot?) {
        if (s == null) {
            Notifications.cancelAlert(this)
            TimerEngine.cancelAlarm(this)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val n = Notifications.ongoing(this, s, System.currentTimeMillis())
        lastNotification = n
        Notifications.update(this, n)
        TimerEngine.afterStateChange(this, s)
    }

    companion object {
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_BREAK = "break"
        const val ACTION_WORK = "work"

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, TimerService::class.java))
    }
}

/** 休息結束提醒、超時輕震、自動結束的排程與觸發。 */
object TimerEngine {
    fun afterStateChange(context: Context, s: TimerSnapshot) {
        val now = System.currentTimeMillis()
        deliverDueAlerts(context, s, now)
        scheduleNext(context, s, now)
    }

    /** 鬧鐘觸發：先檢查自動結束，再補發該發的提醒並更新通知。 */
    suspend fun onAlarm(context: Context) {
        val app = context.app
        app.sessions.checkAutoEnd()?.let { Notifications.showAutoEnded(context, it) }
        val s = app.sessions.snapshot() ?: return
        val now = System.currentTimeMillis()
        // 倒數到 0 時通知標題要從「休息」換成「超時」
        Notifications.update(context, Notifications.ongoing(context, s, now))
        deliverDueAlerts(context, s, now)
        scheduleNext(context, s, now)
    }

    private fun deliverDueAlerts(context: Context, s: TimerSnapshot, now: Long) {
        val settings = context.app.settings
        if (s.phase != Phase.BREAK) {
            Notifications.cancelAlert(context)
            return
        }
        val over = now - s.breakEndAt
        if (over < 0) return
        if (settings.alertedBreakSegmentId != s.current.id) {
            settings.alertedBreakSegmentId = s.current.id
            settings.lastOvertimePing = 0
            if (over < OVERTIME_PING_MS) Notifications.showBreakEnded(context)
        }
        val ping = (over / OVERTIME_PING_MS).toInt()
        if (ping >= 1 && ping > settings.lastOvertimePing) {
            settings.lastOvertimePing = ping
            Notifications.showOvertime(context, ping * OVERTIME_PING_MS / MINUTE_MS)
        }
    }

    private fun scheduleNext(context: Context, s: TimerSnapshot, now: Long) {
        val threshold = context.app.settings.autoEndMinutes.value * MINUTE_MS
        val candidates = buildList {
            s.autoEndTriggerAt(threshold)?.let { add(it) }
            if (s.phase == Phase.BREAK) {
                val over = now - s.breakEndAt
                add(if (over < 0) s.breakEndAt else s.breakEndAt + (over / OVERTIME_PING_MS + 1) * OVERTIME_PING_MS)
            }
        }
        val next = candidates.minOrNull()
        if (next == null) {
            cancelAlarm(context)
            return
        }
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(context)
        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.coerceAtLeast(now), pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.coerceAtLeast(now), pi)
        }
    }

    fun cancelAlarm(context: Context) =
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))

    private fun alarmIntent(context: Context) = PendingIntent.getBroadcast(
        context, 0, Intent(context, AlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        context.app.appScope.launch {
            try {
                TimerEngine.onAlarm(context)
            } finally {
                pending.finish()
            }
        }
    }
}

/** 重開機或 App 更新後，如果有進行中的專注就把常駐通知接回來。 */
class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        context.app.appScope.launch {
            try {
                val sessions = context.app.sessions
                sessions.checkAutoEnd()?.let { Notifications.showAutoEnded(context, it) }
                if (sessions.snapshot() != null) TimerService.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
