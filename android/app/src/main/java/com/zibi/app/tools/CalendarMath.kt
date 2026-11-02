package com.zibi.app.tools

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.random.Random

data class EventTimes(val startMs: Long, val endMs: Long, val timezone: String, val allDay: Boolean)

/** "9:05" / "09:05" -> LocalTime, null if invalid. */
fun parseTime(text: String): LocalTime? = normalizeTime(text)?.let { LocalTime.parse(it) }

/**
 * Android stores all-day events as midnight-to-midnight in UTC (not the local zone), so they stay on the same
 * calendar date wherever the phone is. Timed events use the local zone; a missing end defaults to +1 hour.
 */
fun eventTimes(date: LocalDate, start: LocalTime?, end: LocalTime?, allDay: Boolean, zone: ZoneId): EventTimes {
    if (allDay) {
        val s = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val e = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return EventTimes(s, e, "UTC", true)
    }
    require(start != null) { "a timed event needs a start time" }
    val startZ = date.atTime(start).atZone(zone)
    val endZ = if (end != null && end.isAfter(start)) date.atTime(end).atZone(zone) else startZ.plusHours(1)
    return EventTimes(startZ.toInstant().toEpochMilli(), endZ.toInstant().toEpochMilli(), zone.id, false)
}

/** The most alarms one request may create. A sanity limit, not a preference: each alarm is a separate call. */
const val MAX_ALARMS = 100

sealed interface AlarmPlan {
    data class Times(val times: List<LocalTime>) : AlarmPlan
    data class Invalid(val message: String) : AlarmPlan
}

private fun minutesOf(t: LocalTime) = t.hour * 60 + t.minute

private fun timeFromMinutes(m: Int): LocalTime {
    val d = ((m % 1440) + 1440) % 1440
    return LocalTime.of(d / 60, d % 60)
}

/**
 * Turns however the user described their alarms into exact times, in code (never by the model's arithmetic):
 *  1. an explicit list of times;
 *  2. from..to every N minutes ("every minute from 05:45 to 06:20");
 *  3. a count ending at a time, every N minutes (the LAST alarm is at [toTime]);
 *  4. a count of randomly spaced alarms between two times.
 * Ranges may cross midnight.
 */
fun planAlarms(
    times: List<String>?,
    fromTime: String?,
    toTime: String?,
    everyMinutes: Int?,
    count: Int?,
    random: Boolean,
    rng: Random = Random.Default,
): AlarmPlan {
    fun invalid(message: String) = AlarmPlan.Invalid(message)

    val plan: List<LocalTime> = when {
        !times.isNullOrEmpty() -> {
            val parsed = times.map { it to parseTime(it) }
            parsed.firstOrNull { it.second == null }?.let { return invalid("\"${it.first}\" is not a valid HH:MM time") }
            parsed.mapNotNull { it.second }.distinct()
        }
        fromTime != null && toTime != null && everyMinutes != null -> {
            val from = parseTime(fromTime) ?: return invalid("from_time must be HH:MM")
            val to = parseTime(toTime) ?: return invalid("to_time must be HH:MM")
            if (everyMinutes < 1) return invalid("every_minutes must be at least 1")
            val start = minutesOf(from)
            var end = minutesOf(to)
            if (end < start) end += 1440
            (start..end step everyMinutes).map(::timeFromMinutes)
        }
        toTime != null && count != null && everyMinutes != null -> {
            val to = parseTime(toTime) ?: return invalid("to_time must be HH:MM")
            if (count < 1) return invalid("count must be at least 1")
            if (everyMinutes < 1) return invalid("every_minutes must be at least 1")
            if (count > MAX_ALARMS) return invalid("that would be $count alarms; the most I can set in one go is $MAX_ALARMS")
            (count - 1 downTo 0).map { timeFromMinutes(minutesOf(to) - it * everyMinutes) }
        }
        random && fromTime != null && toTime != null && count != null -> {
            val from = parseTime(fromTime) ?: return invalid("from_time must be HH:MM")
            val to = parseTime(toTime) ?: return invalid("to_time must be HH:MM")
            val start = minutesOf(from)
            var end = minutesOf(to)
            if (end < start) end += 1440
            val span = end - start
            if (count < 1) return invalid("count must be at least 1")
            if (count > span + 1) return invalid("there are only ${span + 1} different minutes between those times")
            (0..span).shuffled(rng).take(count).sorted().map { timeFromMinutes(start + it) }
        }
        else -> return invalid(
            "describe the alarms with a list of times, or from_time + to_time + every_minutes, or " +
                "to_time + count + every_minutes, or from_time + to_time + count with random=true"
        )
    }
    if (plan.isEmpty()) return invalid("that describes no alarms")
    if (plan.size > MAX_ALARMS) return invalid("that would be ${plan.size} alarms; the most I can set in one go is $MAX_ALARMS")
    return AlarmPlan.Times(plan)
}
