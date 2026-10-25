package com.zibi.app.tools

import com.zibi.app.data.Reminder
import com.zibi.app.data.ReminderDao
import com.zibi.app.protocol.Risk
import com.zibi.app.protocol.ToolSpec
import com.zibi.app.protocol.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

import com.zibi.app.reminders.ReminderScheduler

class CreateReminderTool(
    private val dao: ReminderDao,
    private val scheduler: ReminderScheduler? = null,
) : ZibiTool {
    override val spec = ToolSpec(
        name = "create_reminder",
        description = "Create a reminder on the phone. Use trigger 'on_leaving_home' for things to do before " +
            "going out. Use 'at_time' with due_date (YYYY-MM-DD) and optional time (HH:MM, 24h) otherwise.",
        parameters = schema(
            """
            {"type":"object",
             "properties":{
               "title":{"type":"string"},
               "trigger":{"type":"string","enum":["on_leaving_home","at_time"]},
               "due_date":{"type":"string","description":"YYYY-MM-DD"},
               "time":{"type":"string","description":"HH:MM, 24h"}},
             "required":["title"],
             "additionalProperties":false}
            """.trimIndent()
        ),
        risk = Risk.WRITE,
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val title = args.string("title")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.fail("title is required")
        val reminder = Reminder(
            title = title,
            triggerType = args.string("trigger") ?: "at_time",
            dueDate = args.string("due_date"),
            time = args.string("time"),
            createdAt = System.currentTimeMillis(),
        )
        val id = dao.insert(reminder)
        scheduler?.schedule(reminder.copy(id = id))
        return ToolResult(
            ok = true,
            summary = "Reminder saved: $title",
            result = buildJsonObject { put("reminder_id", id) },
            undoToken = id.toString(),
        )
    }

    override suspend fun undo(token: String): Boolean {
        val id = token.toLongOrNull() ?: return false
        scheduler?.cancel(id)
        return dao.deleteById(id) > 0
    }
}
