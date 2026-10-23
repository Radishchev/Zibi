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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zibi.app.appViewModel
import com.zibi.app.net.ConnState

@Composable
fun ChatScreen(vm: ChatViewModel = appViewModel { ChatViewModel(it) }) {
    val chat by vm.items.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val conn by vm.connection.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(chat.size) {
        if (chat.isNotEmpty()) listState.animateScrollToItem(chat.lastIndex)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        ConnectionBar(conn, onReconnect = vm::reconnect)
        if (chat.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "Tell Zibi something.\nTry: \"remind me to clip my nails before going out\"",
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
                items(chat, key = { it.id }) { item -> ChatRow(item, onDecision = vm::decide) }
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
private fun ChatRow(item: ChatItem, onDecision: (String, Boolean) -> Unit) {
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
