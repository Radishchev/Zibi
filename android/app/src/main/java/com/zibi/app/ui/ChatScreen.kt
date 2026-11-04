package com.zibi.app.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zibi.app.appContainer
import com.zibi.app.library.HandoffTarget

@Composable
fun ChatScreen(vm: ChatViewModel = appContainer().chatVm) {
    val chat by vm.items.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val transcribing by vm.transcribing.collectAsStateWithLifecycle()
    val input by vm.draft.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(chat.size) {
        if (chat.isNotEmpty()) listState.animateScrollToItem(chat.lastIndex)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        if (chat.isEmpty()) {
            EmptyState(Modifier.weight(1f).fillMaxWidth()) { vm.draft.value = it }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(chat, key = { it.id }) { item -> ChatRow(item, onDecision = vm::decide, onAnswer = vm::answer, onHandoff = vm::openHandoff) }
            }
        }
        if (busy || transcribing) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                value = input,
                onValueChange = { vm.draft.value = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (recording) "Recording… tap the mic to stop" else "Tell Zibi something…") },
                maxLines = 4,
                shape = RoundedCornerShape(22.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            FilledTonalIconButton(
                onClick = vm::toggleRecording,
                enabled = !busy && !transcribing,
                colors = if (recording) {
                    IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    IconButtonDefaults.filledTonalIconButtonColors()
                },
            ) {
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = if (recording) "Stop recording" else "Record voice message",
                )
            }
            FilledIconButton(
                onClick = { vm.send(input) },
                enabled = input.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    val examples = listOf(
        "Grocery list: milk, eggs, coffee",
        "Tell Sam about project X",
        "Remind me to call the dentist Friday",
    )
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "What should Zibi take care of?",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "Say it, type it, or tap an example.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Column(
            Modifier.padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            examples.forEach { example ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.clickable { onPick(example) },
                ) {
                    Text(
                        example,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
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
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
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
