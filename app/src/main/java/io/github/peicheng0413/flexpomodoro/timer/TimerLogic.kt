package io.github.peicheng0413.flexpomodoro.timer

import io.github.peicheng0413.flexpomodoro.data.SegmentEntity
import io.github.peicheng0413.flexpomodoro.data.SegmentType
import io.github.peicheng0413.flexpomodoro.data.SessionEntity

enum class Phase { WORKING, PAUSED, BREAK }

const val MINUTE_MS = 60_000L
const val OVERTIME_PING_MS = 5 * MINUTE_MS

/** 休息額度＝工作時間 ÷ 比例，無條件進位到秒。 */
fun breakAllowance(workMs: Long, ratio: Int): Long {
    if (workMs <= 0) return 0
    val msPerAllowanceSecond = ratio * 1000L
    return (workMs + msPerAllowanceSecond - 1) / msPerAllowanceSecond * 1000L
}

/** 進行中專注在某個時間點的狀態。所有時間都是 epoch 毫秒。 */
data class TimerSnapshot(
    val session: SessionEntity,
    val segments: List<SegmentEntity>,
) {
    val current: SegmentEntity = segments.last { it.end == null }
    val phase: Phase = when (current.type) {
        SegmentType.WORK -> Phase.WORKING
        SegmentType.PAUSE -> Phase.PAUSED
        SegmentType.BREAK -> Phase.BREAK
    }
    val round: Int = current.round

    /** 這一輪已經結束的工作段加總。 */
    private val closedWorkMs: Long = segments
        .filter { it.type == SegmentType.WORK && it.round == round && it.end != null }
        .sumOf { it.end!! - it.start }

    /** 這一輪的工作時間（不含暫停）。休息中則是上一段工作的總長。 */
    fun workMs(now: Long): Long =
        if (phase == Phase.WORKING) closedWorkMs + (now - current.start) else closedWorkMs

    /** 工作中讓 Chronometer 正計時用的起點。 */
    val workBase: Long get() = current.start - closedWorkMs

    val breakEndAt: Long get() = current.start + current.allowanceMs

    /** 休息剩餘時間，負數代表超時。 */
    fun breakRemainingMs(now: Long): Long = breakEndAt - now

    fun nextAllowanceMs(now: Long): Long = breakAllowance(workMs(now), session.ratio)

    /** 會觸發自動結束的時間點；工作中不會自動結束。 */
    fun autoEndTriggerAt(thresholdMs: Long): Long? = autoEndCutAt()?.plus(thresholdMs)

    /** 自動結束時，紀錄要回推到的時間點：暫停開始或超時開始的那一刻。 */
    fun autoEndCutAt(): Long? = when (phase) {
        Phase.WORKING -> null
        Phase.PAUSED -> current.start
        Phase.BREAK -> breakEndAt
    }
}

/** 已結束專注的統計摘要。 */
data class SessionSummary(
    val session: SessionEntity,
    val segments: List<SegmentEntity>,
) {
    val end: Long = session.endedAt ?: segments.maxOfOrNull { it.end ?: it.start } ?: session.startedAt
    private fun SegmentEntity.duration() = (end ?: this@SessionSummary.end) - start
    val workMs: Long = segments.filter { it.type == SegmentType.WORK }.sumOf { it.duration() }
    val pauseMs: Long = segments.filter { it.type == SegmentType.PAUSE }.sumOf { it.duration() }
    val breakMs: Long = segments.filter { it.type == SegmentType.BREAK }.sumOf { it.duration() }
    val overtimeMs: Long = segments.filter { it.type == SegmentType.BREAK }
        .sumOf { (it.duration() - it.allowanceMs).coerceAtLeast(0) }
    val rounds: Int = segments.filter { it.type == SegmentType.WORK }.map { it.round }.distinct().size
}

/** 統計分組：名稱去除前後空白後完全相同才算同一組，空白歸為「未命名」。 */
data class NameGroup(
    val name: String,
    val sessions: List<SessionSummary>,
) {
    val workMs = sessions.sumOf { it.workMs }
    val overtimeMs = sessions.sumOf { it.overtimeMs }
    val count = sessions.size
}

fun groupByName(sessions: List<SessionSummary>): List<NameGroup> =
    sessions.groupBy { it.session.name.trim() }
        .map { (name, list) -> NameGroup(name, list.sortedByDescending { it.session.startedAt }) }
        .sortedByDescending { it.workMs }
