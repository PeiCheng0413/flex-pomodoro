package io.github.peicheng0413.flexpomodoro.data

import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * 匯出／匯入 JSON。匯入採合併：紀錄以 id 去重，習慣同名跳過（不覆蓋現有目標）。只處理已結束的專注。
 * 檔案裡用習慣名稱串起紀錄與習慣，因為各台手機的習慣 id 不同。
 * version 1（模板時代）的檔案也能匯入：模板變成習慣、紀錄依名稱歸類。
 * version 3 加上手動標記（marks），已經有標記的那天跳過。
 */
class JsonBackup(private val db: AppDatabase) {
    private val sessions = db.sessionDao()
    private val habits = db.habitDao()

    data class ParsedGoal(val effectiveFrom: Long, val dailyMinutes: Int, val weeklyDays: Int)

    data class ParsedHabit(val name: String, val breakPercent: Int, val archived: Boolean, val goals: List<ParsedGoal>)

    /** habitName 為 null 表示未歸類。 */
    data class ParsedSession(val session: SessionEntity, val habitName: String?, val segments: List<SegmentEntity>)

    data class ParsedMark(val habitName: String, val day: Long, val done: Boolean)

    data class Parsed(val habits: List<ParsedHabit>, val sessions: List<ParsedSession>, val marks: List<ParsedMark>)

    data class ImportPlan(
        val newHabits: List<ParsedHabit>,
        val newSessions: List<ParsedSession>,
        val newMarks: List<ParsedMark>,
    )

    suspend fun export(): String {
        val segs = sessions.getEndedSegments().groupBy { it.sessionId }
        val allHabits = habits.getAll()
        val names = allHabits.associate { it.id to it.name }
        val goals = habits.getGoals().groupBy { it.habitId }
        val root = JSONObject()
            .put("format", "flex-pomodoro")
            .put("version", 3)
            .put("exportedAt", System.currentTimeMillis())
            .put("habits", JSONArray().apply {
                allHabits.forEach { h ->
                    put(JSONObject()
                        .put("name", h.name)
                        .put("breakPercent", h.breakPercent)
                        .put("archived", h.archived)
                        .put("goals", JSONArray().apply {
                            goals[h.id].orEmpty().forEach { g ->
                                put(JSONObject()
                                    .put("from", LocalDate.ofEpochDay(g.effectiveFrom).toString())
                                    .put("dailyMinutes", g.dailyMinutes)
                                    .put("weeklyDays", g.weeklyDays))
                            }
                        }))
                }
            })
            .put("marks", JSONArray().apply {
                habits.getMarks().forEach { m ->
                    names[m.habitId]?.let { name ->
                        put(JSONObject()
                            .put("habit", name)
                            .put("date", LocalDate.ofEpochDay(m.day).toString())
                            .put("done", m.done))
                    }
                }
            })
            .put("sessions", JSONArray().apply {
                sessions.getEnded().forEach { s ->
                    put(JSONObject()
                        .put("id", s.id)
                        .put("name", s.name)
                        .put("habit", s.habitId?.let { names[it] } ?: JSONObject.NULL)
                        .put("breakPercent", s.breakPercent)
                        .put("startedAt", s.startedAt)
                        .put("endedAt", s.endedAt)
                        .put("autoEnded", s.autoEnded)
                        .put("segments", JSONArray().apply {
                            segs[s.id].orEmpty().forEach { g ->
                                put(JSONObject()
                                    .put("type", g.type.name)
                                    .put("round", g.round)
                                    .put("start", g.start)
                                    .put("end", g.end)
                                    .put("allowanceMs", g.allowanceMs))
                            }
                        }))
                }
            })
        return root.toString(2)
    }

