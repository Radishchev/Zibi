package com.zibi.app.tools

import com.zibi.app.data.FactHistory
import com.zibi.app.data.ProfileDao
import com.zibi.app.data.ProfileFact
import com.zibi.app.data.TimetableEntry
import com.zibi.app.privacy.PrivacyVault
import com.zibi.app.protocol.Risk
import com.zibi.app.protocol.ToolSpec
import com.zibi.app.protocol.ZibiJson
import com.zibi.app.protocol.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * What Zibi knows about the user, and how that knowledge changes over time.
 *  - Facts are only saved with evidence: the user's own words (the server checks the quote is real).
 *  - A changed fact keeps its old value in history; a finished one is archived (kept, no longer sent to the AI).
 *  - Sensitive values are replaced by placeholders before saving; the real text lives encrypted in the vault.
 */

val WEEK_DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
val FACT_CATEGORIES = listOf("routine", "study", "preference", "people", "other")

/** "fri", "Friday", "FRIDAY" -> "Friday". */
fun normalizeDay(text: String): String? {
    val t = text.trim().lowercase()
    if (t.length < 3) return null
    return WEEK_DAYS.firstOrNull {
        val d = it.lowercase()
        d.startsWith(t) || (t.length <= 5 && d.take(3) == t.take(3))
    }
}

/** "9:05", "09.05" -> "09:05". Null if it isn't a valid 24h time. */
fun normalizeTime(text: String): String? {
    val m = Regex("^(\\d{1,2})[:.](\\d{2})$").matchEntire(text.trim()) ?: return null
    val h = m.groupValues[1].toInt()
    val min = m.groupValues[2].toInt()
    if (h > 23 || min > 59) return null
    return String.format(Locale.ROOT, "%02d:%02d", h, min)
}

fun normalizeKey(text: String?): String? =
    text?.lowercase()?.replace(Regex("[^a-z0-9]+"), "_")?.trim('_')?.take(40)?.takeIf { it.isNotEmpty() }

