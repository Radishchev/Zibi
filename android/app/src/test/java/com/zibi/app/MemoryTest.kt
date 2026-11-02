package com.zibi.app

import com.zibi.app.data.ProfileFact
import com.zibi.app.profile.STALE_AFTER_MS
import com.zibi.app.profile.buildSnapshot
import com.zibi.app.protocol.string
import com.zibi.app.tools.ArchiveFactsTool
import com.zibi.app.tools.ForgetFactTool
import com.zibi.app.tools.RecallMemoryTool
import com.zibi.app.tools.RememberFactTool
import com.zibi.app.tools.UpdateTimetableTool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun remember(key: String, value: String, evidence: String = "I said so", hidden: Boolean = false) =
    buildJsonObject {
        put("key", key); put("value", value); put("evidence", evidence)
        if (hidden) put("private", true)
    }

private fun fact(
    key: String, value: String = "v", status: String = "active", confirmedAt: Long = 0L, isPrivate: Boolean = false,
) = ProfileFact(
    category = "routine", factKey = key, value = value, source = "user", updatedAt = 1L,
    status = status, isPrivate = isPrivate, confirmedAt = confirmedAt,
)

class MemoryTest {
    private fun setup(): Triple<FakeProfileDao, RememberFactTool, com.zibi.app.privacy.PrivacyVault> {
        val dao = FakeProfileDao()
        val vault = newVault()
        return Triple(dao, RememberFactTool(dao, vault), vault)
    }

