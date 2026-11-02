package com.zibi.app.profile

import com.zibi.app.data.FactHistory
import com.zibi.app.data.ProfileDao
import com.zibi.app.data.ProfileFact
import com.zibi.app.data.TimetableEntry
import com.zibi.app.privacy.PrivacyVault
import com.zibi.app.tools.WEEK_DAYS
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Facts not confirmed for this long are flagged to the AI as possibly out of date. */
const val STALE_AFTER_MS = 180L * 24 * 60 * 60 * 1000

/**
 * The JSON the server gets (`hello.context` / `context.update`). Only ACTIVE facts are included: archived ones stay on
 * the phone. Hidden facts hold placeholders, never real values.
 */
fun buildSnapshot(
    facts: List<ProfileFact>,
    timetable: List<TimetableEntry>,
    now: Long = System.currentTimeMillis(),
): JsonObject = buildJsonObject {
    put("timetable", buildJsonArray {
        timetable.sortedWith(compareBy({ WEEK_DAYS.indexOf(it.day) }, { it.startTime })).forEach { e ->
            add(buildJsonObject {
                put("day", e.day); put("start", e.startTime); put("end", e.endTime); put("title", e.title)
                e.location?.let { put("location", it) }
            })
        }
    })
    put("profile", buildJsonArray {
        facts.filter { it.status == "active" }.forEach { f ->
            add(buildJsonObject {
                put("category", f.category); put("key", f.factKey); put("value", f.value)
                if (f.confirmedAt > 0 && now - f.confirmedAt > STALE_AFTER_MS) put("stale", true)
            })
        }
    })
}

class ProfileRepository(private val dao: ProfileDao, private val vault: PrivacyVault) {
    val facts: Flow<List<ProfileFact>> = dao.observeFacts()
    val timetable: Flow<List<TimetableEntry>> = dao.observeTimetable()
    val history: Flow<List<FactHistory>> = dao.observeHistory()
    val hiddenCount: Flow<Int> = vault.hiddenCount

    /** Emits whenever anything the user told Zibi about themselves changes. Everything in it is already masked. */
    val snapshot: Flow<JsonObject> = combine(facts, timetable) { f, t ->
        vault.redactJson(buildSnapshot(f, t)) as JsonObject
    }.distinctUntilChanged()

    suspend fun current(): JsonObject {
        sweepExpired()
        return snapshot.first()
    }

    /** Facts with an end date (a semester) that has passed move to the archive. */
    suspend fun sweepExpired() { dao.archiveExpired(System.currentTimeMillis()) }

    suspend fun reveal(text: String): String = vault.resolve(text)

    suspend fun deleteFact(f: ProfileFact) {
        dao.deleteFactById(f.id)
        dao.deleteHistoryByKey(f.factKey)
    }

    suspend fun deleteTimetableEntry(id: Long) { dao.deleteTimetableEntries(listOf(id)) }

    /** The user edits a fact by hand. The old value goes to history. */
    suspend fun edit(f: ProfileFact, newText: String) {
        val text = newText.trim().take(300)
        if (text.isEmpty()) return
        val stored: String
        val hidden: Boolean
        if (f.isPrivate) {
            stored = vault.protect(text)
            hidden = true
        } else {
            val r = vault.redact(text)
            stored = r.text
            hidden = r.count > 0
        }
        if (stored == f.value) return
        val now = System.currentTimeMillis()
        dao.insertHistory(
            FactHistory(
                factKey = f.factKey, category = f.category, value = f.value, isPrivate = f.isPrivate,
                validFrom = f.updatedAt, validTo = now, reason = "edited by you",
            )
        )
        dao.updateFact(f.copy(value = stored, isPrivate = hidden, updatedAt = now, confirmedAt = now))
    }

    /** Hide a fact from the AI (its value moves into the encrypted vault), or show it again. */
    suspend fun setHidden(f: ProfileFact, hidden: Boolean) {
        val real = vault.resolve(f.value)
        val stored = if (hidden) vault.protect(real) else real
        dao.updateFact(f.copy(value = stored, isPrivate = hidden))
    }

    suspend fun restore(f: ProfileFact) {
        dao.updateFact(f.copy(status = "active", archivedAt = null, validUntil = null, confirmedAt = System.currentTimeMillis()))
    }

    suspend fun archive(f: ProfileFact) {
        dao.updateFact(f.copy(status = "archived", archivedAt = System.currentTimeMillis()))
    }
}
