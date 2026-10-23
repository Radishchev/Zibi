package com.zibi.app.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.zibi.app.protocol.Risk
import com.zibi.app.protocol.ToolSpec
import com.zibi.app.protocol.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Tools that hand work to other apps through standard Android intents.
 * No special permissions, and it works with whichever clock/calendar app the user has.
 */

class SetAlarmTool(private val context: Context) : ZibiTool {
    override val spec = ToolSpec(
        name = "set_alarm",
        description = "Set an alarm in the phone's clock app at the next occurrence of a given time.",
        parameters = schema(
            """
            {"type":"object",
             "properties":{
               "time":{"type":"string","description":"HH:MM, 24h"},
               "label":{"type":"string"}},
             "required":["time"],
             "additionalProperties":false}
            """.trimIndent()
        ),
        risk = Risk.WRITE,
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val time = args.string("time")?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            ?: return ToolResult.fail("time must be HH:MM in 24h format")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, time.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, time.minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, args.string("label") ?: "Zibi")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            ToolResult(
                ok = true,
                summary = "Alarm set for $time",
                result = buildJsonObject { put("status", "alarm_set"); put("time", time.toString()) },
            )
        } catch (e: ActivityNotFoundException) {
            ToolResult.fail("no clock app available to set alarms")
        }
    }
}

class CreateCalendarEventTool(private val context: Context) : ZibiTool {
    override val spec = ToolSpec(
        name = "create_calendar_event",
        description = "Open the calendar app with a pre-filled event. The USER must tap Save in the calendar, " +
            "so never say the event was saved, only that it was prepared. start_time defaults to 09:00, " +
            "end_time to one hour later.",
        parameters = schema(
            """
            {"type":"object",
             "properties":{
               "title":{"type":"string"},
               "date":{"type":"string","description":"YYYY-MM-DD"},
               "start_time":{"type":"string","description":"HH:MM, 24h"},
               "end_time":{"type":"string","description":"HH:MM, 24h"}},
             "required":["title","date"],
             "additionalProperties":false}
            """.trimIndent()
        ),
        risk = Risk.WRITE,
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val title = args.string("title")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.fail("title is required")
        val dateText = args.string("date") ?: return ToolResult.fail("date is required")
        val date = try {
            LocalDate.parse(dateText)
        } catch (e: DateTimeParseException) {
            return ToolResult.fail("date must be YYYY-MM-DD")
        }
        val startText = args.string("start_time")
        val start = if (startText == null) LocalTime.of(9, 0)
        else runCatching { LocalTime.parse(startText) }.getOrNull() ?: return ToolResult.fail("start_time must be HH:MM")
        val endText = args.string("end_time")
        val end = if (endText == null) start.plusHours(1)
        else runCatching { LocalTime.parse(endText) }.getOrNull() ?: return ToolResult.fail("end_time must be HH:MM")
        if (!end.isAfter(start)) return ToolResult.fail("end_time must be after start_time")

        val zone = ZoneId.systemDefault()
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, date.atTime(start).atZone(zone).toInstant().toEpochMilli())
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, date.atTime(end).atZone(zone).toInstant().toEpochMilli())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            ToolResult(
                ok = true,
                summary = "Calendar opened for \"$title\" on $date, tap Save there",
                result = buildJsonObject { put("status", "calendar_editor_opened_user_must_save") },
            )
        } catch (e: ActivityNotFoundException) {
            ToolResult.fail("no calendar app available")
        }
    }
}
