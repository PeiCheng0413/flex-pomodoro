package io.github.peicheng0413.flexpomodoro.data

import androidx.room.withTransaction
import io.github.peicheng0413.flexpomodoro.habit.Goal
import io.github.peicheng0413.flexpomodoro.habit.GoalHistory
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

class HabitRepository(private val db: AppDatabase) {
    private val dao = db.habitDao()
    private val sessions = db.sessionDao()

    /** 全部習慣，包含已封存的。 */
    val habits: Flow<List<HabitEntity>> = dao.observeAll()
    val goals: Flow<List<HabitGoalEntity>> = dao.observeGoals()

    /** 新增習慣，目標從最早的日子就生效。同名已存在時回傳 null。 */
    suspend fun create(name: String, breakPercent: Int, goal: Goal): HabitEntity? = db.withTransaction {
        val habit = HabitEntity(name = name.trim(), breakPercent = breakPercent)
        val id = dao.insert(habit)
        if (id < 0) return@withTransaction null
        dao.upsertGoal(HabitGoalEntity(id, 0, goal.dailyMinutes, goal.weeklyDays))
        habit.copy(id = id)
    }

    /**
     * 儲存習慣設定。目標有變時，預設從今天起生效、過去照舊；applyToPast 則整段歷史都改用新目標。
     * 名稱和別的習慣重複時不儲存，回傳 false。
     */
    suspend fun update(habit: HabitEntity, goal: Goal, applyToPast: Boolean, today: LocalDate = LocalDate.now()): Boolean =
        db.withTransaction {
            val named = habit.copy(name = habit.name.trim())
            val clash = dao.findByName(named.name)
            if (clash != null && clash.id != named.id) return@withTransaction false
            dao.update(named)
            val rows = dao.getGoals().filter { it.habitId == named.id }
            when {
                applyToPast -> {
                    dao.deleteGoals(named.id)
                    dao.upsertGoal(HabitGoalEntity(named.id, 0, goal.dailyMinutes, goal.weeklyDays))
                }
                GoalHistory(rows).at(today) != goal ->
                    dao.upsertGoal(HabitGoalEntity(named.id, today.toEpochDay(), goal.dailyMinutes, goal.weeklyDays))
            }
            true
        }

    suspend fun setArchived(habit: HabitEntity, archived: Boolean) = dao.update(habit.copy(archived = archived))

    /** 把 from 的紀錄全部併進 into，然後移除已經空掉的 from。 */
    suspend fun merge(from: Long, into: Long) = db.withTransaction {
        if (from == into) return@withTransaction
        sessions.moveAll(from, into)
        dao.deleteGoals(from)
        dao.delete(from)
    }
}
