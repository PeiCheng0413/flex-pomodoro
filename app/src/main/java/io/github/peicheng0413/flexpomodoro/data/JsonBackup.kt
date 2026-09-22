package io.github.peicheng0413.flexpomodoro.data

import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject

/** 匯出／匯入 JSON。匯入採合併：紀錄以 id 去重，模板同名跳過。只處理已結束的專注。 */
class JsonBackup(private val db: AppDatabase) {
    private val sessions = db.sessionDao()
    private val templates = db.templateDao()

    data class Parsed(
        val templates: List<TemplateEntity>,
        val sessions: List<Pair<SessionEntity, List<SegmentEntity>>>,
    )

    data class ImportPlan(
        val newTemplates: List<TemplateEntity>,
        val newSessions: List<Pair<SessionEntity, List<SegmentEntity>>>,
    )

    suspend fun export(): String {
        val segs = sessions.getEndedSegments().groupBy { it.sessionId }
        val root = JSONObject()
            .put("format", "flex-pomodoro")
            .put("version", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put("templates", JSONArray().apply {
                templates.getAll().forEach { put(JSONObject().put("name", it.name).put("ratio", it.ratio)) }
            })
            .put("sessions", JSONArray().apply {
                sessions.getEnded().forEach { s ->
                    put(JSONObject()
                        .put("id", s.id)
                        .put("name", s.name)
                        .put("ratio", s.ratio)
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
        val tpl = root.optJSONArray("templates") ?: JSONArray()
        val ses = root.optJSONArray("sessions") ?: JSONArray()
        return Parsed(
            templates = (0 until tpl.length()).map { i ->
                val o = tpl.getJSONObject(i)
                TemplateEntity(name = o.getString("name").trim(), ratio = o.getInt("ratio"))
            },
            sessions = (0 until ses.length()).map { i ->
                val o = ses.getJSONObject(i)
                val id = o.getString("id")
                val arr = o.getJSONArray("segments")
                SessionEntity(
                    id = id,
                    name = o.optString("name").trim(),
                    ratio = o.getInt("ratio"),
                    startedAt = o.getLong("startedAt"),
                    endedAt = o.getLong("endedAt"),
                    autoEnded = o.optBoolean("autoEnded"),
                ) to (0 until arr.length()).map { j ->
                    val g = arr.getJSONObject(j)
                    SegmentEntity(
                        sessionId = id,
                        type = SegmentType.valueOf(g.getString("type")),
                        round = g.getInt("round"),
                        start = g.getLong("start"),
                        end = g.getLong("end"),
                        allowanceMs = g.optLong("allowanceMs"),
                    )
                }
            },
        )
    }

    suspend fun plan(parsed: Parsed): ImportPlan {
        val existingIds = sessions.allIds().toSet()
        val existingNames = templates.getAll().map { it.name }.toSet()
        return ImportPlan(
            newTemplates = parsed.templates.filter { it.name.isNotEmpty() && it.name !in existingNames }
                .distinctBy { it.name },
            newSessions = parsed.sessions.filter { it.first.id !in existingIds }.distinctBy { it.first.id },
        )
    }

    suspend fun apply(plan: ImportPlan) = db.withTransaction {
        plan.newTemplates.forEach { templates.insert(it) }
        plan.newSessions.forEach { (s, segs) ->
            sessions.insertSession(s)
            sessions.insertSegments(segs)
        }
    }
}
