package com.zibi.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zibi.app.AppContainer
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
    data class Problem(override val id: Long, val text: String) : ChatItem
}

class ChatViewModel(private val c: AppContainer) : ViewModel() {
    private val _items = MutableStateFlow<List<ChatItem>>(emptyList())
    val items: StateFlow<List<ChatItem>> = _items.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    val connection: StateFlow<ConnState> = c.client.state

    private var nextId = 0L

    init {
        viewModelScope.launch { c.client.events.collect(::onEvent) }
        c.client.connect()
    }

    private fun add(make: (Long) -> ChatItem) {
        val item = make(nextId++)
        _items.update { it + item }
    }

    private fun onEvent(e: ClientEvent) {
        when (e) {
            is ClientEvent.Message -> { add { ChatItem.Assistant(it, e.text) }; _busy.value = false }
            is ClientEvent.Confirm -> add { ChatItem.Confirm(it, e.cardId, e.summary) }
            is ClientEvent.ToolRan -> add { ChatItem.ToolNote(it, e.summary, e.ok) }
            is ClientEvent.Problem -> { add { ChatItem.Problem(it, e.message) }; _busy.value = false }
        }
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (connection.value != ConnState.Connected) {
            add { ChatItem.Problem(it, "Not connected to the server yet. Check Settings.") }
            return
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

    fun reconnect() = c.client.reconnect()
}