    // ---- saving, grounding and sensitivity
    @Test
    fun aFactNeedsEvidence() = runTest {
        val (dao, tool, _) = setup()
        val r = tool.execute(buildJsonObject { put("key", "employer"); put("value", "Acme") })
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("evidence"))
        assertTrue(dao.facts.isEmpty())
    }

    @Test
    fun theToolAsksTheServerToCheckTheQuote() {
        val (_, tool, _) = setup()
        assertEquals(listOf("evidence"), tool.spec.grounded)
    }

    @Test
    fun aPhoneNumberInAFactIsStoredAsAPlaceholderNeverAsTheRealNumber() = runTest {
        val (dao, tool, vault) = setup()
        val r = tool.execute(remember("sam_phone", "Sam's number is +91 98765 43210"))
        assertTrue(r.ok)
        val stored = dao.findFact("sam_phone")!!
        assertEquals("Sam's number is [PHONE_1]", stored.value)
        assertTrue(stored.isPrivate)
        assertFalse(r.summary.contains("98765"))                     // not even in the chat note or History
        assertEquals("Sam's number is +91 98765 43210", vault.resolve(stored.value))
    }

    @Test
    fun aFactMarkedPrivateIsHiddenCompletely() = runTest {
        val (dao, tool, vault) = setup()
        val r = tool.execute(remember("home_address", "12 Rose Lane, Pune", hidden = true))
        assertTrue(r.ok)
        val stored = dao.findFact("home_address")!!
        assertEquals("[PRIVATE_1]", stored.value)
        assertTrue(stored.isPrivate && !r.summary.contains("Rose"))
        assertEquals("12 Rose Lane, Pune", vault.resolve(stored.value))
    }

    // ---- facts change over time
    @Test
    fun aChangedFactKeepsItsOldValueInHistoryAndCanBeUndone() = runTest {
        val (dao, tool, _) = setup()
        tool.execute(remember("employer", "Acme", "I work at Acme"))
        val update = tool.execute(remember("employer", "Globex", "I moved to Globex"))
        assertEquals("Globex", dao.findFact("employer")!!.value)
        assertEquals(listOf("Acme"), dao.history.map { it.value })

        assertTrue(tool.undo(update.undoToken!!))
        assertEquals("Acme", dao.findFact("employer")!!.value)
        assertTrue(dao.history.isEmpty())
    }

    @Test
    fun repeatingATrueFactRefreshesWhenItWasLastConfirmed() = runTest {
        val (dao, tool, _) = setup()
        tool.execute(remember("wake_time", "07:00"))
        val old = dao.findFact("wake_time")!!.copy(confirmedAt = 5L)
        dao.updateFact(old)
        val r = tool.execute(remember("wake_time", "07:00"))
        assertTrue(r.ok && r.undoToken == null)
        assertTrue(dao.findFact("wake_time")!!.confirmedAt > 5L)
        assertTrue(dao.history.isEmpty())
    }

    // ---- archive and recall (the "semester is over" case)
    @Test
    fun finishedFactsAreArchivedKeptAndRecallable() = runTest {
        val (dao, tool, _) = setup()
        tool.execute(remember("course_dl", "Deep Learning, Prof Rao", "I take Deep Learning with Prof Rao"))
        val archive = ArchiveFactsTool(dao).execute(buildJsonObject { put("keys", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("course_dl")) }) })
        assertTrue(archive.ok)
        assertEquals("archived", dao.findFact("course_dl")!!.status)

        val recalled = RecallMemoryTool(dao).execute(buildJsonObject { put("query", "deep learning") })
        assertTrue(recalled.ok)
        assertTrue(recalled.result.toString().contains("Prof Rao"))

        assertTrue(ArchiveFactsTool(dao).undo(archive.undoToken!!))
        assertEquals("active", dao.findFact("course_dl")!!.status)
    }

    @Test
    fun recallAlsoFindsEarlierValuesOfAChangedFact() = runTest {
        val (dao, tool, _) = setup()
        tool.execute(remember("employer", "Acme Corp", "I work at Acme Corp"))
        tool.execute(remember("employer", "Globex", "I moved to Globex"))
        val r = RecallMemoryTool(dao).execute(buildJsonObject { put("query", "acme") })
        assertTrue(r.result.toString().contains("previous value"))
    }

    @Test
    fun factsWithAnEndDateAreArchivedAutomaticallyOnceItHasPassed() = runTest {
        val dao = FakeProfileDao()
        dao.insertFact(fact("semester_exam", status = "active").copy(validUntil = 1_000L))
        dao.insertFact(fact("wake_time", status = "active"))
        assertEquals(1, dao.archiveExpired(now = 2_000L))
        assertEquals("archived", dao.findFact("semester_exam")!!.status)
        assertEquals("active", dao.findFact("wake_time")!!.status)
    }

    @Test
    fun forgettingDeletesTheFactAndItsHistory() = runTest {
        val (dao, tool, _) = setup()
        tool.execute(remember("employer", "Acme"))
        tool.execute(remember("employer", "Globex"))
        val r = ForgetFactTool(dao).execute(buildJsonObject { put("key", "employer") })
        assertTrue(r.ok)
        assertNull(dao.findFact("employer"))
        assertTrue(dao.history.isEmpty())
    }

    @Test
    fun replacingTheTimetableArchivesTheOldOneInsteadOfLosingIt() = runTest {
        val dao = FakeProfileDao()
        val tool = UpdateTimetableTool(dao)
        fun entries(title: String) = buildJsonObject {
            put("mode", "replace")
            put("entries", buildJsonArray { add(buildJsonObject { put("day", "Mon"); put("start", "10:00"); put("end", "11:00"); put("title", title) }) })
        }
        tool.execute(entries("Old Course"))
        tool.execute(entries("New Course"))
        assertEquals(listOf("New Course"), dao.classes.map { it.title })
        val archived = dao.facts.single { it.status == "archived" }
        assertTrue(archived.factKey.startsWith("timetable_") && archived.value.contains("Old Course"))
    }

    // ---- what is sent to the server
    @Test
    fun theSnapshotSendsOnlyActiveFactsAndFlagsStaleOnes() {
        val now = 10 * STALE_AFTER_MS
        val snapshot = buildSnapshot(
            facts = listOf(
                fact("fresh", confirmedAt = now - 1_000),
                fact("old", confirmedAt = now - STALE_AFTER_MS - 1),
                fact("past_course", status = "archived", confirmedAt = now),
                fact("sam_phone", value = "[PHONE_1]", isPrivate = true, confirmedAt = now),
            ),
            timetable = emptyList(),
            now = now,
        )
        val profile = snapshot["profile"] as JsonArray
        val keys = profile.map { (it as JsonObject).string("key") }
        assertEquals(listOf("fresh", "old", "sam_phone"), keys)             // the archived fact stays on the phone
        assertNull((profile[0] as JsonObject)["stale"])
        assertNotNull((profile[1] as JsonObject)["stale"])
        assertEquals("[PHONE_1]", (profile[2] as JsonObject).string("value"))
    }
}
