package io.github.peicheng0413.flexpomodoro.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.peicheng0413.flexpomodoro.app
import io.github.peicheng0413.flexpomodoro.habit.HabitProgress
import io.github.peicheng0413.flexpomodoro.habit.buildProgress
import io.github.peicheng0413.flexpomodoro.habit.endedDailyByHabit
import io.github.peicheng0413.flexpomodoro.habit.toLocalDate
import io.github.peicheng0413.flexpomodoro.timer.SessionSummary
import io.github.peicheng0413.flexpomodoro.timer.TimerSnapshot
import java.time.LocalDate
import java.time.ZoneId

/** 習慣頁與計時頁共用的資料：每個習慣的進度、未歸類紀錄、全部已結束紀錄。 */
class HabitBoard(
    /** 全部習慣，包含已封存。 */
    val all: List<HabitProgress>,
    val ended: List<SessionSummary>,
    val today: LocalDate,
) {
    val current: List<HabitProgress> = all.filter { !it.habit.archived }
    val archived: List<HabitProgress> = all.filter { it.habit.archived }
    val unassigned: List<SessionSummary> = ended.filter { it.session.habitId == null }

    fun of(habitId: Long): HabitProgress? = all.firstOrNull { it.habit.id == habitId }

    fun records(habitId: Long): List<SessionSummary> = ended.filter { it.session.habitId == habitId }
}

/**
 * 已結束紀錄的每日時間只在資料變動時重算；進行中的專注每秒併進去，沒有專注時每分鐘更新一次（跨日用）。
 */
@Composable
fun rememberHabitBoard(active: TimerSnapshot?): HabitBoard {
    val app = LocalContext.current.app
    val habits by app.habits.habits.collectAsStateWithLifecycle(emptyList())
    val goals by app.habits.goals.collectAsStateWithLifecycle(emptyList())
    val ended by app.sessions.ended.collectAsStateWithLifecycle(emptyList())
    val zone = remember { ZoneId.systemDefault() }
    val endedDaily = remember(ended) { endedDailyByHabit(ended, zone) }
    val now by rememberNow(if (active != null) 1000L else 60_000L)
    return remember(habits, goals, ended, endedDaily, active, now) {
        HabitBoard(buildProgress(habits, goals, endedDaily, active, now, zone), ended, now.toLocalDate(zone))
    }
}
