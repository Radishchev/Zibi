package com.zibi.app.ui

import android.Manifest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zibi.app.AppContainer
import com.zibi.app.library.HandoffTarget
import com.zibi.app.net.ClientEvent
import com.zibi.app.net.ConnState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface ChatItem {
    val id: Long
    data class User(override val id: Long, val text: String) : ChatItem
    data class Assistant(override val id: Long, val text: String) : ChatItem
    data class ToolNote(override val id: Long, val text: String, val ok: Boolean) : ChatItem
    data class Confirm(
        override val id: Long,
        val cardId: String,
        val summary: String,
        val decision: Boolean? = null,
    ) : ChatItem
    data class Ask(
        override val id: Long,
        val cardId: String,
        val question: String,
        val options: List<String>,
        val answer: String? = null,
    ) : ChatItem
    data class Handoff(
        override val id: Long,
        val handoffId: Long,
        val title: String,
        val prompt: String,
        val files: List<String>,
        val outcome: String? = null,
    ) : ChatItem
    data class Problem(override val id: Long, val text: String) : ChatItem
}

class ChatViewModel(private val c: AppContainer) : ViewModel() {
    private val _items = MutableStateFlow<List<ChatItem>>(emptyList())
    val items: StateFlow<List<ChatItem>> = _items.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _transcribing = MutableStateFlow(false)
    val transcribing: StateFlow<Boolean> = _transcribing.asStateFlow()

    /** Text in the input box; voice transcription fills this so the user can review before sending. */
    val draft = MutableStateFlow("")

    val connection: StateFlow<ConnState> = c.client.state

    private var nextId = 0L

    init {
        viewModelScope.launch { c.client.events.collect { onEvent(it) } }
        viewModelScope.launch { c.client.state.collect { clearConnectionProblems(it) } }
        c.client.connect()
    }

    /** When connection is restored, remove transient connection-related Problem items. */
    private fun clearConnectionProblems(state: ConnState) {
        if (state is ConnState.Connected) {
            _items.update { list ->
                list.filter { it !is ChatItem.Problem }
            }
        }
    }

    private fun add(make: (Long) -> ChatItem) {
        val item = make(nextId++)
        _items.update { it + item }
    }

    // The AI works with placeholders like [PHONE_1]; the user is shown the real values, on this phone only.
    private suspend fun show(text: String) = c.vault.resolve(text)

    private suspend fun onEvent(e: ClientEvent) {
        when (e) {
            is ClientEvent.Message -> { val t = show(e.text); add { ChatItem.Assistant(it, t) }; _busy.value = false }
            is ClientEvent.Confirm -> { val t = show(e.summary); add { ChatItem.Confirm(it, e.cardId, t) } }
            is ClientEvent.Ask -> {
                val q = show(e.question)
                val options = e.options.map { show(it) }
                add { ChatItem.Ask(it, e.cardId, q, options) }
            }
            is ClientEvent.ToolRan -> {
                add { ChatItem.ToolNote(it, e.summary, e.ok) }
                e.uiRef?.let(::showUiRef)
            }
            is ClientEvent.Problem -> { add { ChatItem.Problem(it, e.message) }; _busy.value = false }
        }
    }

    /** A tool can ask the chat to show a richer card for what it created. */
    private fun showUiRef(ref: String) {
        if (!ref.startsWith("handoff:")) return
        val handoffId = ref.removePrefix("handoff:").toLongOrNull() ?: return
        viewModelScope.launch {
            val h = c.db.handoffDao().get(handoffId) ?: return@launch
            val files = h.docIds.split(",").mapNotNull { it.toLongOrNull() }
                .mapNotNull { c.db.libraryDao().get(it)?.name }
            add { ChatItem.Handoff(it, h.id, h.title, h.prompt, files) }
        }
    }

