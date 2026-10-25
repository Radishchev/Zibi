package com.zibi.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zibi.app.AppContainer
import com.zibi.app.library.HandoffTarget
import com.zibi.app.net.ClientEvent
import com.zibi.app.net.ConnState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    val connection: StateFlow<ConnState> = c.client.state
    val sharedIncomingText: StateFlow<String?> = c.sharedIncomingText.asStateFlow()

    private var nextId = 0L

    init {
        viewModelScope.launch { c.client.events.collect(::onEvent) }
        c.client.connect()
    }

    fun consumeSharedText() {
        c.sharedIncomingText.value = null
    }

    private fun add(make: (Long) -> ChatItem) {
        val item = make(nextId++)
        _items.update { it + item }
    }

    private fun onEvent(e: ClientEvent) {
        when (e) {
            is ClientEvent.Message -> { add { ChatItem.Assistant(it, e.text) }; _busy.value = false }
            is ClientEvent.Confirm -> add { ChatItem.Confirm(it, e.cardId, e.summary) }
            is ClientEvent.Ask -> add { ChatItem.Ask(it, e.cardId, e.question, e.options) }
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

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (connection.value != ConnState.Connected) {
            add { ChatItem.Problem(it, "Not connected to the server yet. Check Settings.") }
            return
        }
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
        if (!c.client.sendText(t)) {
            add { ChatItem.Problem(it, "Couldn't send. The connection dropped.") }
            _busy.value = false
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
}
