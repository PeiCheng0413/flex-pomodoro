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

/** 一次專注。endedAt 為 null 表示進行中（同時最多一筆）。 */
@Entity(tableName = "sessions", indices = [Index("name"), Index("startedAt")])
data class SessionEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** 休息額度佔工作時間的百分比。 */
    val breakPercent: Int,
    val startedAt: Long,
    val endedAt: Long? = null,
    val autoEnded: Boolean = false,
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

@Entity(tableName = "templates", indices = [Index(value = ["name"], unique = true)])
data class TemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val breakPercent: Int,
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

    /** 歷史上用過的名稱，最近用的排前面，給名稱欄位自動提示用。 */
    @Query("SELECT name FROM sessions WHERE name != '' GROUP BY name ORDER BY MAX(startedAt) DESC")
    fun observeNames(): Flow<List<String>>

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

    @Query("UPDATE sessions SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("UPDATE sessions SET name = :newName WHERE name = :oldName")
    suspend fun renameGroup(oldName: String, newName: String)

    @Query("DELETE FROM segments WHERE sessionId = :id")
    suspend fun deleteSegments(id: String)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: String)
}

@Dao
interface TemplateDao {
    @Query("SELECT * FROM templates ORDER BY id")
    fun observeAll(): Flow<List<TemplateEntity>>

    @Query("SELECT * FROM templates ORDER BY id")
    suspend fun getAll(): List<TemplateEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(template: TemplateEntity): Long

    @Update(onConflict = OnConflictStrategy.IGNORE)
    suspend fun update(template: TemplateEntity)

    @Query("DELETE FROM templates WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(
    entities = [SessionEntity::class, SegmentEntity::class, TemplateEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun templateDao(): TemplateDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "flex-pomodoro.db")
                .addCallback(object : Callback() {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        // 預設模板
                        listOf("讀書", "運動", "工作").forEach {
                            db.execSQL("INSERT INTO templates (name, breakPercent) VALUES (?, 20)", arrayOf(it))
                        }
                    }
                })
                .addMigrations(MIGRATION_1_2)
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
    }
}
