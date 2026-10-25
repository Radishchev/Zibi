package com.zibi.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zibi.app.appViewModel
import com.zibi.app.library.HandoffTarget
import com.zibi.app.net.ConnState

@Composable
fun ChatScreen(vm: ChatViewModel = appViewModel { ChatViewModel(it) }) {
    val chat by vm.items.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val conn by vm.connection.collectAsStateWithLifecycle()
    val sharedText by vm.sharedIncomingText.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(sharedText) {
        val s = sharedText
        if (!s.isNullOrBlank()) {
            input = s
            vm.consumeSharedText()
        }
    }

    LaunchedEffect(chat.size) {
        if (chat.isNotEmpty()) listState.animateScrollToItem(chat.lastIndex)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        ConnectionBar(conn, onReconnect = vm::reconnect)
        if (chat.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "Just tell Zibi what's on your mind.\nTry: \"grocery list: milk, eggs. Tell Sam about project X. Fix the login bug in project X.\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(chat, key = { it.id }) { item -> ChatRow(item, onDecision = vm::decide, onAnswer = vm::answer, onHandoff = vm::openHandoff) }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Tell Zibi something…") },
                maxLines = 4,
            )
            IconButton(
                onClick = { vm.send(input); input = "" },
                enabled = input.isNotBlank(),
            ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
        }
    }
}

@Composable
private fun ConnectionBar(conn: ConnState, onReconnect: () -> Unit) {
    val label = when (conn) {
        ConnState.Connected -> "Connected"
        ConnState.Connecting -> "Connecting…"
        ConnState.Disconnected -> "Offline"
        is ConnState.Failed -> "Offline: ${conn.message}"
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        if (conn !is ConnState.Connected && conn !is ConnState.Connecting) {
            TextButton(onClick = onReconnect) { Text("Reconnect") }
        }
    }
}

@Composable
private fun ChatRow(
    item: ChatItem,
    onDecision: (String, Boolean) -> Unit,
    onAnswer: (String, String) -> Unit,
    onHandoff: (ChatItem.Handoff, HandoffTarget, String) -> Unit,
) {
    when (item) {
        is ChatItem.User -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(16.dp),
            ) { Text(item.text, Modifier.padding(12.dp)) }
        }
        is ChatItem.Assistant -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
            ) { Text(item.text, Modifier.padding(12.dp)) }
        }
        is ChatItem.ToolNote -> Text(
            (if (item.ok) "✓ " else "✗ ") + item.text,
            style = MaterialTheme.typography.labelMedium,
            color = if (item.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        is ChatItem.Problem -> Text(
            item.text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        is ChatItem.Handoff -> HandoffCard(item) { target, prompt -> onHandoff(item, target, prompt) }
        is ChatItem.Ask -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(item.question)
                if (item.answer == null) {
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        item.options.forEach { option ->
                            OutlinedButton(
                                onClick = { onAnswer(item.cardId, option) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(option) }
                        }
                    }
                } else {
                    Text(
                        "You: ${item.answer}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        is ChatItem.Confirm -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Zibi wants to do this:", style = MaterialTheme.typography.labelMedium)
                Text(item.summary, Modifier.padding(vertical = 8.dp))
                when (item.decision) {
                    null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onDecision(item.cardId, true) }) { Text("Yes") }
                        OutlinedButton(onClick = { onDecision(item.cardId, false) }) { Text("No") }
                    }
                    true -> Text("Approved", style = MaterialTheme.typography.labelMedium)
                    false -> Text("Declined", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun HandoffCard(item: ChatItem.Handoff, onOpen: (HandoffTarget, String) -> Unit) {
    var text by remember(item.handoffId) { mutableStateOf(item.prompt) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Prompt ready: ${item.title}", style = MaterialTheme.typography.labelLarge)
            if (item.files.isNotEmpty()) {
                Text(
                    "Files to attach: ${item.files.joinToString()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                maxLines = 8,
            )
            Text("Edit it if you like, then choose where to send it:", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onOpen(HandoffTarget.Claude, text) }) { Text("Claude") }
                Button(onClick = { onOpen(HandoffTarget.ChatGpt, text) }) { Text("ChatGPT") }
                OutlinedButton(onClick = { onOpen(HandoffTarget.Other, text) }) { Text("Other") }
            }
            item.outcome?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
