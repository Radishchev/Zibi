package com.zibi.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zibi.app.AppContainer
import com.zibi.app.appViewModel
import com.zibi.app.data.Handoff
import com.zibi.app.data.ListEntity
import com.zibi.app.library.HandoffTarget
import com.zibi.app.data.ListItem
import com.zibi.app.data.MessageDraft
import com.zibi.app.data.Reminder
import com.zibi.app.data.Task
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ListWithItems(val list: ListEntity, val items: List<ListItem>)

data class QuickState(
    val leaving: List<Reminder> = emptyList(),
    val drafts: List<MessageDraft> = emptyList(),
    val lists: List<ListWithItems> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val handoffs: List<Handoff> = emptyList(),
)

class QuickViewModel(private val c: AppContainer) : ViewModel() {
    private val core = combine(
        c.db.listDao().observeLists(),
        c.db.listDao().observeItems(),
        c.db.taskDao().observeAll(),
        c.db.draftDao().observePending(),
        c.db.reminderDao().observeAll(),
    ) { lists, items, tasks, drafts, reminders ->
        QuickState(
            leaving = reminders.filter { !it.done && it.triggerType == "on_leaving_home" },
            drafts = drafts,
            lists = lists.map { l -> ListWithItems(l, items.filter { it.listId == l.id }) },
            tasks = tasks,
        )
    }

    val state: StateFlow<QuickState> = combine(core, c.db.handoffDao().observePending()) { s, handoffs ->
        s.copy(handoffs = handoffs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QuickState())

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    fun dismissNotice() { _notice.value = null }

    fun toggleItem(i: ListItem) { viewModelScope.launch { c.db.listDao().setItemDone(i.id, !i.done) } }
    fun clearTicked(l: ListEntity) { viewModelScope.launch { c.db.listDao().clearDone(l.id) } }
    fun deleteList(l: ListEntity) { viewModelScope.launch { c.db.listDao().deleteList(l.id) } }

    fun toggleTask(t: Task) { viewModelScope.launch { c.db.taskDao().setDone(t.id, !t.done) } }
    fun deleteTask(t: Task) { viewModelScope.launch { c.db.taskDao().deleteById(t.id) } }

    fun finishReminder(r: Reminder) { viewModelScope.launch { c.db.reminderDao().setDone(r.id, true) } }

    /** Opens the saved message in SMS/WhatsApp. Goes through the dispatcher so it shows up in History. */
    fun openDraft(d: MessageDraft, app: String) {
        viewModelScope.launch {
            val result = c.dispatcher.execute(
                "send_message",
                buildJsonObject { put("to", d.recipient); put("text", d.text); put("app", app) },
            )
            if (result.ok) c.db.draftDao().markOpened(d.id, app, System.currentTimeMillis())
            else _notice.value = result.summary
        }
    }
    fun openHandoff(h: Handoff, target: HandoffTarget) {
        viewModelScope.launch { _notice.value = c.handoffs.open(h.id, target, h.prompt) }
    }
    fun dismissHandoff(h: Handoff) { viewModelScope.launch { c.db.handoffDao().dismiss(h.id) } }
    fun markDraftSent(d: MessageDraft) { viewModelScope.launch { c.db.draftDao().setDone(d.id, true) } }
    fun deleteDraft(d: MessageDraft) { viewModelScope.launch { c.db.draftDao().deleteById(d.id) } }
}

@Composable
fun QuickScreen(vm: QuickViewModel = appViewModel { QuickViewModel(it) }) {
    val s by vm.state.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val nothing = s.leaving.isEmpty() && s.drafts.isEmpty() && s.lists.isEmpty() && s.tasks.isEmpty() &&
        s.handoffs.isEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        notice?.let { n ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(n, Modifier.weight(1f))
                        TextButton(onClick = vm::dismissNotice) { Text("OK") }
                    }
                }
            }
        }
        if (nothing) {
            item {
                Text(
                    "Nothing here yet.\nTell Zibi things in the Chat tab and they show up here: lists you can tick off, " +
                        "tasks, and messages ready to send.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        if (s.handoffs.isNotEmpty()) {
            item {
                SectionCard("Ready to hand off") {
                    s.handoffs.forEach { h ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(h.title, style = MaterialTheme.typography.labelLarge)
                            Text(h.prompt, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { vm.openHandoff(h, HandoffTarget.Claude) }) { Text("Claude") }
                                Button(onClick = { vm.openHandoff(h, HandoffTarget.ChatGpt) }) { Text("ChatGPT") }
                                OutlinedButton(onClick = { vm.openHandoff(h, HandoffTarget.Other) }) { Text("Other") }
                            }
                            TextButton(onClick = { vm.dismissHandoff(h) }) { Text("Dismiss") }
                        }
                    }
                }
            }
        }
        if (s.leaving.isNotEmpty()) {
            item {
                SectionCard("Before you go") {
                    s.leaving.forEach { r -> CheckRow(r.title, checked = false, onToggle = { vm.finishReminder(r) }) }
                }
            }
        }
        if (s.drafts.isNotEmpty()) {
            item {
                SectionCard("Messages to send") {
                    s.drafts.forEach { d -> DraftRow(d, vm) }
                }
            }
        }
        s.lists.forEach { lw ->
            item(key = "list-${lw.list.id}") {
                val open = lw.items.count { !it.done }
                SectionCard("${lw.list.name} ($open)") {
                    if (lw.items.isEmpty()) {
                        Text("Empty", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    lw.items.forEach { i -> CheckRow(i.text, i.done, onToggle = { vm.toggleItem(i) }) }
                    Row {
                        if (lw.items.any { it.done }) {
                            TextButton(onClick = { vm.clearTicked(lw.list) }) { Text("Clear ticked") }
                        }
                        TextButton(onClick = { vm.deleteList(lw.list) }) { Text("Delete list") }
                    }
                }
            }
        }
        if (s.tasks.isNotEmpty()) {
            item {
                SectionCard("Tasks") {
                    s.tasks.forEach { t ->
                        CheckRow(
                            text = t.title,
                            checked = t.done,
                            subtitle = t.project,
                            onToggle = { vm.toggleTask(t) },
                            onDelete = { vm.deleteTask(t) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            content()
        }
    }
}

@Composable
private fun CheckRow(
    text: String,
    checked: Boolean,
    onToggle: () -> Unit,
    subtitle: String? = null,
    onDelete: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(text, textDecoration = if (checked) TextDecoration.LineThrough else null)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
        }
    }
}

@Composable
private fun DraftRow(d: MessageDraft, vm: QuickViewModel) {
    val app = d.app
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("To: ${d.recipient}", style = MaterialTheme.typography.labelLarge)
        Text(d.text, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (app == null) {
                OutlinedButton(onClick = { vm.openDraft(d, "sms") }) { Text("SMS") }
                OutlinedButton(onClick = { vm.openDraft(d, "whatsapp") }) { Text("WhatsApp") }
            } else {
                val label = if (app == "whatsapp") "WhatsApp" else "SMS"
                Button(onClick = { vm.openDraft(d, app) }) {
                    Text(if (d.openedAt == null) "Message via $label" else "Open $label again")
                }
            }
        }
        Row {
            if (d.openedAt != null) TextButton(onClick = { vm.markDraftSent(d) }) { Text("Mark as sent") }
            TextButton(onClick = { vm.deleteDraft(d) }) { Text("Delete") }
        }
    }
}
