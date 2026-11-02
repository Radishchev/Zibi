package com.zibi.app

import com.zibi.app.data.FactHistory
import com.zibi.app.data.ProfileDao
import com.zibi.app.data.ProfileFact
import com.zibi.app.data.TimetableEntry
import com.zibi.app.data.VaultDao
import com.zibi.app.data.VaultEntry
import com.zibi.app.privacy.NoopCipher
import com.zibi.app.privacy.PrivacyVault
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal class FakeVaultDao : VaultDao {
    val rows = mutableListOf<VaultEntry>()
    override suspend fun insert(entry: VaultEntry): Long { rows += entry.copy(id = rows.size + 1L); return rows.size.toLong() }
    override suspend fun all(): List<VaultEntry> = rows.toList()
    override fun observeCount(): Flow<Int> = flowOf(rows.size)
}

internal fun newVault(dao: FakeVaultDao = FakeVaultDao()) = PrivacyVault(dao, NoopCipher)

internal class FakeProfileDao : ProfileDao {
    val facts = mutableListOf<ProfileFact>()
    val history = mutableListOf<FactHistory>()
    val classes = mutableListOf<TimetableEntry>()
    private var next = 1L

    override suspend fun findFact(key: String) = facts.firstOrNull { it.factKey == key }
    override suspend fun insertFact(fact: ProfileFact): Long { val id = next++; facts += fact.copy(id = id); return id }
    override suspend fun updateFact(fact: ProfileFact) {
        val i = facts.indexOfFirst { it.id == fact.id }
        if (i >= 0) facts[i] = fact
    }
    override suspend fun deleteFactByKey(key: String): Int { val b = facts.size; facts.removeAll { it.factKey == key }; return b - facts.size }
    override suspend fun deleteFactById(id: Long) { facts.removeAll { it.id == id } }
    override fun observeFacts(): Flow<List<ProfileFact>> = flowOf(facts)
    override suspend fun searchFacts(pattern: String): List<ProfileFact> {
        val p = pattern.trim('%').lowercase()
        return facts.filter { it.factKey.lowercase().contains(p) || it.value.lowercase().contains(p) }
    }
    override suspend fun archiveExpired(now: Long): Int {
        var n = 0
        facts.indices.forEach { i ->
            val f = facts[i]
            if (f.status == "active" && f.validUntil != null && f.validUntil < now) { facts[i] = f.copy(status = "archived", archivedAt = now); n++ }
        }
        return n
    }
    override suspend fun insertHistory(entry: FactHistory): Long { val id = next++; history += entry.copy(id = id); return id }
    override suspend fun deleteHistoryById(id: Long) { history.removeAll { it.id == id } }
    override suspend fun deleteHistoryByKey(key: String) { history.removeAll { it.factKey == key } }
    override suspend fun searchHistory(pattern: String): List<FactHistory> {
        val p = pattern.trim('%').lowercase()
        return history.filter { it.factKey.lowercase().contains(p) || it.value.lowercase().contains(p) }
    }
    override fun observeHistory(): Flow<List<FactHistory>> = flowOf(history)
    override suspend fun insertTimetable(entries: List<TimetableEntry>): List<Long> =
        entries.map { val id = next++; classes += it.copy(id = id); id }
    override suspend fun timetable() = classes.toList()
    override fun observeTimetable(): Flow<List<TimetableEntry>> = flowOf(classes)
    override suspend fun clearTimetable() { classes.clear() }
    override suspend fun deleteTimetableEntries(ids: List<Long>): Int {
        val b = classes.size; classes.removeAll { it.id in ids }; return b - classes.size
    }
}
