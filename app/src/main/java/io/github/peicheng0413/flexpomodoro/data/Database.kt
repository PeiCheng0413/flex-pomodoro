package io.github.peicheng0413.flexpomodoro.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

enum class SegmentType { WORK, PAUSE, BREAK }

/**
 * 一次專注。endedAt 為 null 表示進行中（同時最多一筆）。
 * habitId 為 null 表示單純計時、還沒歸類；name 是開始當下的習慣名稱，只當快照與匯出參考，畫面一律用習慣的名稱。
 */
@Entity(tableName = "sessions", indices = [Index("name"), Index("startedAt"), Index("habitId")])
data class SessionEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** 休息額度佔工作時間的百分比。 */
    val breakPercent: Int,
    val startedAt: Long,
    val endedAt: Long? = null,
    val autoEnded: Boolean = false,
    val habitId: Long? = null,
)

/** 專注中的一段時間。end 為 null 表示正在進行的那一段。BREAK 會記下當時算出的休息額度。 */
@Entity(tableName = "segments", indices = [Index("sessionId")])
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val type: SegmentType,
    val round: Int,
    val start: Long,
    val end: Long? = null,
    val allowanceMs: Long = 0,
)

/** 習慣（原本的模板）。不能刪除，只能封存；封存後不出現在開始清單，紀錄照留。 */
@Entity(tableName = "habits", indices = [Index(value = ["name"], unique = true)])
data class HabitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val breakPercent: Int,
    val archived: Boolean = false,
)

/**
 * 習慣目標的變更紀錄：從 effectiveFrom（LocalDate.toEpochDay）那天起，每天要做 dailyMinutes 分鐘、每週要達成 weeklyDays 天。
 * 每一天依當時生效的目標判定，改目標不會改寫過去。
 */
@Entity(tableName = "habit_goals", primaryKeys = ["habitId", "effectiveFrom"])
data class HabitGoalEntity(
    val habitId: Long,
    val effectiveFrom: Long,
    val dailyMinutes: Int,
    val weeklyDays: Int,
)

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE endedAt IS NULL LIMIT 1")
    fun observeActive(): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE endedAt IS NULL LIMIT 1")
    suspend fun getActive(): SessionEntity?

    @Query("SELECT * FROM segments WHERE sessionId = :sessionId ORDER BY start, id")
    fun observeSegments(sessionId: String): Flow<List<SegmentEntity>>

    @Query("SELECT * FROM segments WHERE sessionId = :sessionId ORDER BY start, id")
    suspend fun getSegments(sessionId: String): List<SegmentEntity>

    @Query("SELECT * FROM sessions WHERE endedAt IS NOT NULL ORDER BY startedAt DESC")
    fun observeEnded(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM segments WHERE sessionId IN (SELECT id FROM sessions WHERE endedAt IS NOT NULL) ORDER BY start, id")
    fun observeEndedSegments(): Flow<List<SegmentEntity>>

    @Query("SELECT * FROM sessions WHERE endedAt IS NOT NULL ORDER BY startedAt")
    suspend fun getEnded(): List<SessionEntity>

    @Query("SELECT * FROM segments WHERE sessionId IN (SELECT id FROM sessions WHERE endedAt IS NOT NULL) ORDER BY start, id")
    suspend fun getEndedSegments(): List<SegmentEntity>

    @Query("SELECT id FROM sessions")
    suspend fun allIds(): List<String>

    @Insert
    suspend fun insertSession(session: SessionEntity)

    @Update
    suspend fun updateSession(session: SessionEntity)

    @Insert
    suspend fun insertSegment(segment: SegmentEntity): Long

    @Insert
    suspend fun insertSegments(segments: List<SegmentEntity>)

    @Update
    suspend fun updateSegment(segment: SegmentEntity)

    /** 把一筆紀錄歸到某個習慣，null 表示改回未歸類。 */
    @Query("UPDATE sessions SET habitId = :habitId WHERE id = :id")
    suspend fun assign(id: String, habitId: Long?)

    /** 合併習慣：from 的紀錄全部移到 into。 */
    @Query("UPDATE sessions SET habitId = :into WHERE habitId = :from")
    suspend fun moveAll(from: Long, into: Long)

    @Query("DELETE FROM segments WHERE sessionId = :id")
    suspend fun deleteSegments(id: String)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: String)
}

