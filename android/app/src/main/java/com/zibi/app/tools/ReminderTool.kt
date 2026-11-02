package com.zibi.app.tools

import android.Manifest
import com.zibi.app.PermissionBroker
import com.zibi.app.data.Reminder
import com.zibi.app.data.ReminderDao
import com.zibi.app.protocol.Risk
import com.zibi.app.protocol.ToolSpec
import com.zibi.app.protocol.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CreateReminderTool(
    private val dao: ReminderDao,
    private val permissions: PermissionBroker,
    private val scheduler: ReminderScheduler,
) : ZibiTool {
    override val spec = ToolSpec(
        name = "create_reminder",
        description = "Create a reminder on the phone. Use trigger 'on_leaving_home' for things to do before " +
            "going out. For a notification at a specific time, resolve the user's full time phrase with " +
            "resolve_datetime first, then use 'at_time' with its due_date (YYYY-MM-DD) and time (HH:MM, 24h). " +
            "Without a time, the reminder is saved but will not send a notification.",
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
        val trigger = args.string("trigger") ?: "at_time"
        val dueDate = args.string("due_date")
        val time = args.string("time")
        val timed = trigger == "at_time" && dueDate != null && time != null
        if (trigger == "at_time" && (dueDate == null) != (time == null)) {
            return ToolResult.fail("both due_date and time are required for a timed reminder")
        }
        val reminder = Reminder(
            title = title,
            triggerType = trigger,
            dueDate = dueDate,
            time = time,
            createdAt = System.currentTimeMillis(),
        )
        if (timed) {
            val triggerAt = reminderTriggerAtMillis(reminder)
                ?: return ToolResult.fail("the reminder date or time is invalid")
            if (triggerAt <= System.currentTimeMillis()) {
                return ToolResult.fail("the reminder time must be in the future")
            }
            if (!permissions.ensure(Manifest.permission.POST_NOTIFICATIONS)) {
                return ToolResult.fail("notification permission is needed to deliver this reminder")
            }
            if (!permissions.ensureExactAlarms()) {
                return ToolResult.fail("exact-alarm access is needed to deliver this reminder on time")
            }
        }
        val id = dao.insert(reminder)
        if (timed && !scheduler.schedule(reminder.copy(id = id))) {
            dao.deleteById(id)
            return ToolResult.fail("Android could not schedule this reminder")
        }
        return ToolResult(
            ok = true,
            summary = if (timed) "Reminder set: $title" else "Reminder saved without a notification time: $title",
            result = buildJsonObject { put("reminder_id", id) },
            undoToken = id.toString(),
        )
    }

    override suspend fun undo(token: String): Boolean {
        val id = token.toLongOrNull() ?: return false
        scheduler.cancel(id)
        return dao.deleteById(id) > 0
    }
}