private fun dateOf(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toString()

private fun parseToken(token: String): JsonObject? =
    runCatching { ZibiJson.parseToJsonElement(token).jsonObject }.getOrNull()

class RememberFactTool(private val dao: ProfileDao, private val vault: PrivacyVault) : ZibiTool {
    override val spec = ToolSpec(
        name = "remember_fact",
        description = "Save or update a lasting fact the user STATED about themselves (routine, studies, preferences, " +
            "people). 'evidence' must quote the user's own words, copied exactly; facts they haven't said are " +
            "rejected. Use a short stable snake_case key (wake_time, alarm_style). Saving an existing key updates it " +
            "and keeps the old value in history. private=true for sensitive things (health, money, IDs). " +
            "valid_until (YYYY-MM-DD) when it stops being true, e.g. the end of a semester.",
        parameters = schema(
            """
            {"type":"object",
             "properties":{
               "category":{"type":"string","enum":["routine","study","preference","people","other"]},
               "key":{"type":"string"},
               "value":{"type":"string"},
               "evidence":{"type":"string","description":"the user's own words, copied exactly"},
               "private":{"type":"boolean"},
               "valid_until":{"type":"string","description":"YYYY-MM-DD"}},
             "required":["key","value","evidence"],
             "additionalProperties":false}
            """.trimIndent()
        ),
        risk = Risk.WRITE,
        grounded = listOf("evidence"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val key = normalizeKey(args.string("key")) ?: return ToolResult.fail("key is required")
        val raw = args.string("value")?.trim()?.take(300)?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.fail("value is required")
        val evidence = args.string("evidence")?.trim()?.take(300)?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.fail("evidence is required: quote the user's own words that state this fact")
        val category = args.string("category")?.takeIf { it in FACT_CATEGORIES } ?: "other"
        val wantPrivate = (args["private"] as? JsonPrimitive)?.booleanOrNull == true
        val validUntilText = args.string("valid_until")
        val validUntil: Long? = if (validUntilText == null) null else {
            runCatching {
                LocalDate.parse(validUntilText).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull() ?: return ToolResult.fail("valid_until must be YYYY-MM-DD")
        }

        val now = System.currentTimeMillis()
        val stored: String
        val isPrivate: Boolean
        if (wantPrivate) {
            stored = vault.protect(raw)
            isPrivate = true
        } else {
            val r = vault.redact(raw) // phone numbers, e-mails, ... become placeholders; the real text goes to the vault
            stored = r.text
            isPrivate = r.count > 0
        }
        val shown = if (isPrivate) "(hidden from the AI)" else stored

        val existing = dao.findFact(key)
        if (existing == null) {
            dao.insertFact(
                ProfileFact(
                    category = category, factKey = key, value = stored, source = "user", updatedAt = now,
                    isPrivate = isPrivate, evidence = evidence, validUntil = validUntil, confirmedAt = now,
                )
            )
            return ToolResult(
                ok = true,
                summary = "Remembered: $key = $shown",
                result = buildJsonObject { put("status", "saved"); put("key", key) },
                undoToken = buildJsonObject { put("k", key); put("new", true) }.toString(),
            )
        }
        if (existing.value == stored && existing.category == category && existing.status == "active") {
            dao.updateFact(existing.copy(confirmedAt = now, evidence = evidence)) // still true: refresh "last confirmed"
            return ToolResult(true, "Already knew: $key", buildJsonObject { put("status", "confirmed") })
        }
        val historyId = dao.insertHistory(
            FactHistory(
                factKey = key, category = existing.category, value = existing.value, isPrivate = existing.isPrivate,
                validFrom = existing.updatedAt, validTo = now, reason = "updated",
            )
        )
        dao.updateFact(
            existing.copy(
                category = category, value = stored, isPrivate = isPrivate, evidence = evidence, updatedAt = now,
                confirmedAt = now, status = "active", archivedAt = null, validUntil = validUntil,
            )
        )
        return ToolResult(
            ok = true,
            summary = "Updated: $key = $shown",
            result = buildJsonObject { put("status", "updated"); put("key", key) },
            undoToken = buildJsonObject {
                put("k", key); put("c", existing.category); put("v", existing.value); put("p", existing.isPrivate)
                put("s", existing.status); put("h", historyId); put("vu", existing.validUntil)
                put("ca", existing.confirmedAt); put("ev", existing.evidence)
            }.toString(),
        )
    }

    override suspend fun undo(token: String): Boolean {
        val obj = parseToken(token) ?: return false
        val key = obj.string("k") ?: return false
        if (obj["new"] != null) return dao.deleteFactByKey(key) > 0
        val existing = dao.findFact(key) ?: return false
        dao.updateFact(
            existing.copy(
                category = obj.string("c") ?: "other",
                value = obj.string("v") ?: return false,
                isPrivate = (obj["p"] as? JsonPrimitive)?.booleanOrNull == true,
                status = obj.string("s") ?: "active",
                validUntil = (obj["vu"] as? JsonPrimitive)?.longOrNull,
                confirmedAt = (obj["ca"] as? JsonPrimitive)?.longOrNull ?: existing.confirmedAt,
                evidence = obj.string("ev"),
                archivedAt = null,
                updatedAt = System.currentTimeMillis(),
            )
        )
        (obj["h"] as? JsonPrimitive)?.longOrNull?.let { dao.deleteHistoryById(it) }
        return true
    }
}

class ForgetFactTool(private val dao: ProfileDao) : ZibiTool {
    override val spec = ToolSpec(
        name = "forget_fact",
        description = "Permanently delete a saved fact about the user, and its history, by key. Use when the user asks " +
            "to forget something. To keep something but stop using it, use archive_facts instead.",
        parameters = schema(
            """
            {"type":"object","properties":{"key":{"type":"string"}},"required":["key"],"additionalProperties":false}
            """.trimIndent()
        ),
        risk = Risk.WRITE,
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val key = normalizeKey(args.string("key")) ?: return ToolResult.fail("key is required")
        val existing = dao.findFact(key) ?: return ToolResult.fail("I don't have anything saved as \"$key\"")
        dao.deleteFactByKey(key)
        dao.deleteHistoryByKey(key)
        return ToolResult(
            ok = true,
            summary = "Forgot: $key",
            result = buildJsonObject { put("status", "deleted") },
            undoToken = buildJsonObject {
                put("k", key); put("c", existing.category); put("v", existing.value); put("p", existing.isPrivate)
                put("s", existing.status)
            }.toString(),
        )
    }

    override suspend fun undo(token: String): Boolean {
        val obj = parseToken(token) ?: return false
        val key = obj.string("k") ?: return false
        if (dao.findFact(key) != null) return false
        val now = System.currentTimeMillis()
        dao.insertFact(
            ProfileFact(
                category = obj.string("c") ?: "other", factKey = key, value = obj.string("v") ?: return false,
                source = "user", updatedAt = now, status = obj.string("s") ?: "active",
                isPrivate = (obj["p"] as? JsonPrimitive)?.booleanOrNull == true, confirmedAt = now,
            )
        )
        return true
    }
}

class ArchiveFactsTool(private val dao: ProfileDao) : ZibiTool {
    override val spec = ToolSpec(
        name = "archive_facts",
        description = "Move facts that are no longer current to the archive (a finished semester's courses, an old " +
            "job). They are kept and can be found with recall_memory, but are no longer shown to you every turn.",
        parameters = schema(
            """
            {"type":"object",
             "properties":{"keys":{"type":"array","items":{"type":"string"}},"reason":{"type":"string"}},
             "required":["keys"],
             "additionalProperties":false}
            """.trimIndent()
        ),
        risk = Risk.WRITE,
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val keys = (args["keys"] as? JsonArray)?.mapNotNull { normalizeKey((it as? JsonPrimitive)?.contentOrNull) }.orEmpty()
        if (keys.isEmpty()) return ToolResult.fail("keys must list at least one fact key")
        val now = System.currentTimeMillis()
        val archived = mutableListOf<String>()
        val missing = mutableListOf<String>()
        for (key in keys.distinct()) {
            val f = dao.findFact(key)
            if (f == null || f.status != "active") { missing += key; continue }
            dao.updateFact(f.copy(status = "archived", archivedAt = now))
            archived += key
        }
        if (archived.isEmpty()) return ToolResult.fail("none of those are active facts: ${missing.joinToString()}")
        return ToolResult(
            ok = true,
            summary = "Archived: ${archived.joinToString()}",
            result = buildJsonObject {
                put("archived", buildJsonArray { archived.forEach { add(JsonPrimitive(it)) } })
                put("not_found", buildJsonArray { missing.forEach { add(JsonPrimitive(it)) } })
            },
            undoToken = buildJsonArray { archived.forEach { add(JsonPrimitive(it)) } }.toString(),
        )
    }

    override suspend fun undo(token: String): Boolean {
        val arr = runCatching { ZibiJson.parseToJsonElement(token) as? JsonArray }.getOrNull() ?: return false
        arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.forEach { key ->
            dao.findFact(key)?.let { dao.updateFact(it.copy(status = "active", archivedAt = null)) }
        }
        return true
    }
}

class RecallMemoryTool(private val dao: ProfileDao) : ZibiTool {
    override val spec = ToolSpec(
        name = "recall_memory",
        description = "Search what you know about the user's PAST: archived facts and earlier values of facts that " +
            "changed (old job, last semester). Use when the user asks about earlier times. Current facts are " +
            "already shown to you.",
        parameters = schema(
            """
            {"type":"object","properties":{"query":{"type":"string","description":"a few keywords"}},"required":["query"],"additionalProperties":false}
            """.trimIndent()
        ),
        risk = Risk.READ,
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val words = Regex("[\\p{L}\\p{N}]{3,}").findAll((args.string("query") ?: "").lowercase())
            .map { it.value }.distinct().take(6).toList()
        if (words.isEmpty()) return ToolResult.fail("query is required")
        val facts = linkedMapOf<Long, ProfileFact>()
        val history = linkedMapOf<Long, FactHistory>()
        for (w in words) {
            dao.searchFacts("%$w%").filter { it.status == "archived" }.forEach { facts[it.id] = it }
            dao.searchHistory("%$w%").forEach { history[it.id] = it }
        }
        val items = buildJsonArray {
            facts.values.take(8).forEach {
                add(buildJsonObject {
                    put("key", it.factKey); put("value", it.value); put("status", "archived")
                    put("until", dateOf(it.archivedAt ?: it.updatedAt))
                })
            }
            history.values.take(8).forEach {
                add(buildJsonObject {
                    put("key", it.factKey); put("value", it.value); put("status", "previous value")
                    put("until", dateOf(it.validTo))
                })
            }
        }
        return ToolResult(
            ok = true,
            summary = "Looked through past memories (${facts.size + history.size} found)",
            result = buildJsonObject { put("matches", items) },
        )
    }
}

class UpdateTimetableTool(private val dao: ProfileDao) : ZibiTool {
    override val spec = ToolSpec(
        name = "update_timetable",
        description = "Save the user's weekly class timetable. mode 'replace' = the entries are the WHOLE timetable " +
            "(the previous one is archived, not lost); mode 'add' (default) = extra classes added to the existing one.",
        parameters = schema(
            """
            {"type":"object",
             "properties":{
               "mode":{"type":"string","enum":["add","replace"]},
               "entries":{"type":"array","items":{"type":"object","properties":{
                 "day":{"type":"string"},
                 "start":{"type":"string","description":"HH:MM 24h"},
                 "end":{"type":"string","description":"HH:MM 24h"},
                 "title":{"type":"string"},
                 "location":{"type":"string"}},
                 "required":["day","start","end","title"]}}},
             "required":["entries"],
             "additionalProperties":false}
            """.trimIndent()
        ),
        risk = Risk.WRITE,
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val raw = args["entries"] as? JsonArray ?: return ToolResult.fail("entries must be a list")
        if (raw.isEmpty()) return ToolResult.fail("entries must not be empty")
        val parsed = mutableListOf<TimetableEntry>()
        val problems = mutableListOf<String>()
        raw.forEachIndexed { i, element ->
            val o = element as? JsonObject
            if (o == null) {
                problems += "entry ${i + 1} is not an object"
                return@forEachIndexed
            }
            val day = o.string("day")?.let(::normalizeDay)
            val start = o.string("start")?.let(::normalizeTime)
            val end = o.string("end")?.let(::normalizeTime)
            val title = o.string("title")?.trim()
            when {
                day == null -> problems += "entry ${i + 1}: unknown day"
                start == null || end == null -> problems += "entry ${i + 1}: times must be HH:MM"
                end <= start -> problems += "entry ${i + 1}: end must be after start"
                title.isNullOrEmpty() -> problems += "entry ${i + 1}: title is required"
                else -> parsed += TimetableEntry(
                    day = day, startTime = start, endTime = end, title = title,
                    location = o.string("location")?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
        }
        if (problems.isNotEmpty()) return ToolResult.fail("nothing saved. " + problems.joinToString("; "))

        val replace = args.string("mode") == "replace"
        return if (replace) {
            val previous = dao.timetable()
            if (previous.isNotEmpty()) archivePreviousTimetable(previous) // last semester's timetable is kept, not lost
            dao.clearTimetable()
            dao.insertTimetable(parsed)
            ToolResult(
                ok = true,
                summary = "Timetable saved: ${parsed.size} classes",
                result = buildJsonObject { put("status", "replaced"); put("classes", parsed.size) },
                undoToken = buildJsonObject { put("restore", buildJsonArray { previous.forEach { add(entryJson(it)) } }) }.toString(),
            )
        } else {
            val ids = dao.insertTimetable(parsed)
            ToolResult(
                ok = true,
                summary = "Added ${parsed.size} class(es) to your timetable",
                result = buildJsonObject { put("status", "added"); put("classes", parsed.size) },
                undoToken = buildJsonObject { put("remove", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } }) }.toString(),
            )
        }
    }

    private suspend fun archivePreviousTimetable(previous: List<TimetableEntry>) {
        val now = System.currentTimeMillis()
        val base = "timetable_" + LocalDate.now()
        var key = base
        var n = 2
        while (dao.findFact(key) != null) key = base + "_" + n++
        val text = previous.sortedWith(compareBy({ WEEK_DAYS.indexOf(it.day) }, { it.startTime }))
            .joinToString("; ") { "${it.day.take(3)} ${it.startTime}-${it.endTime} ${it.title}" }
        dao.insertFact(
            ProfileFact(
                category = "study", factKey = key, value = text.take(500), source = "user", updatedAt = now,
                status = "archived", confirmedAt = now, archivedAt = now,
            )
        )
    }

    override suspend fun undo(token: String): Boolean {
        val obj = parseToken(token) ?: return false
        (obj["remove"] as? JsonArray)?.let { arr ->
            dao.deleteTimetableEntries(arr.mapNotNull { (it as? JsonPrimitive)?.longOrNull })
            return true
        }
        (obj["restore"] as? JsonArray)?.let { arr ->
            dao.clearTimetable()
            dao.insertTimetable(arr.mapNotNull { entryFromJson(it) })
            return true
        }
        return false
    }

    private fun entryJson(e: TimetableEntry): JsonObject = buildJsonObject {
        put("day", e.day); put("start", e.startTime); put("end", e.endTime); put("title", e.title)
        e.location?.let { put("location", it) }
    }

    private fun entryFromJson(el: JsonElement): TimetableEntry? {
        val o = el as? JsonObject ?: return null
        return TimetableEntry(
            day = o.string("day") ?: return null, startTime = o.string("start") ?: return null,
            endTime = o.string("end") ?: return null, title = o.string("title") ?: return null,
            location = o.string("location"),
        )
    }
}
