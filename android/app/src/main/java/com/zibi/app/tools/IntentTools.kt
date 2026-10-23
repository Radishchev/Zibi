package com.zibi.app.tools

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.zibi.app.PermissionBroker
import com.zibi.app.protocol.Risk
import com.zibi.app.protocol.ToolSpec
import com.zibi.app.protocol.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

class CreateCalendarEventTool(
    private val context: Context,
    private val permissions: PermissionBroker,
) : ZibiTool {
    override val spec = ToolSpec(
        name = "create_calendar_event",
        description = "Add an event to the user's calendar. The result says whether it was saved directly " +
            "(status event_saved) or only opened for the user to save (status calendar_editor_opened_user_must_save); " +
            "report exactly that. start_time defaults to 09:00, end_time to one hour later.",
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
        val startMs = date.atTime(start).atZone(zone).toInstant().toEpochMilli()
        val endMs = date.atTime(end).atZone(zone).toInstant().toEpochMilli()

        // Preferred path: write the event directly (undoable). Needs the calendar permission.
        if (permissions.ensure(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) {
            val id = try {
                withContext(Dispatchers.IO) { insertEvent(title, startMs, endMs, zone.id) }
            } catch (e: SecurityException) {
                null
            }
            if (id != null) {
                return ToolResult(
                    ok = true,
                    summary = "Added to calendar: \"$title\" on $date at $start",
                    result = buildJsonObject { put("status", "event_saved"); put("event_id", id) },
                    undoToken = id.toString(),
                )
            }
        }

        // Fallback (permission denied or no writable calendar): open the editor pre-filled, the user taps Save.
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
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

    override suspend fun undo(token: String): Boolean {
        val id = token.toLongOrNull() ?: return false
        return withContext(Dispatchers.IO) {
            try {
                context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) > 0
            } catch (e: SecurityException) {
                false
            }
        }
    }

    private fun insertEvent(title: String, startMs: Long, endMs: Long, timezoneId: String): Long? {
        val resolver = context.contentResolver
        val calendarId = findCalendarId(resolver) ?: return null
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.EVENT_TIMEZONE, timezoneId)
        }
        val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return null
        return ContentUris.parseId(uri)
    }

    /** The primary visible writable calendar, else the first visible writable one. */
    private fun findCalendarId(resolver: ContentResolver): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY)
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        var first: Long? = null
        resolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (first == null) first = id
                if (c.getInt(1) == 1) return id
            }
        }
        return first
    }
}
