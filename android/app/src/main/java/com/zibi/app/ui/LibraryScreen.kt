package com.zibi.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zibi.app.AppContainer
import com.zibi.app.appViewModel
import com.zibi.app.data.LibraryDoc
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(private val c: AppContainer) : ViewModel() {
    val docs: StateFlow<List<LibraryDoc>> = c.library.docs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    fun dismissNotice() { _notice.value = null }

    // run on the app scope so leaving this screen doesn't cancel an upload in progress
    fun add(uri: Uri) {
        c.scope.launch {
            try { c.library.add(uri) } catch (e: Exception) { _notice.value = e.message ?: "Couldn't add that file." }
        }
    }
    fun retry(d: LibraryDoc) { c.scope.launch { c.library.retry(d) } }
    fun delete(d: LibraryDoc) { c.scope.launch { c.library.delete(d) } }
}

@Composable
fun LibraryScreen(vm: LibraryViewModel = appViewModel { LibraryViewModel(it) }) {
    val docs by vm.docs.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.add(uri)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Library", style = MaterialTheme.typography.titleMedium)
        Text(
            "Add PDFs (notes, slides, papers), then ask Zibi about them in chat. For big requests like " +
                "\"teach me this topic\", Zibi prepares a prompt for ChatGPT or Claude with the files attached, " +
                "and you choose whether to send it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Text(
            "Privacy: PDFs are uploaded to your Zibi server and indexed. When you ask a question, the matching " +
                "passages are sent to the AI model to write the answer.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { picker.launch(arrayOf("application/pdf")) },
            modifier = Modifier.padding(vertical = 12.dp),
        ) { Text("Add PDF") }

        notice?.let { n ->
            Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(n, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = vm::dismissNotice) { Text("OK") }
                }
            }
        }

        LazyColumn(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(docs, key = { it.id }) { d ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(d.name, style = MaterialTheme.typography.bodyLarge)
                        when (d.status) {
                            "ready" -> Text(
                                "${d.pages} pages, indexed",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            "uploading" -> {
                                Text("Uploading and indexing…", style = MaterialTheme.typography.labelMedium)
                                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                            }
                            else -> Text(
                                d.error ?: "Failed",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Row {
                            if (d.status == "failed") TextButton(onClick = { vm.retry(d) }) { Text("Retry") }
                            TextButton(onClick = { vm.delete(d) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}
