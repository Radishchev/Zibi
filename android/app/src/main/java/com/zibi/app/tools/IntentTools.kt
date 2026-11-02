package com.zibi.app.tools

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.zibi.app.PermissionBroker
import com.zibi.app.data.ActionLogDao
import com.zibi.app.protocol.Risk
import com.zibi.app.protocol.ToolSpec
import com.zibi.app.protocol.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.Calendar

/**
 * Tools that hand work to other apps through standard Android intents, or write to the system calendar.
 * They work with whichever clock/calendar app the user has.
 */

// ---------------------------------------------------------------- alarms

private val DAY_CONSTANTS = mapOf(
    "monday" to Calendar.MONDAY, "tuesday" to Calendar.TUESDAY, "wednesday" to Calendar.WEDNESDAY,
    "thursday" to Calendar.THURSDAY, "friday" to Calendar.FRIDAY, "saturday" to Calendar.SATURDAY,
    "sunday" to Calendar.SUNDAY,
)

private fun repeatDays(args: JsonObject): List<Int> =
    (args["repeat_days"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(::normalizeDay)?.lowercase() }
        ?.mapNotNull { DAY_CONSTANTS[it] }
        .orEmpty()

private fun alarmIntent(time: LocalTime, label: String, days: List<Int>): Intent =
    Intent(AlarmClock.ACTION_SET_ALARM).apply {
        putExtra(AlarmClock.EXTRA_HOUR, time.hour)
        putExtra(AlarmClock.EXTRA_MINUTES, time.minute)
        putExtra(AlarmClock.EXTRA_MESSAGE, label)
        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (days.isNotEmpty()) putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

class SetAlarmTool(private val context: Context) : ZibiTool {
    override val spec = ToolSpec(
        name = "set_alarm",
        description = "Set ONE alarm in the phone's clock app at the next occurrence of a time. Optionally repeating " +
            "on given weekdays. For several alarms use set_alarms.",
        parameters = schema(
            """
            {"type":"object",
             "properties":{
               "time":{"type":"string","description":"HH:MM, 24h"},
               "label":{"type":"string"},
               "repeat_days":{"type":"array","items":{"type":"string"}}},
             "required":["time"],
             "additionalProperties":false}
            """.trimIndent()
        ),
        risk = Risk.WRITE,
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val time = args.string("time")?.let(::parseTime) ?: return ToolResult.fail("time must be HH:MM in 24h format")
        return try {
            context.startActivity(alarmIntent(time, args.string("label") ?: "Zibi", repeatDays(args)))
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

/**
 * Sets as many alarms as the user asks for, spaced however they ask. The model only describes the schedule;
 * the exact times are worked out in code ([planAlarms]).
 */
class SetAlarmsTool(private val context: Context) : ZibiTool {
    override val spec = ToolSpec(
        name = "set_alarms",
        description = "Set several alarms in the clock app, EXACTLY as the user describes them: never fewer, never " +
            "different. Describe them in ONE of these ways: (1) times: a list of HH:MM; (2) from_time + to_time + " +
            "every_minutes (e.g. every minute from 05:45 to 06:20); (3) to_time + count + every_minutes (the LAST " +
            "alarm is at to_time); (4) from_time + to_time + count + random=true for randomly spaced alarms. " +
            "Up to 100 alarms. You do not need to calculate any times.",
        parameters = schema(
            """
            {"type":"object",
             "properties":{
               "times":{"type":"array","items":{"type":"string"},"description":"HH:MM, 24h"},
               "from_time":{"type":"string","description":"HH:MM, 24h"},
               "to_time":{"type":"string","description":"HH:MM, 24h"},
               "every_minutes":{"type":"integer"},
               "count":{"type":"integer"},
               "random":{"type":"boolean"},
               "label":{"type":"string"},
               "repeat_days":{"type":"array","items":{"type":"string"}}},
             "additionalProperties":false}
            """.trimIndent()
        ),
        risk = Risk.WRITE,
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val plan = planAlarms(
            times = (args["times"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            fromTime = args.string("from_time"),
            toTime = args.string("to_time"),
            everyMinutes = (args["every_minutes"] as? JsonPrimitive)?.intOrNull,
            count = (args["count"] as? JsonPrimitive)?.intOrNull,
            random = (args["random"] as? JsonPrimitive)?.booleanOrNull == true,
        )
        val times = when (plan) {
            is AlarmPlan.Invalid -> return ToolResult.fail(plan.message)
            is AlarmPlan.Times -> plan.times
        }
        val label = args.string("label") ?: "Zibi"
        val days = repeatDays(args)
        try {
            times.forEachIndexed { i, t ->
                context.startActivity(alarmIntent(t, if (times.size > 1) "$label ${i + 1}/${times.size}" else label, days))
                delay(200) // give the clock app time to take each one
            }
        } catch (e: ActivityNotFoundException) {
            return ToolResult.fail("no clock app available to set alarms")
        }
        val shown = if (times.size <= 4) times.joinToString() else "${times.first()} to ${times.last()}"
        return ToolResult(
            ok = true,
            summary = "Set ${times.size} alarm(s): $shown",
            result = buildJsonObject {
                put("status", "alarms_set"); put("count", times.size)
                put("first", times.first().toString()); put("last", times.last().toString())
            },
            // no undo: clock apps don't let other apps delete alarms. They can be cleared in the clock app.
        )
    }
}

// ---------------------------------------------------------------- calendar

class CreateCalendarEventTool(
    private val context: Context,
    private val permissions: PermissionBroker,
) : ZibiTool {
    override val spec = ToolSpec(
        name = "create_calendar_event",
        description = "Add an event to the user's calendar. NEVER guess a time: give start_time only if the user " +
            "said it; for deadlines and submissions with no time use all_day=true; if a timed event has no time, " +
            "ask the user first. The result says whether it was saved (event_saved) or only opened for the user to " +
            "save (calendar_editor_opened_user_must_save); report exactly that. Remember the event_id.",
        parameters = schema(
            """
            {"type":"object",
             "properties":{
               "title":{"type":"string"},
               "date":{"type":"string","description":"YYYY-MM-DD"},
               "all_day":{"type":"boolean"},
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
        val allDay = (args["all_day"] as? JsonPrimitive)?.booleanOrNull == true
        val startText = args.string("start_time")
        val endText = args.string("end_time")
        val start = startText?.let { parseTime(it) ?: return ToolResult.fail("start_time must be HH:MM") }
        val end = endText?.let { parseTime(it) ?: return ToolResult.fail("end_time must be HH:MM") }
        if (!allDay && start == null) {
            return ToolResult.fail(
                "start_time is required unless all_day is true. Ask the user for the time, or use all_day for a deadline."
            )
        }
        if (!allDay && end != null && !end.isAfter(start!!)) return ToolResult.fail("end_time must be after start_time")

        val t = eventTimes(date, start, end, allDay, ZoneId.systemDefault())
        val whenText = if (t.allDay) "$date (all day)" else "$date at $start"

        // Preferred path: write the event directly (undoable). Needs the calendar permission.
        if (permissions.ensure(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) {
            val id = try {
                withContext(Dispatchers.IO) { insertEvent(title, t) }
            } catch (e: SecurityException) {
                null
            }
            if (id != null) {
                return ToolResult(
                    ok = true,
                    summary = "Added to calendar: \"$title\" on $whenText",
                    result = buildJsonObject { put("status", "event_saved"); put("event_id", id) },
                    undoToken = id.toString(),
                )
            }
        }

        // Fallback (permission denied or no writable calendar): open the editor pre-filled, the user taps Save.
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, t.startMs)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, t.endMs)
            putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, t.allDay)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            ToolResult(
                ok = true,
                summary = "Calendar opened for \"$title\" on $whenText, tap Save there",
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

    private fun insertEvent(title: String, t: EventTimes): Long? {
        val resolver = context.contentResolver
        val calendarId = findWritableCalendarId(context) ?: return null
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, t.startMs)
            put(CalendarContract.Events.DTEND, t.endMs)
            put(CalendarContract.Events.ALL_DAY, if (t.allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, t.timezone)
        }
        val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return null
        return ContentUris.parseId(uri)
    }
}

/** The primary visible writable calendar, else the first visible writable one. */
private fun findWritableCalendarId(context: Context): Long? {
    val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY)
    val selection = "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
        "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
    var first: Long? = null
    context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, null, null)?.use { c ->
        while (c.moveToNext()) {
            val id = c.getLong(0)
            if (first == null) first = id
            if (c.getInt(1) == 1) return id
        }
    }
    return first
}

/** Changes an event Zibi created earlier. It can only touch events that Zibi itself created. */
class UpdateCalendarEventTool(
    private val context: Context,
    private val permissions: PermissionBroker,
    private val log: ActionLogDao,
) : ZibiTool {
    override val spec = ToolSpec(
        name = "update_calendar_event",
        description = "Change a calendar event you created earlier (use the event_id from the create_calendar_event " +
            "result). Pass only the fields to change. all_day=true makes it an all-day event; to make it timed " +
            "again pass all_day=false and a start_time.",
        parameters = schema(
            """
            {"type":"object",
             "properties":{
               "event_id":{"type":"integer"},
               "title":{"type":"string"},
               "date":{"type":"string","description":"YYYY-MM-DD"},
               "all_day":{"type":"boolean"},
               "start_time":{"type":"string","description":"HH:MM, 24h"},
               "end_time":{"type":"string","description":"HH:MM, 24h"}},
             "required":["event_id"],
             "additionalProperties":false}
            """.trimIndent()
        ),
        risk = Risk.WRITE,
    )

    private data class Existing(val title: String, val startMs: Long, val endMs: Long, val allDay: Boolean, val tz: String?)

    override suspend fun execute(args: JsonObject): ToolResult {
        val id = (args["event_id"] as? JsonPrimitive)?.longOrNull ?: return ToolResult.fail("event_id is required")
        if (id.toString() !in log.undoTokens("create_calendar_event")) {
            return ToolResult.fail("I can only change events that I created myself.")
        }
        if (!permissions.ensure(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) {
            return ToolResult.fail("calendar permission is needed to change events")
        }
        return try {
            withContext(Dispatchers.IO) { applyUpdate(id, args) }
        } catch (e: SecurityException) {
            ToolResult.fail("calendar permission is needed to change events")
        }
    }

    private fun applyUpdate(id: Long, args: JsonObject): ToolResult {
        val old = readEvent(id) ?: return ToolResult.fail("that event no longer exists")
        val zone = ZoneId.systemDefault()
        val oldZone = if (old.allDay) ZoneOffset.UTC else zone
        val oldDate = Instant.ofEpochMilli(old.startMs).atZone(oldZone).toLocalDate()
        val oldStart = if (old.allDay) null else Instant.ofEpochMilli(old.startMs).atZone(zone).toLocalTime()
        val oldEnd = if (old.allDay) null else Instant.ofEpochMilli(old.endMs).atZone(zone).toLocalTime()

        val title = args.string("title")?.trim()?.takeIf { it.isNotEmpty() } ?: old.title
        val dateText = args.string("date")
        val date: LocalDate = when {
            dateText == null -> oldDate
            else -> runCatching { LocalDate.parse(dateText) }.getOrNull() ?: return ToolResult.fail("date must be YYYY-MM-DD")
        }
        val allDay = (args["all_day"] as? JsonPrimitive)?.booleanOrNull ?: old.allDay

        val startText = args.string("start_time")
        val endText = args.string("end_time")
        val start: LocalTime? = when {
            startText == null -> oldStart
            else -> parseTime(startText) ?: return ToolResult.fail("start_time must be HH:MM")
        }
        val end: LocalTime? = when {
            endText != null -> parseTime(endText) ?: return ToolResult.fail("end_time must be HH:MM")
            startText != null -> null // the start moved, so the old end no longer applies
            else -> oldEnd
        }
        if (!allDay && start == null) return ToolResult.fail("start_time is required to make it a timed event")

        val t = eventTimes(date, start, end, allDay, zone)
        val values = ContentValues().apply {
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, t.startMs)
            put(CalendarContract.Events.DTEND, t.endMs)
            put(CalendarContract.Events.ALL_DAY, if (t.allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, t.timezone)
        }
        val rows = context.contentResolver.update(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), values, null, null
        )
        if (rows == 0) return ToolResult.fail("couldn't update that event")
        val whenText = if (t.allDay) "$date (all day)" else "$date at ${start}"
        return ToolResult(
            ok = true,
            summary = "Updated \"$title\": $whenText",
            result = buildJsonObject { put("status", "event_updated"); put("event_id", id) },
            undoToken = listOf(id, old.startMs, old.endMs, if (old.allDay) 1 else 0, old.tz ?: "", old.title).joinToString("|"),
        )
    }

    override suspend fun undo(token: String): Boolean {
        val p = token.split("|", limit = 6)
        if (p.size != 6) return false
        val id = p[0].toLongOrNull() ?: return false
        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, p[1].toLongOrNull() ?: return false)
            put(CalendarContract.Events.DTEND, p[2].toLongOrNull() ?: return false)
            put(CalendarContract.Events.ALL_DAY, p[3].toIntOrNull() ?: return false)
            if (p[4].isNotEmpty()) put(CalendarContract.Events.EVENT_TIMEZONE, p[4])
            put(CalendarContract.Events.TITLE, p[5])
        }
        return withContext(Dispatchers.IO) {
            try {
                context.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), values, null, null) > 0
            } catch (e: SecurityException) {
                false
            }
        }
    }

    private fun readEvent(id: Long): Existing? = context.contentResolver.query(
        ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
        arrayOf(
            CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY, CalendarContract.Events.EVENT_TIMEZONE,
        ),
        null, null, null,
    )?.use { c ->
        if (c.moveToFirst()) Existing(c.getString(0) ?: "", c.getLong(1), c.getLong(2), c.getInt(3) == 1, c.getString(4)) else null
    }
}
