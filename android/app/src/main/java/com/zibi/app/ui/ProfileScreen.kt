package com.zibi.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zibi.app.AppContainer
import com.zibi.app.appViewModel
import com.zibi.app.data.FactHistory
import com.zibi.app.data.ProfileFact
import com.zibi.app.data.TimetableEntry
import com.zibi.app.profile.STALE_AFTER_MS
import com.zibi.app.tools.FACT_CATEGORIES
import com.zibi.app.tools.WEEK_DAYS
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ProfileViewModel(private val c: AppContainer) : ViewModel() {
    private fun <T> share(f: kotlinx.coroutines.flow.Flow<T>, initial: T): StateFlow<T> =
        f.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val facts: StateFlow<List<ProfileFact>> = share(c.profile.facts, emptyList())
    val timetable: StateFlow<List<TimetableEntry>> = share(c.profile.timetable, emptyList())
    val history: StateFlow<List<FactHistory>> = share(c.profile.history, emptyList())
    val hiddenCount: StateFlow<Int> = share(c.profile.hiddenCount, 0)

    suspend fun reveal(text: String): String = c.profile.reveal(text)
    fun delete(f: ProfileFact) { viewModelScope.launch { c.profile.deleteFact(f) } }
    fun edit(f: ProfileFact, text: String) { viewModelScope.launch { c.profile.edit(f, text) } }
    fun setHidden(f: ProfileFact, hidden: Boolean) { viewModelScope.launch { c.profile.setHidden(f, hidden) } }
    fun restore(f: ProfileFact) { viewModelScope.launch { c.profile.restore(f) } }
    fun deleteClass(e: TimetableEntry) { viewModelScope.launch { c.profile.deleteTimetableEntry(e.id) } }
}

private val categoryLabels = mapOf(
    "routine" to "Routine", "study" to "Studies", "preference" to "Preferences", "people" to "People", "other" to "Other",
)
private val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy")
private fun dateOf(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dateFormat)

@Composable
fun ProfileScreen(vm: ProfileViewModel = appViewModel { ProfileViewModel(it) }) {
    val facts by vm.facts.collectAsStateWithLifecycle()
    val timetable by vm.timetable.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val hidden by vm.hiddenCount.collectAsStateWithLifecycle()
    var showPast by remember { mutableStateOf(false) }

    val active = facts.filter { it.status == "active" }
    val archived = facts.filter { it.status == "archived" }
    val byCategory = active.groupBy { it.category }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Your map", style = MaterialTheme.typography.titleMedium)
            Text(
                "What Zibi has learned about you. It only saves what you actually said, keeps it up to date by itself " +
                    "(when something changes, the old value moves to your history), and puts finished things in Past. " +
                    "You never have to fill anything in. You can edit, hide or delete anything here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Privacy", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "$hidden private value(s) are hidden from the AI. Phone numbers, e-mail addresses, card and ID " +
                            "numbers and passwords are replaced by placeholders on this phone before anything is sent, and " +
                            "the real values stay here, encrypted. Zibi still uses them for you (for example to send a " +
                            "message) without the AI seeing them.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "This recognises common patterns, not everything. For anything else sensitive, use Hide on a fact.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        if (active.isEmpty() && timetable.isEmpty()) {
            item {
                Text(
                    "Nothing yet. Zibi learns as you use it. For example, the first time you ask for an alarm, it will " +
                        "ask how you like your alarms.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (timetable.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Timetable", style = MaterialTheme.typography.titleSmall)
                        timetable.sortedWith(compareBy({ WEEK_DAYS.indexOf(it.day) }, { it.startTime })).forEach { e ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(e.title)
                                    Text(
                                        "${e.day} ${e.startTime}-${e.endTime}" + (e.location?.let { " · $it" } ?: ""),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { vm.deleteClass(e) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                    }
                }
            }
        }
        FACT_CATEGORIES.filter { it in byCategory }.forEach { cat ->
            item(key = "cat-$cat") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(categoryLabels[cat] ?: cat, style = MaterialTheme.typography.titleSmall)
                        byCategory[cat].orEmpty().forEach { f -> FactRow(f, vm, archived = false) }
                    }
                }
            }
        }
        if (archived.isNotEmpty() || history.isNotEmpty()) {
            item {
                TextButton(onClick = { showPast = !showPast }) {
                    Text((if (showPast) "Hide" else "Show") + " past (${archived.size} archived, ${history.size} earlier values)")
                }
            }
        }
        if (showPast) {
            if (archived.isNotEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Archived", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Kept, but not sent to the AI. Zibi looks here only when you ask about the past.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            archived.forEach { f -> FactRow(f, vm, archived = true) }
                        }
                    }
                }
            }
            if (history.isNotEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Earlier values", style = MaterialTheme.typography.titleSmall)
                            history.forEach { h ->
                                Text(
                                    "${h.factKey}: ${if (h.isPrivate) "(hidden)" else h.value}  ·  until ${dateOf(h.validTo)}  ·  ${h.reason}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FactRow(f: ProfileFact, vm: ProfileViewModel, archived: Boolean) {
    var revealed by remember(f.id) { mutableStateOf(false) }
    var editing by remember(f.id) { mutableStateOf(false) }
    var draft by remember(f.id) { mutableStateOf("") }
    val shown by produceState(initialValue = "", f.value, f.isPrivate, revealed) {
        value = if (f.isPrivate && !revealed) "" else vm.reveal(f.value)
    }
    val stale = f.status == "active" && f.confirmedAt > 0 && System.currentTimeMillis() - f.confirmedAt > STALE_AFTER_MS

    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        if (f.isPrivate && !revealed) {
            Text(
                "•••••• hidden from the AI (tap to show here)",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { revealed = true },
            )
        } else {
            Text(shown, modifier = Modifier.clickable(enabled = f.isPrivate) { revealed = false })
        }
        val details = buildList {
            add(f.factKey)
            add("you said it ${dateOf(f.updatedAt)}")
            f.validUntil?.let { add("until ${dateOf(it)}") }
        }.joinToString(" · ")
        Text(details, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (stale) {
            Text(
                "Not confirmed for 6+ months. Zibi will double-check when it matters.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row {
            if (archived) {
                TextButton(onClick = { vm.restore(f) }) { Text("Restore") }
            } else {
                TextButton(onClick = { editing = true }) { Text("Edit") }
                TextButton(onClick = { vm.setHidden(f, !f.isPrivate) }) { Text(if (f.isPrivate) "Unhide" else "Hide from AI") }
            }
            TextButton(onClick = { vm.delete(f) }) { Text("Delete") }
        }
    }

    if (editing) {
        LaunchedEffect(Unit) { draft = vm.reveal(f.value) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(f.factKey) },
            text = { OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { vm.edit(f, draft); editing = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
}
