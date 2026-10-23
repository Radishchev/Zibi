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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

    Column(Modifier.fillMaxSize()) {
        Text("Reminders", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
        if (reminders.isEmpty()) {
            Text(
                "No reminders yet. Ask Zibi in the chat.",
                modifier = Modifier.padding(horizontal = 16.dp),
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
}

private fun describe(r: Reminder): String = when (r.triggerType) {
    "on_leaving_home" -> "Before leaving home (also shown in Quick)"
    else -> listOfNotNull(r.dueDate, r.time).joinToString(" ").ifBlank { "No time set" }
}
