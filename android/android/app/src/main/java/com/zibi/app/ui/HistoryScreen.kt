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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.zibi.app.data.ActionLogEntry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class HistoryViewModel(private val c: AppContainer) : ViewModel() {
    val entries: StateFlow<List<ActionLogEntry>> = c.db.actionLogDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun undo(entry: ActionLogEntry) { viewModelScope.launch { c.dispatcher.undo(entry) } }
}

private val timeFormat = DateTimeFormatter.ofPattern("d MMM, HH:mm")

@Composable
fun HistoryScreen(vm: HistoryViewModel = appViewModel { HistoryViewModel(it) }) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Text("What Zibi did", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
        if (entries.isEmpty()) {
            Text(
                "Nothing yet. Every action Zibi takes will be listed here, with undo where possible.",
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries, key = { it.id }) { e ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text((if (e.ok) "" else "Failed: ") + e.summary)
                            Text(
                                "${e.tool} · " + Instant.ofEpochMilli(e.ts).atZone(ZoneId.systemDefault()).format(timeFormat),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        when {
                            e.undone -> Text("Undone", style = MaterialTheme.typography.labelMedium)
                            e.undoToken != null -> OutlinedButton(onClick = { vm.undo(e) }) { Text("Undo") }
                        }
                    }
                }
            }
        }
    }
}
