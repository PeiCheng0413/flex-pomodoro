package io.github.peicheng0413.flexpomodoro.habit

import io.github.peicheng0413.flexpomodoro.data.DEFAULT_DAILY_MINUTES
import io.github.peicheng0413.flexpomodoro.data.DEFAULT_WEEKLY_DAYS
import io.github.peicheng0413.flexpomodoro.data.DayMarkEntity
import io.github.peicheng0413.flexpomodoro.data.HabitEntity
import io.github.peicheng0413.flexpomodoro.data.HabitGoalEntity
import io.github.peicheng0413.flexpomodoro.data.SegmentEntity
import io.github.peicheng0413.flexpomodoro.data.SegmentType
import io.github.peicheng0413.flexpomodoro.timer.MINUTE_MS
import io.github.peicheng0413.flexpomodoro.timer.SessionSummary
import io.github.peicheng0413.flexpomodoro.timer.TimerSnapshot
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** 一週從星期一開始。 */
fun LocalDate.weekStart(): LocalDate = with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

fun Long.toLocalDate(zone: ZoneId): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

typealias DailyWork = Map<LocalDate, Long>

/** 工作段依日期切開加總：跨午夜的那一段分給兩天。進行中的段（end = null）算到 now。 */
fun dailyWorkMs(segments: List<SegmentEntity>, now: Long, zone: ZoneId): DailyWork {
    val out = HashMap<LocalDate, Long>()
    segments.filter { it.type == SegmentType.WORK }.forEach { g ->
        val end = g.end ?: now
        var from = g.start
        while (from < end) {
            val day = from.toLocalDate(zone)
            val nextMidnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val to = minOf(end, nextMidnight)
            out.merge(day, to - from) { a, b -> a + b }
            from = to
        }
    }
    return out
}

/** 兩份每日工作時間逐日相加（標準庫的 Map.plus 是覆蓋，不是相加）。 */
fun DailyWork.sumWith(other: DailyWork): DailyWork =
    if (other.isEmpty()) this else HashMap(this).apply { other.forEach { (d, ms) -> merge(d, ms) { a, b -> a + b } } }

/** 已結束的紀錄依習慣分組算好每日工作時間；key 為 null 的是未歸類。 */
fun endedDailyByHabit(ended: List<SessionSummary>, zone: ZoneId): Map<Long?, DailyWork> =
    ended.groupBy { it.session.habitId }.mapValues { (_, list) ->
        HashMap<LocalDate, Long>().apply {
            list.forEach { s -> dailyWorkMs(s.segments, s.end, zone).forEach { (d, ms) -> merge(d, ms) { a, b -> a + b } } }
        }
    }

data class Goal(val dailyMinutes: Int, val weeklyDays: Int) {
    val dailyMs: Long get() = dailyMinutes.coerceAtLeast(1) * MINUTE_MS
}

/** 目標變更紀錄：某天的目標＝那天以前最後一次生效的設定；比第一筆還早的日子用第一筆。 */
class GoalHistory(rows: List<HabitGoalEntity>) {
    private val sorted = rows.sortedBy { it.effectiveFrom }

    fun at(date: LocalDate): Goal {
        val day = date.toEpochDay()
        val row = sorted.lastOrNull { it.effectiveFrom <= day } ?: sorted.firstOrNull()
            ?: return Goal(DEFAULT_DAILY_MINUTES, DEFAULT_WEEKLY_DAYS)
        return Goal(row.dailyMinutes, row.weeklyDays)
    }
}

/** 熱圖的四階：沒做、未滿一半、一半以上但未達標、達標。 */
const val LEVEL_NONE = 0
const val LEVEL_LOW = 1
const val LEVEL_HIGH = 2
const val LEVEL_DONE = 3

/** 手動標記：有做未達標（淺綠）、達標（深綠）。 */
enum class Mark { PARTIAL, DONE }

/**
 * 點週曆的下一個狀態，只能往上加：計時紀錄是事實，不能被點掉。
 * - 原狀沒做：原狀 → 有做 → 達標 → 原狀
 * - 原狀有做但未達標：原狀 → 達標 → 原狀
 * - 計時已達標：不變
 * 回傳 null 表示清掉標記、恢復原狀。
 */
fun nextMark(timedLevel: Int, current: Mark?): Mark? = when {
    timedLevel >= LEVEL_DONE -> current
    current == null -> if (timedLevel == LEVEL_NONE) Mark.PARTIAL else Mark.DONE
    current == Mark.PARTIAL -> Mark.DONE
    else -> null
}