    fun openHandoff(item: ChatItem.Handoff, target: HandoffTarget, prompt: String) {
        viewModelScope.launch {
            val message = c.handoffs.open(item.handoffId, target, prompt)
            _items.update { list ->
                list.map { if (it is ChatItem.Handoff && it.id == item.id) it.copy(outcome = message) else it }
            }
        }
    }

    fun processSharedContent(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        _busy.value = true
        viewModelScope.launch {
            val connected = withTimeoutOrNull(30_000) { connection.first { it == ConnState.Connected } } != null
            if (!connected || !c.client.sendText(t, source = "share")) {
                draft.value = t
                add { ChatItem.Problem(it, "Couldn't send the shared text yet. It's in the input box; tap send when connected.") }
                _busy.value = false
                return@launch
            }
            add { ChatItem.User(it, t) }
        }
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (connection.value != ConnState.Connected) {
            add { ChatItem.Problem(it, "Not connected to the server yet. Check Settings.") }
            return
        }
        if (t == draft.value.trim()) draft.value = ""
        // typing instead of tapping answers an open question and declines a pending confirmation
        _items.update { list ->
            list.map {
                when {
                    it is ChatItem.Ask && it.answer == null -> it.copy(answer = t)
                    it is ChatItem.Confirm && it.decision == null -> it.copy(decision = false)
                    else -> it
                }
            }
        }
        add { ChatItem.User(it, t) }
        _busy.value = true
        viewModelScope.launch {
            if (!c.client.sendText(t)) {
                add { ChatItem.Problem(it, "Couldn't send. The connection dropped.") }
                _busy.value = false
            }
        }
    }

    fun decide(cardId: String, approve: Boolean) {
        _items.update { list ->
            list.map { if (it is ChatItem.Confirm && it.cardId == cardId) it.copy(decision = approve) else it }
        }
        if (approve) c.approvals.record()
        c.client.sendUiAction(cardId, if (approve) "approve" else "deny")
    }

    fun answer(cardId: String, option: String) {
        _items.update { list ->
            list.map { if (it is ChatItem.Ask && it.cardId == cardId) it.copy(answer = option) else it }
        }
        c.client.sendUiAction(cardId, option)
    }

    fun reconnect() = c.client.reconnect()

    /** Mic button: tap to start recording, tap again to transcribe into the input box. */
    fun toggleRecording() {
        if (_recording.value) stopRecording() else startRecording()
    }

    fun startRecording() {
        viewModelScope.launch {
            if (!c.permissions.ensure(Manifest.permission.RECORD_AUDIO)) {
                add { ChatItem.Problem(it, "Zibi needs the microphone permission for voice messages.") }
                return@launch
            }
            try {
                c.voiceRecorder.start()
                _recording.value = true
            } catch (e: Exception) {
                add { ChatItem.Problem(it, "Couldn't start the microphone: ${e.message}") }
            }
        }
    }

    fun stopRecording() = finishRecording(sendImmediately = false)

    fun stopRecordingAndSend() = finishRecording(sendImmediately = true)

    private fun finishRecording(sendImmediately: Boolean) {
        _recording.value = false
        val bytes = try {
            c.voiceRecorder.stop()
        } catch (e: Exception) {
            add { ChatItem.Problem(it, "Recording failed: ${e.message}") }
            return
        }
        if (bytes == null) return
        if (connection.value != ConnState.Connected) {
            add { ChatItem.Problem(it, "Not connected to the server yet. Check Settings.") }
            return
        }
        viewModelScope.launch {
            _transcribing.value = true
            try {
                val text = c.stt.transcribe("voice.m4a", bytes).trim()
                if (text.isEmpty()) {
                    add { ChatItem.Problem(it, "I couldn't make out any words — try again?") }
                } else if (sendImmediately) {
                    send(text)
                } else {
                    draft.value = if (draft.value.isBlank()) text else draft.value.trim() + " " + text
                }
            } catch (e: Exception) {
                add { ChatItem.Problem(it, e.message ?: "Transcription failed.") }
            } finally {
                _transcribing.value = false
            }
        }
    }
}
