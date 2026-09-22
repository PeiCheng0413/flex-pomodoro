package io.github.peicheng0413.flexpomodoro.data

import androidx.room.withTransaction
import io.github.peicheng0413.flexpomodoro.timer.MINUTE_MS
import io.github.peicheng0413.flexpomodoro.timer.Phase
import io.github.peicheng0413.flexpomodoro.timer.SessionSummary
import io.github.peicheng0413.flexpomodoro.timer.TimerSnapshot
import io.github.peicheng0413.flexpomodoro.timer.breakAllowance
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class SessionRepository(
    private val db: AppDatabase,
    private val settings: Settings,
) {
    private val dao = db.sessionDao()
    private val mutex = Mutex()

    @OptIn(ExperimentalCoroutinesApi::class)
    val active: Flow<TimerSnapshot?> = dao.observeActive().flatMapLatest { session ->
        if (session == null) flowOf(null)
        else dao.observeSegments(session.id).map { segs ->
            if (segs.any { it.end == null }) TimerSnapshot(session, segs) else null
        }
    }

    val ended: Flow<List<SessionSummary>> =
        combine(dao.observeEnded(), dao.observeEndedSegments()) { sessions, segments ->
            val bySession = segments.groupBy { it.sessionId }
            sessions.map { SessionSummary(it, bySession[it.id].orEmpty()) }
        }

    val names: Flow<List<String>> = dao.observeNames()

    suspend fun snapshot(): TimerSnapshot? {
        val session = dao.getActive() ?: return null
        val segs = dao.getSegments(session.id)
        if (segs.none { it.end == null }) return null
        return TimerSnapshot(session, segs)
    }

    private suspend fun <T> locked(block: suspend () -> T): T =
        mutex.withLock { db.withTransaction { block() } }

    suspend fun start(name: String, ratio: Int, now: Long = System.currentTimeMillis()) = locked {
        if (dao.getActive() != null) return@locked
        val id = UUID.randomUUID().toString()
        dao.insertSession(SessionEntity(id = id, name = name.trim(), ratio = ratio, startedAt = now))
        dao.insertSegment(SegmentEntity(sessionId = id, type = SegmentType.WORK, round = 1, start = now))
    }

    suspend fun pause(now: Long = System.currentTimeMillis()) = transition(Phase.WORKING) { s ->
        close(s, now)
        open(s, SegmentType.PAUSE, s.round, now)
    }

    suspend fun resume(now: Long = System.currentTimeMillis()) = transition(Phase.PAUSED) { s ->
        close(s, now)
        open(s, SegmentType.WORK, s.round, now)
    }

    suspend fun takeBreak(now: Long = System.currentTimeMillis()) =
        transition(Phase.WORKING, Phase.PAUSED) { s ->
            val allowance = breakAllowance(s.workMs(now), s.session.ratio)
            close(s, now)
            open(s, SegmentType.BREAK, s.round, now, allowance)
        }

    suspend fun startWork(now: Long = System.currentTimeMillis()) = transition(Phase.BREAK) { s ->
        close(s, now)
        open(s, SegmentType.WORK, s.round + 1, now)
    }

    /** 工作 ↔ 暫停（橫向單擊）。 */
    suspend fun togglePause(now: Long = System.currentTimeMillis()) {
        when (snapshot()?.phase) {
            Phase.WORKING -> pause(now)
            Phase.PAUSED -> resume(now)
            else -> Unit
        }
    }

    /** 進入休息／回到工作（橫向長按）。 */
    suspend fun toggleBreak(now: Long = System.currentTimeMillis()) {
        when (snapshot()?.phase) {
            Phase.WORKING, Phase.PAUSED -> takeBreak(now)
            Phase.BREAK -> startWork(now)
            null -> Unit
        }
    }

    suspend fun end(now: Long = System.currentTimeMillis()) = locked {
        val s = snapshotLocked() ?: return@locked
        finish(s, now, auto = false)
    }

    /**
     * 暫停或休息超時超過門檻就自動結束，紀錄回推到暫停／超時開始的那一刻。
     * 回傳被自動結束的專注摘要，沒有結束則回傳 null。
     */
    suspend fun checkAutoEnd(now: Long = System.currentTimeMillis()): SessionSummary? = locked {
        val s = snapshotLocked() ?: return@locked null
        val threshold = settings.autoEndMinutes.value * MINUTE_MS
        val trigger = s.autoEndTriggerAt(threshold) ?: return@locked null
        if (now < trigger) return@locked null
        finish(s, s.autoEndCutAt()!!, auto = true)
    }

    private suspend fun finish(s: TimerSnapshot, at: Long, auto: Boolean): SessionSummary? {
        dao.updateSegment(s.current.copy(end = at))
        val session = s.session.copy(endedAt = at, autoEnded = auto)
        dao.updateSession(session)
        val summary = SessionSummary(session, dao.getSegments(session.id))
        // 不到一秒的專注（例如誤按開始）不留紀錄
        if (summary.workMs < 1000) {
            deleteLocked(session.id)
            return null
        }
        return summary
    }

    suspend fun rename(id: String, name: String) = locked { dao.rename(id, name.trim()) }

    suspend fun renameGroup(oldName: String, newName: String) = locked {
        dao.renameGroup(oldName, newName.trim())
    }

    suspend fun delete(id: String) = locked { deleteLocked(id) }

    private suspend fun deleteLocked(id: String) {
        dao.deleteSegments(id)
        dao.deleteSession(id)
    }

    // --- 內部工具 ---

    private suspend fun snapshotLocked(): TimerSnapshot? = snapshot()

    private suspend fun transition(vararg from: Phase, block: suspend (TimerSnapshot) -> Unit) = locked {
        val s = snapshotLocked() ?: return@locked
        if (s.phase in from) block(s)
    }

    private suspend fun close(s: TimerSnapshot, now: Long) {
        dao.updateSegment(s.current.copy(end = now.coerceAtLeast(s.current.start)))
    }

    private suspend fun open(s: TimerSnapshot, type: SegmentType, round: Int, now: Long, allowance: Long = 0) {
        dao.insertSegment(
            SegmentEntity(sessionId = s.session.id, type = type, round = round, start = now, allowanceMs = allowance)
        )
    }
}