/** 一個習慣到今天為止的進度。daily 已包含進行中專注的部分；marks 是手動標記。 */
class HabitProgress(
    val habit: HabitEntity,
    val goals: GoalHistory,
    val daily: DailyWork,
    val today: LocalDate,
    val marks: Map<LocalDate, Mark> = emptyMap(),
) {
    fun workMs(date: LocalDate): Long = daily[date] ?: 0

    fun goal(date: LocalDate): Goal = goals.at(date)

    fun done(date: LocalDate): Boolean = timedLevel(date) == LEVEL_DONE || marks[date] == Mark.DONE

    /** 只看計時紀錄的色階。 */
    fun timedLevel(date: LocalDate): Int {
        val work = workMs(date)
        val target = goal(date).dailyMs
        return when {
            work <= 0 -> LEVEL_NONE
            work >= target -> LEVEL_DONE
            work * 2 >= target -> LEVEL_HIGH
            else -> LEVEL_LOW
        }
    }

    /** 畫面上的色階：計時紀錄與手動標記取較高的。 */
    fun level(date: LocalDate): Int = maxOf(
        timedLevel(date),
        when (marks[date]) {
            Mark.DONE -> LEVEL_DONE
            Mark.PARTIAL -> LEVEL_LOW
            null -> LEVEL_NONE
        },
    )

    /** 點週曆後要存的標記。 */
    fun markAfterTap(date: LocalDate): Mark? = nextMark(timedLevel(date), marks[date])

    fun daysDone(weekStart: LocalDate): Int =
        (0L until 7L).map { weekStart.plusDays(it) }.count { !it.isAfter(today) && done(it) }

    /** 一週要達成幾天：以那週最後一天（本週則是今天）生效的目標為準。 */
    fun weekTarget(weekStart: LocalDate): Int = goal(minOf(weekStart.plusDays(6), today)).weeklyDays.coerceIn(1, 7)

    fun weekMet(weekStart: LocalDate): Boolean = daysDone(weekStart) >= weekTarget(weekStart)

    /** 連續達標週數。本週還沒達標不算中斷，從上週往回數。 */
    val streakWeeks: Int by lazy {
        val thisWeek = today.weekStart()
        var week = if (weekMet(thisWeek)) thisWeek else thisWeek.minusWeeks(1)
        var count = 0
        while (weekMet(week)) {
            count++
            week = week.minusWeeks(1)
        }
        count
    }

    /** 最早有紀錄的那一天，沒有紀錄就是今天。 */
    val firstDay: LocalDate get() = daily.keys.minOrNull() ?: today

    fun totalMs(from: LocalDate = LocalDate.MIN): Long =
        daily.entries.sumOf { (d, ms) -> if (d.isBefore(from)) 0L else ms }
}

/** 總覽熱圖：沒做、有做但沒達成任何習慣、達成部分、全部達成。 */
fun overviewLevel(progress: List<HabitProgress>, date: LocalDate): Int {
    if (progress.isEmpty()) return LEVEL_NONE
    val done = progress.count { it.done(date) }
    return when {
        done == progress.size -> LEVEL_DONE
        done > 0 -> LEVEL_HIGH
        progress.any { it.level(date) > LEVEL_NONE } -> LEVEL_LOW
        else -> LEVEL_NONE
    }
}

/**
 * 組出每個習慣的進度：已結束紀錄的每日時間（先算好、不常變）加上進行中專注到 now 為止的部分。
 */
fun buildProgress(
    habits: List<HabitEntity>,
    goalRows: List<HabitGoalEntity>,
    endedDaily: Map<Long?, DailyWork>,
    markRows: List<DayMarkEntity>,
    active: TimerSnapshot?,
    now: Long,
    zone: ZoneId,
): List<HabitProgress> {
    val today = now.toLocalDate(zone)
    val goalsByHabit = goalRows.groupBy { it.habitId }
    val marksByHabit = markRows.groupBy { it.habitId }.mapValues { (_, rows) ->
        rows.associate { LocalDate.ofEpochDay(it.day) to if (it.done) Mark.DONE else Mark.PARTIAL }
    }
    val activeDaily = active?.let { dailyWorkMs(it.segments, now, zone) }.orEmpty()
    return habits.map { h ->
        val ended = endedDaily[h.id].orEmpty()
        val daily = if (active?.session?.habitId == h.id) ended.sumWith(activeDaily) else ended
        HabitProgress(h, GoalHistory(goalsByHabit[h.id].orEmpty()), daily, today, marksByHabit[h.id].orEmpty())
    }
}
