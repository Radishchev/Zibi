package com.zibi.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zibi.app.AppContainer
import com.zibi.app.appViewModel
import com.zibi.app.data.Reminder
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RemindersViewModel(private val c: AppContainer) : ViewModel() {
    val reminders: StateFlow<List<Reminder>> = c.db.reminderDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setDone(r: Reminder, done: Boolean) { viewModelScope.launch { c.db.reminderDao().setDone(r.id, done) } }
    fun delete(r: Reminder) { viewModelScope.launch { c.db.reminderDao().deleteById(r.id) } }
}

@Composable
fun RemindersScreen(vm: RemindersViewModel = appViewModel { RemindersViewModel(it) }) {
    val reminders by vm.reminders.collectAsStateWithLifecycle()
    var showLeaving by remember { mutableStateOf(false) }
    val leaving = reminders.filter { !it.done && it.triggerType == "on_leaving_home" }

    Column(Modifier.fillMaxSize()) {
        Button(
            onClick = { showLeaving = true },
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        ) { Text("I'm leaving now (${leaving.size})") }

        if (reminders.isEmpty()) {
            Text(
                "No reminders yet. Ask Zibi in the chat.",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(reminders, key = { it.id }) { r ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = r.done, onCheckedChange = { vm.setDone(r, it) })
                    Column(Modifier.weight(1f)) {
                        Text(r.title, style = MaterialTheme.typography.bodyLarge)
                        Text(describe(r), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { vm.delete(r) }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                }
            }
        }
    }

    if (showLeaving) {
        AlertDialog(
            onDismissRequest = { showLeaving = false },
            confirmButton = { TextButton(onClick = { showLeaving = false }) { Text("Got it") } },
            title = { Text(if (leaving.isEmpty()) "All clear" else "Before you go") },
            text = {
                if (leaving.isEmpty()) Text("Nothing pending. Have a good one.")
                else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    leaving.forEach { Text("• ${it.title}") }
                }
            },
        )
    }
}

private fun describe(r: Reminder): String = when (r.triggerType) {
    "on_leaving_home" -> "Before leaving home"
    else -> listOfNotNull(r.dueDate, r.time).joinToString(" ").ifBlank { "No time set" }
}
