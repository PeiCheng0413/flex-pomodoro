package io.github.peicheng0413.flexpomodoro

import io.github.peicheng0413.flexpomodoro.data.SegmentEntity
import io.github.peicheng0413.flexpomodoro.data.SegmentType
import io.github.peicheng0413.flexpomodoro.data.SessionEntity
import io.github.peicheng0413.flexpomodoro.timer.Phase
import io.github.peicheng0413.flexpomodoro.timer.SessionSummary
import io.github.peicheng0413.flexpomodoro.timer.TimerSnapshot
import io.github.peicheng0413.flexpomodoro.timer.breakAllowance
import io.github.peicheng0413.flexpomodoro.ui.formatCountdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimerLogicTest {
    private val sec = 1000L
    private val min = 60 * sec
    private val session = SessionEntity(id = "s", name = "讀書", ratio = 5, startedAt = 0)

    private fun seg(type: SegmentType, round: Int, start: Long, end: Long?, allowance: Long = 0) =
        SegmentEntity(sessionId = "s", type = type, round = round, start = start, end = end, allowanceMs = allowance)

    @Test
    fun allowanceRoundsUpToWholeSecond() {
        // 7:23 ÷ 5 = 88.6 秒 → 89 秒
        assertEquals(89 * sec, breakAllowance(7 * min + 23 * sec, 5))
        assertEquals(10 * min, breakAllowance(50 * min, 5))
        assertEquals(1 * sec, breakAllowance(1, 5))
        assertEquals(0, breakAllowance(0, 5))
        assertEquals(15 * min, breakAllowance(45 * min, 3))
    }

    @Test
    fun pausedTimeIsNotWork() {
        val s = TimerSnapshot(session, listOf(
            seg(SegmentType.WORK, 1, 0, 10 * min),
            seg(SegmentType.PAUSE, 1, 10 * min, 18 * min),
            seg(SegmentType.WORK, 1, 18 * min, null),
        ))
        assertEquals(Phase.WORKING, s.phase)
        assertEquals(15 * min, s.workMs(23 * min))
        assertEquals(3 * min, s.nextAllowanceMs(23 * min))
        // Chronometer 起點：現在 - 工作時間
        assertEquals(23 * min - 15 * min, s.workBase)
    }

    @Test
    fun breakCountsDownAndGoesNegative() {
        val s = TimerSnapshot(session, listOf(
            seg(SegmentType.WORK, 1, 0, 50 * min),
            seg(SegmentType.BREAK, 1, 50 * min, null, allowance = 10 * min),
        ))
        assertEquals(4 * min, s.breakRemainingMs(56 * min))
        assertEquals(-3 * min, s.breakRemainingMs(63 * min))
        assertEquals("-03:00", formatCountdown(s.breakRemainingMs(63 * min)))
        assertEquals("04:00", formatCountdown(s.breakRemainingMs(56 * min)))
        assertEquals("00:01", formatCountdown(1))
    }

    @Test
    fun autoEndCutsBackToOvertimeStart() {
        val threshold = 60 * min
        val breaking = TimerSnapshot(session, listOf(
            seg(SegmentType.WORK, 1, 0, 50 * min),
            seg(SegmentType.BREAK, 1, 50 * min, null, allowance = 10 * min),
        ))
        assertEquals(60 * min, breaking.autoEndCutAt())
        assertEquals(120 * min, breaking.autoEndTriggerAt(threshold))

        val paused = TimerSnapshot(session, listOf(
            seg(SegmentType.WORK, 1, 0, 20 * min),
            seg(SegmentType.PAUSE, 1, 20 * min, null),
        ))
        assertEquals(20 * min, paused.autoEndCutAt())

        val working = TimerSnapshot(session, listOf(seg(SegmentType.WORK, 1, 0, null)))
        assertNull(working.autoEndTriggerAt(threshold))
    }

    @Test
    fun summaryCountsOvertimeAndRounds() {
        val summary = SessionSummary(session.copy(endedAt = 100 * min), listOf(
            seg(SegmentType.WORK, 1, 0, 40 * min),
            seg(SegmentType.PAUSE, 1, 40 * min, 45 * min),
            seg(SegmentType.WORK, 1, 45 * min, 50 * min),
            seg(SegmentType.BREAK, 1, 50 * min, 63 * min, allowance = 9 * min),
            seg(SegmentType.WORK, 2, 63 * min, 93 * min),
            seg(SegmentType.BREAK, 2, 93 * min, 100 * min, allowance = 6 * min),
        ))
        assertEquals(75 * min, summary.workMs)
        assertEquals(5 * min, summary.pauseMs)
        assertEquals(5 * min, summary.overtimeMs) // 4 + 1
        assertEquals(2, summary.rounds)
    }
}
