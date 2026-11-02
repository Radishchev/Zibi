package com.zibi.app

import com.zibi.app.data.ProfileFact
import com.zibi.app.data.TimetableEntry
import com.zibi.app.profile.buildSnapshot
import com.zibi.app.protocol.string
import com.zibi.app.tools.UpdateTimetableTool
import com.zibi.app.tools.eventTimes
import com.zibi.app.tools.normalizeDay
import com.zibi.app.tools.normalizeKey
import com.zibi.app.tools.normalizeTime
import com.zibi.app.tools.parseTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private fun entry(day: String, start: String, end: String, title: String) = buildJsonObject {
    put("day", day); put("start", start); put("end", end); put("title", title)
}

private fun entries(vararg e: JsonObject): JsonObject = buildJsonObject {
    put("entries", JsonArray(e.toList()))
}

class ProfileAndTimeTest {
    // ---- normalisation helpers
    @Test
    fun daysAndTimesAreNormalised() {
        assertEquals("Friday", normalizeDay("fri"))
        assertEquals("Thursday", normalizeDay("Thurs"))
        assertEquals("Wednesday", normalizeDay("WEDNESDAY"))
        assertNull(normalizeDay("someday"))
        assertEquals("09:05", normalizeTime("9:05"))
        assertEquals("09:05", normalizeTime("09.05"))
        assertNull(normalizeTime("25:00"))
        assertNull(normalizeTime("noon"))
        assertEquals("wake_time", normalizeKey("Wake Time!"))
        assertNull(normalizeKey("   "))
    }

    // ---- all-day events and time math
    @Test
    fun allDayEventsAreUtcMidnightToMidnight() {
        val t = eventTimes(LocalDate.of(2026, 10, 26), null, null, true, ZoneId.of("Asia/Kolkata"))
        assertEquals(Instant.parse("2026-10-26T00:00:00Z").toEpochMilli(), t.startMs)
        assertEquals(Instant.parse("2026-10-27T00:00:00Z").toEpochMilli(), t.endMs)
        assertEquals("UTC", t.timezone)
        assertTrue(t.allDay)
    }

    @Test
    fun timedEventsUseTheLocalZoneAndDefaultToOneHour() {
        val ist = ZoneId.of("Asia/Kolkata")
        val t = eventTimes(LocalDate.of(2026, 10, 26), LocalTime.of(10, 0), null, false, ist)
        assertEquals(Instant.parse("2026-10-26T04:30:00Z").toEpochMilli(), t.startMs)
        assertEquals(3_600_000L, t.endMs - t.startMs)
        // a late event whose default end passes midnight must still end AFTER it starts
        val late = eventTimes(LocalDate.of(2026, 10, 26), LocalTime.of(23, 30), null, false, ist)
        assertEquals(3_600_000L, late.endMs - late.startMs)
    }

    @Test
    fun timedEventWithoutAStartTimeIsRejected() {
        try {
            eventTimes(LocalDate.of(2026, 10, 26), null, null, false, ZoneId.of("UTC"))
            org.junit.Assert.fail("expected an exception")
        } catch (e: IllegalArgumentException) {
            // expected: the tool must ask for a time instead of guessing
        }
    }

    @Test
    fun timesParseLeniently() {
        assertEquals(LocalTime.of(9, 5), parseTime("9:05"))
        assertNull(parseTime("later"))
    }

    // ---- update_timetable
    @Test
    fun timetableEntriesAreNormalisedAndSaved() = runTest {
        val dao = FakeProfileDao()
        val r = UpdateTimetableTool(dao).execute(entries(entry("fri", "9:30", "11:00", "Data Science")))
        assertTrue(r.ok)
        assertEquals(listOf("Friday 09:30-11:00 Data Science"),
            dao.classes.map { "${it.day} ${it.startTime}-${it.endTime} ${it.title}" })
    }

    @Test
    fun oneBadEntryMeansNothingIsSaved() = runTest {
        val dao = FakeProfileDao()
        val r = UpdateTimetableTool(dao).execute(
            entries(entry("Monday", "10:00", "11:00", "ML"), entry("Funday", "10:00", "11:00", "Party"))
        )
        assertFalse(r.ok)
        assertTrue(dao.classes.isEmpty())
        val backwards = UpdateTimetableTool(dao).execute(entries(entry("Monday", "11:00", "10:00", "ML")))
        assertFalse(backwards.ok)
    }

    @Test
    fun replaceCanBeUndoneAndAddCanBeUndone() = runTest {
        val dao = FakeProfileDao()
        val tool = UpdateTimetableTool(dao)
        tool.execute(entries(entry("Monday", "10:00", "11:00", "ML")))

        val replace = tool.execute(buildJsonObject {
            put("mode", "replace")
            put("entries", buildJsonArray { add(entry("Tuesday", "09:00", "10:00", "DL")) })
        })
        assertEquals(listOf("DL"), dao.classes.map { it.title })
        assertTrue(tool.undo(replace.undoToken!!))
        assertEquals(listOf("ML"), dao.classes.map { it.title })

        val add = tool.execute(entries(entry("Friday", "10:00", "12:00", "DS")))
        assertEquals(setOf("ML", "DS"), dao.classes.map { it.title }.toSet())
        assertTrue(tool.undo(add.undoToken!!))
        assertEquals(listOf("ML"), dao.classes.map { it.title })
    }

    // ---- what is sent to the server
    @Test
    fun snapshotHasTimetableInWeekOrderAndFacts() {
        val snap = buildSnapshot(
            facts = listOf(ProfileFact(1, "routine", "wake_time", "07:00", "user", 0)),
            timetable = listOf(
                TimetableEntry(1, "Friday", "10:00", "12:00", "DS", null),
                TimetableEntry(2, "Monday", "14:00", "16:00", "ML", "Room 4"),
            ),
        )
        val tt = snap["timetable"] as JsonArray
        assertEquals("ML", (tt[0] as JsonObject).string("title"))
        assertEquals("Room 4", (tt[0] as JsonObject).string("location"))
        assertEquals("DS", (tt[1] as JsonObject).string("title"))
        val profile = snap["profile"] as JsonArray
        assertEquals("wake_time", (profile[0] as JsonObject).string("key"))
        // it must be valid JSON the server's schema accepts
        assertNotNull(Json.parseToJsonElement(snap.toString()).jsonObject["profile"])
    }
}