@Dao
interface HabitDao {
    @Query("SELECT * FROM habits ORDER BY id")
    fun observeAll(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habits ORDER BY id")
    suspend fun getAll(): List<HabitEntity>

    @Query("SELECT * FROM habits WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): HabitEntity?

    /** 同名時回傳 -1。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(habit: HabitEntity): Long

    @Update
    suspend fun update(habit: HabitEntity)

    /** 只給合併用：被併掉的習慣已經沒有紀錄了。 */
    @Query("DELETE FROM habits WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM habit_goals ORDER BY habitId, effectiveFrom")
    fun observeGoals(): Flow<List<HabitGoalEntity>>

    @Query("SELECT * FROM habit_goals ORDER BY habitId, effectiveFrom")
    suspend fun getGoals(): List<HabitGoalEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGoal(goal: HabitGoalEntity)

    @Query("DELETE FROM habit_goals WHERE habitId = :habitId")
    suspend fun deleteGoals(habitId: Long)
}

@Database(
    entities = [SessionEntity::class, SegmentEntity::class, HabitEntity::class, HabitGoalEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun habitDao(): HabitDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "flex-pomodoro.db")
                .addCallback(object : Callback() {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        // 預設習慣
                        listOf("讀書", "運動", "工作").forEach {
                            db.execSQL("INSERT INTO habits (name, breakPercent, archived) VALUES (?, 20, 0)", arrayOf(it))
                        }
                        insertDefaultGoals(db)
                    }
                })
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()

        /** 休息比例從「除數」改成「百分比」：÷5 → 20%。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                listOf("sessions", "templates").forEach { table ->
                    db.execSQL("ALTER TABLE $table RENAME COLUMN ratio TO breakPercent")
                    db.execSQL("UPDATE $table SET breakPercent = MAX(1, CAST(100.0 / breakPercent + 0.5 AS INTEGER)) WHERE breakPercent > 0")
                }
            }
        }

        /**
         * 模板變成習慣：
         * - templates 改名為 habits，加上封存旗標；新增目標變更紀錄表。
         * - 歷史上用過、但沒存成模板的名稱，自動建成習慣（休息比例取最近一次用的）。
         * - 舊紀錄依名稱接上習慣；空白名稱的紀錄留在「未歸類」。
         * - 所有習慣先給預設目標，從 1970-01-01 起生效，之後可以選擇套用到過去。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE templates RENAME TO habits")
                db.execSQL("ALTER TABLE habits ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
                db.execSQL("DROP INDEX IF EXISTS index_templates_name")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_habits_name` ON `habits` (`name`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `habit_goals` (`habitId` INTEGER NOT NULL, `effectiveFrom` INTEGER NOT NULL, " +
                        "`dailyMinutes` INTEGER NOT NULL, `weeklyDays` INTEGER NOT NULL, PRIMARY KEY(`habitId`, `effectiveFrom`))"
                )
                db.execSQL("ALTER TABLE sessions ADD COLUMN habitId INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sessions_habitId` ON `sessions` (`habitId`)")
                db.execSQL(
                    "INSERT INTO habits (name, breakPercent, archived) " +
                        "SELECT s.name, s.breakPercent, 0 FROM sessions s " +
                        "WHERE s.name != '' AND s.name NOT IN (SELECT name FROM habits) " +
                        "AND s.startedAt = (SELECT MAX(startedAt) FROM sessions WHERE name = s.name) " +
                        "GROUP BY s.name"
                )
                db.execSQL("UPDATE sessions SET habitId = (SELECT id FROM habits WHERE habits.name = sessions.name) WHERE name != ''")
                insertDefaultGoals(db)
            }
        }

        /** 還沒有任何目標的習慣，補上預設目標。 */
        private fun insertDefaultGoals(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                "INSERT INTO habit_goals (habitId, effectiveFrom, dailyMinutes, weeklyDays) " +
                    "SELECT id, 0, $DEFAULT_DAILY_MINUTES, $DEFAULT_WEEKLY_DAYS FROM habits " +
                    "WHERE id NOT IN (SELECT habitId FROM habit_goals)"
            )
        }
    }
}

const val DEFAULT_DAILY_MINUTES = 30
const val DEFAULT_WEEKLY_DAYS = 5
