package io.github.peicheng0413.flexpomodoro

import io.github.peicheng0413.flexpomodoro.data.HabitEntity
import io.github.peicheng0413.flexpomodoro.data.HabitGoalEntity
import io.github.peicheng0413.flexpomodoro.data.SegmentEntity
import io.github.peicheng0413.flexpomodoro.data.SegmentType
import io.github.peicheng0413.flexpomodoro.data.SessionEntity
import io.github.peicheng0413.flexpomodoro.habit.Goal
import io.github.peicheng0413.flexpomodoro.habit.GoalHistory
import io.github.peicheng0413.flexpomodoro.habit.HabitProgress
import io.github.peicheng0413.flexpomodoro.habit.LEVEL_DONE
import io.github.peicheng0413.flexpomodoro.habit.LEVEL_HIGH
import io.github.peicheng0413.flexpomodoro.habit.LEVEL_LOW
import io.github.peicheng0413.flexpomodoro.habit.LEVEL_NONE
import io.github.peicheng0413.flexpomodoro.habit.Mark
import io.github.peicheng0413.flexpomodoro.habit.nextMark
import io.github.peicheng0413.flexpomodoro.data.DayMarkEntity
import io.github.peicheng0413.flexpomodoro.habit.buildProgress
import io.github.peicheng0413.flexpomodoro.habit.dailyWorkMs
import io.github.peicheng0413.flexpomodoro.habit.overviewLevel
import io.github.peicheng0413.flexpomodoro.habit.weekStart
import io.github.peicheng0413.flexpomodoro.timer.TimerSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class HabitLogicTest {
    private val min = 60_000L
    private val zone = ZoneId.of("Asia/Taipei")
    private val habit = HabitEntity(id = 1, name = "讀書", breakPercent = 20)

    // 2026-10-14 是星期三，這週從 10/12（一）開始
    private val today = LocalDate.of(2026, 10, 14)

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun seg(type: SegmentType, start: Long, end: Long?) =
        SegmentEntity(sessionId = "s", type = type, round = 1, start = start, end = end)

    private fun goal(from: LocalDate, daily: Int, weekly: Int) = HabitGoalEntity(1, from.toEpochDay(), daily, weekly)

    private fun progress(daily: Map<LocalDate, Long>, goals: List<HabitGoalEntity> = listOf(HabitGoalEntity(1, 0, 30, 3))) =
        HabitProgress(habit, GoalHistory(goals), daily, today)

    @Test
    fun workAcrossMidnightIsSplitBetweenDays() {
        val daily = dailyWorkMs(listOf(
            seg(SegmentType.WORK, at(2026, 10, 13, 23, 30), at(2026, 10, 14, 0, 45)),
            seg(SegmentType.PAUSE, at(2026, 10, 14, 0, 45), at(2026, 10, 14, 1, 0)),
            // 進行中的工作段算到 now
            seg(SegmentType.WORK, at(2026, 10, 14, 1, 0), null),
        ), now = at(2026, 10, 14, 1, 20), zone = zone)
        assertEquals(30 * min, daily[LocalDate.of(2026, 10, 13)])
        assertEquals(65 * min, daily[LocalDate.of(2026, 10, 14)])
    }

    @Test
    fun goalInEffectOnEachDay() {
        val change = LocalDate.of(2026, 10, 1)
        val goals = GoalHistory(listOf(goal(LocalDate.ofEpochDay(0), 30, 5), goal(change, 60, 3)))
        assertEquals(Goal(30, 5), goals.at(change.minusDays(1)))
        assertEquals(Goal(60, 3), goals.at(change))
        assertEquals(Goal(60, 3), goals.at(today))
        // 比第一筆還早的日子用第一筆
        assertEquals(Goal(60, 3), GoalHistory(listOf(goal(change, 60, 3))).at(change.minusDays(10)))
    }

    @Test
    fun levelsAreRelativeToDailyGoal() {
        val p = progress(mapOf(
            today to 30 * min,
            today.minusDays(1) to 15 * min,
            today.minusDays(2) to 14 * min,
        ))
        assertEquals(LEVEL_DONE, p.level(today))
        assertEquals(LEVEL_HIGH, p.level(today.minusDays(1)))
        assertEquals(LEVEL_LOW, p.level(today.minusDays(2)))
        assertEquals(LEVEL_NONE, p.level(today.minusDays(3)))
        assertTrue(p.done(today))
        assertFalse(p.done(today.minusDays(1)))
    }

    @Test
    fun weeklyTargetCountsDaysNotTotalMinutes() {
        val monday = today.weekStart()
        assertEquals(LocalDate.of(2026, 10, 12), monday)
        // 一天做 300 分鐘也只算一天
        val p = progress(mapOf(monday to 300 * min))
        assertEquals(1, p.daysDone(monday))
        assertFalse(p.weekMet(monday))
    }

    @Test
    fun streakIgnoresUnfinishedCurrentWeek() {
        val thisWeek = today.weekStart()
        fun week(n: Long) = thisWeek.minusWeeks(n)
        val daily = buildMap {
            // 上週、上上週各達成 3 天；再前一週只有 2 天
            listOf(1L, 2L).forEach { n -> (0L..2L).forEach { put(week(n).plusDays(it), 30 * min) } }
            (0L..1L).forEach { put(week(3).plusDays(it), 30 * min) }
            put(thisWeek, 30 * min)
        }
        val p = progress(daily)
        assertTrue(p.weekMet(week(1)))
        assertFalse(p.weekMet(week(3)))
        assertFalse(p.weekMet(thisWeek))
        assertEquals(2, p.streakWeeks)

        // 本週也達標就算進去
        val more = progress(daily + mapOf(thisWeek.plusDays(1) to 30 * min, thisWeek.plusDays(2) to 30 * min))
        assertEquals(3, more.streakWeeks)
    }

    @Test
    fun weekTargetUsesGoalAtEndOfWeek() {
        val thisWeek = today.weekStart()
        val lastWeek = thisWeek.minusWeeks(1)
        // 上週三把每週目標從 5 天改成 2 天：上週以週日生效的 2 天為準
        val goals = listOf(goal(LocalDate.ofEpochDay(0), 30, 5), goal(lastWeek.plusDays(2), 30, 2))
        val p = progress(mapOf(lastWeek to 30 * min, lastWeek.plusDays(1) to 30 * min), goals)
        assertEquals(2, p.weekTarget(lastWeek))
        assertTrue(p.weekMet(lastWeek))
        assertEquals(5, p.weekTarget(lastWeek.minusWeeks(1)))
    }

    @Test
    fun overviewLevelCountsHabitsDone() {
        val a = progress(mapOf(today to 30 * min, today.minusDays(1) to 30 * min, today.minusDays(2) to 5 * min))
        val b = HabitProgress(habit.copy(id = 2), GoalHistory(emptyList()), mapOf(today to 30 * min), today)
        val both = listOf(a, b)
        assertEquals(LEVEL_DONE, overviewLevel(both, today))
        assertEquals(LEVEL_HIGH, overviewLevel(both, today.minusDays(1)))
        assertEquals(LEVEL_LOW, overviewLevel(both, today.minusDays(2)))
        assertEquals(LEVEL_NONE, overviewLevel(both, today.minusDays(3)))
    }

    @Test
    fun activeSessionCountsOnlyForItsHabit() {
        val start = at(2026, 10, 14, 9, 0)
        val session = SessionEntity(id = "s", name = "讀書", breakPercent = 20, startedAt = start, habitId = 1)
        val active = TimerSnapshot(session, listOf(seg(SegmentType.WORK, start, null)))
        val other = habit.copy(id = 2, name = "運動")
        val result = buildProgress(
            habits = listOf(habit, other),
            goalRows = emptyList(),
            endedDaily = mapOf(1L to mapOf(today to 10 * min)),
            markRows = emptyList(),
            active = active,
            now = start + 25 * min,
            zone = zone,
        )
        assertEquals(35 * min, result[0].workMs(today))
        assertEquals(0, result[1].workMs(today))
    }

    @Test
    fun tapCycleOnlyGoesUp() {
        // 原狀沒做：原狀 → 有做 → 達標 → 原狀
        assertEquals(Mark.PARTIAL, nextMark(LEVEL_NONE, null))
        assertEquals(Mark.DONE, nextMark(LEVEL_NONE, Mark.PARTIAL))
        assertEquals(null, nextMark(LEVEL_NONE, Mark.DONE))
        // 計時有做但未達標：原狀 → 達標 → 原狀
        assertEquals(Mark.DONE, nextMark(LEVEL_LOW, null))
        assertEquals(Mark.DONE, nextMark(LEVEL_HIGH, null))
        assertEquals(null, nextMark(LEVEL_HIGH, Mark.DONE))
        // 計時已達標：點了不變
        assertEquals(null, nextMark(LEVEL_DONE, null))
        assertEquals(Mark.DONE, nextMark(LEVEL_DONE, Mark.DONE))
    }

    @Test
    fun marksRaiseLevelAndCountTowardWeek() {
        val monday = today.weekStart()
        val p = HabitProgress(
            habit, GoalHistory(listOf(HabitGoalEntity(1, 0, 30, 3))),
            daily = mapOf(monday to 20 * min, monday.plusDays(1) to 40 * min),
            today = today,
            marks = mapOf(monday to Mark.DONE, monday.plusDays(2) to Mark.PARTIAL),
        )
        // 標記達標：亮深綠、算達成，但不灌分鐘數
        assertEquals(LEVEL_DONE, p.level(monday))
        assertTrue(p.done(monday))
        assertEquals(20 * min, p.workMs(monday))
        // 標記有做：淺綠、不算達成
        assertEquals(LEVEL_LOW, p.level(monday.plusDays(2)))
        assertFalse(p.done(monday.plusDays(2)))
        // 一天手動達標＋一天計時達標＝本週 2 天
        assertEquals(2, p.daysDone(monday))
        assertEquals(Mark.DONE, p.markAfterTap(monday.plusDays(2)))
    }

    @Test
    fun markRowsAreAttachedToTheirHabit() {
        val result = buildProgress(
            habits = listOf(habit, habit.copy(id = 2, name = "運動")),
            goalRows = emptyList(),
            endedDaily = emptyMap(),
            markRows = listOf(DayMarkEntity(2, today.toEpochDay(), true)),
            active = null,
            now = at(2026, 10, 14, 12, 0),
            zone = zone,
        )
        assertFalse(result[0].done(today))
        assertTrue(result[1].done(today))
        // 兩個習慣達成一個＝達成部分
        assertEquals(LEVEL_HIGH, overviewLevel(result, today))
    }
}