    /** 解析失敗會丟出例外，由呼叫端顯示錯誤。 */
    fun parse(text: String): Parsed {
        val root = JSONObject(text)
        require(root.optString("format") == "flex-pomodoro") { "不是彈性蕃茄鐘的匯出檔" }
        val legacy = root.optInt("version", 1) < 2
        // version 1 叫 templates，沒有目標
        val hab = root.optJSONArray(if (legacy) "templates" else "habits") ?: JSONArray()
        val ses = root.optJSONArray("sessions") ?: JSONArray()
        val marks = root.optJSONArray("marks") ?: JSONArray()
        return Parsed(
            marks = (0 until marks.length()).map { i ->
                val o = marks.getJSONObject(i)
                ParsedMark(o.getString("habit").trim(), LocalDate.parse(o.getString("date")).toEpochDay(), o.getBoolean("done"))
            },
            habits = (0 until hab.length()).map { i ->
                val o = hab.getJSONObject(i)
                val goals = o.optJSONArray("goals") ?: JSONArray()
                ParsedHabit(
                    name = o.getString("name").trim(),
                    breakPercent = percentOf(o),
                    archived = o.optBoolean("archived"),
                    goals = (0 until goals.length()).map { j ->
                        val g = goals.getJSONObject(j)
                        ParsedGoal(
                            effectiveFrom = LocalDate.parse(g.getString("from")).toEpochDay(),
                            dailyMinutes = g.getInt("dailyMinutes"),
                            weeklyDays = g.getInt("weeklyDays"),
                        )
                    },
                )
            },
            sessions = (0 until ses.length()).map { i ->
                val o = ses.getJSONObject(i)
                val id = o.getString("id")
                val name = o.optString("name").trim()
                val arr = o.getJSONArray("segments")
                ParsedSession(
                    session = SessionEntity(
                        id = id,
                        name = name,
                        breakPercent = percentOf(o),
                        startedAt = o.getLong("startedAt"),
                        endedAt = o.getLong("endedAt"),
                        autoEnded = o.optBoolean("autoEnded"),
                    ),
                    // version 1 用名稱分組：有名稱就歸到同名習慣
                    habitName = when {
                        legacy -> name.ifEmpty { null }
                        o.isNull("habit") -> null
                        else -> o.getString("habit").trim().ifEmpty { null }
                    },
                    segments = (0 until arr.length()).map { j ->
                        val g = arr.getJSONObject(j)
                        SegmentEntity(
                            sessionId = id,
                            type = SegmentType.valueOf(g.getString("type")),
                            round = g.getInt("round"),
                            start = g.getLong("start"),
                            end = g.getLong("end"),
                            allowanceMs = g.optLong("allowanceMs"),
                        )
                    },
                )
            },
        )
    }

    /** 舊版匯出檔存的是除數（ratio），換算成百分比。 */
    private fun percentOf(o: JSONObject): Int = when {
        o.has("breakPercent") -> o.getInt("breakPercent")
        else -> o.getInt("ratio").let { if (it > 0) Math.round(100f / it) else 20 }
    }

    suspend fun plan(parsed: Parsed): ImportPlan {
        val existingIds = sessions.allIds().toSet()
        val existingNames = habits.getAll().map { it.name }.toSet()
        val newSessions = parsed.sessions.filter { it.session.id !in existingIds }.distinctBy { it.session.id }
        val listed = parsed.habits.filter { it.name.isNotEmpty() }
        // 紀錄用到、但檔案裡沒列出的習慣（version 1 常見），用預設目標補建
        val referenced = newSessions.mapNotNull { it.habitName }.filter { n -> listed.none { it.name == n } }
            .map { n -> ParsedHabit(n, newSessions.first { it.habitName == n }.session.breakPercent, false, emptyList()) }
        val idToName = habits.getAll().associate { it.id to it.name }
        val existingMarks = habits.getMarks().mapNotNull { m -> idToName[m.habitId]?.let { it to m.day } }.toSet()
        // 標記指到的習慣如果兩邊都沒有，就不匯入那筆標記
        val knownNames = existingNames + listed.map { it.name } + referenced.map { it.name }
        return ImportPlan(
            newHabits = (listed + referenced).filter { it.name !in existingNames }.distinctBy { it.name },
            newSessions = newSessions,
            newMarks = parsed.marks.filter { it.habitName in knownNames && (it.habitName to it.day) !in existingMarks }
                .distinctBy { it.habitName to it.day },
        )
    }

    suspend fun apply(plan: ImportPlan) = db.withTransaction {
        plan.newHabits.forEach { h ->
            val id = habits.insert(HabitEntity(name = h.name, breakPercent = h.breakPercent, archived = h.archived))
            if (id < 0) return@forEach
            val goals = h.goals.ifEmpty { listOf(ParsedGoal(0, DEFAULT_DAILY_MINUTES, DEFAULT_WEEKLY_DAYS)) }
            goals.forEach { habits.upsertGoal(HabitGoalEntity(id, it.effectiveFrom, it.dailyMinutes, it.weeklyDays)) }
        }
        val ids = habits.getAll().associate { it.name to it.id }
        plan.newSessions.forEach { (s, habitName, segs) ->
            sessions.insertSession(s.copy(habitId = habitName?.let { ids[it] }))
            sessions.insertSegments(segs)
        }
        plan.newMarks.forEach { m ->
            ids[m.habitName]?.let { habits.upsertMark(DayMarkEntity(it, m.day, m.done)) }
        }
    }
}
