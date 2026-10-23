package com.zibi.app.net

import android.net.Uri
import com.zibi.app.data.SettingsRepository
import com.zibi.app.protocol.Envelope
import com.zibi.app.protocol.MsgType
import com.zibi.app.protocol.helloEnvelope
import com.zibi.app.protocol.parseEnvelope
import com.zibi.app.protocol.string
import com.zibi.app.protocol.toJson
import com.zibi.app.tools.ToolDispatcher
import com.zibi.app.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.min

sealed interface ConnState {
    data object Disconnected : ConnState
    data object Connecting : ConnState
    data object Connected : ConnState
    data class Failed(val message: String) : ConnState
}

/** What the UI cares about. Everything else (tool calls, handshakes) is handled inside the client. */
sealed interface ClientEvent {
    data class Message(val text: String) : ClientEvent
    data class Confirm(val cardId: String, val summary: String) : ClientEvent
    data class ToolRan(val tool: String, val ok: Boolean, val summary: String) : ClientEvent
    data class Problem(val message: String) : ClientEvent
}

/**
 * The phone's connection to the Zibi backend. Speaks the JSON protocol, executes tool calls
 * from the server through [ToolDispatcher], and reconnects with backoff.
 */
class ZibiClient(
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val registry: ToolRegistry,
    private val dispatcher: ToolDispatcher,
    private val deviceId: String,
) {
    private val http = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var generation = 0
    @Volatile private var wantConnected = false
    private var retryJob: Job? = null
    private var attempt = 0

    private val _state = MutableStateFlow<ConnState>(ConnState.Disconnected)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ClientEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ClientEvent> = _events.asSharedFlow()

    fun connect() {
        val s = _state.value
        if (wantConnected && (s is ConnState.Connected || s is ConnState.Connecting)) return
        wantConnected = true
        retryJob?.cancel()
        scope.launch { open() }
    }

    fun disconnect() {
        wantConnected = false
        retryJob?.cancel()
        generation++
        socket?.close(1000, "bye")
        socket = null
        _state.value = ConnState.Disconnected
    }

    fun reconnect() {
        disconnect()
        connect()
    }

    fun sendText(text: String): Boolean {
        val env = Envelope(type = MsgType.INPUT_TEXT, payload = buildJsonObject { put("text", text) })
        return socket?.send(env.toJson()) ?: false
    }

    fun sendUiAction(replyTo: String, actionId: String) {
        val env = Envelope(
            type = MsgType.UI_ACTION,
            replyTo = replyTo,
            payload = buildJsonObject { put("action_id", actionId) },
        )
        socket?.send(env.toJson())
    }

    // --- internals ---------------------------------------------------------------

    private suspend fun open() {
        socket?.cancel()
        val gen = ++generation
        _state.value = ConnState.Connecting
        val url = buildUrl(settings.serverUrl.first(), settings.token.first())
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            wantConnected = false
            _state.value = ConnState.Failed("Invalid server URL")
            return
        }
        socket = http.newWebSocket(request, listener(gen))
    }

    private fun buildUrl(base: String, token: String): String {
        if (token.isBlank()) return base
        val sep = if (base.contains("?")) "&" else "?"
        return base + sep + "token=" + Uri.encode(token)
    }

    private fun scheduleRetry() {
        if (!wantConnected) return
        retryJob?.cancel()
        retryJob = scope.launch {
            attempt++
            delay(min(30_000L, 1_000L shl min(attempt, 5)))
            if (wantConnected) open()
        }
    }

    private fun listener(gen: Int) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (gen != generation) return
            // handshake: tell the server which tools this phone offers
            webSocket.send(
                helloEnvelope(
                    deviceId = deviceId,
                    timezone = ZoneId.systemDefault().id,
                    tools = registry.manifest(),
                ).toJson()
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (gen != generation) return
            scope.launch { handle(webSocket, text) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (gen != generation) return
            _state.value = ConnState.Disconnected
            scheduleRetry()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (gen != generation) return
            val code = response?.code
            if (code == 401 || code == 403) {
                wantConnected = false // retrying won't fix a wrong token
                _state.value = ConnState.Failed("Server rejected the token. Check Settings.")
                return
            }
            _state.value = ConnState.Failed(t.message ?: "Connection failed")
            scheduleRetry()
        }
    }

    private suspend fun handle(ws: WebSocket, text: String) {
        val env = try {
            parseEnvelope(text)
        } catch (e: IllegalArgumentException) {
            _events.tryEmit(ClientEvent.Problem("Received a message I couldn't read"))
            return
        }
        when (env.type) {
            MsgType.HELLO_ACK -> {
                attempt = 0
                _state.value = ConnState.Connected
            }
            MsgType.TOOL_CALL -> runToolCall(ws, env)
            MsgType.RESPONSE_CARD -> when (env.payload.string("card")) {
                "message" -> _events.tryEmit(ClientEvent.Message(env.payload.string("text") ?: ""))
                "confirm" -> _events.tryEmit(
                    ClientEvent.Confirm(cardId = env.id, summary = env.payload.string("summary") ?: "Confirm?")
                )
            }
            MsgType.ERROR -> _events.tryEmit(ClientEvent.Problem(env.payload.string("message") ?: "Server error"))
        }
    }

    private suspend fun runToolCall(ws: WebSocket, env: Envelope) {
        val name = env.payload.string("tool") ?: "?"
        val args = env.payload["args"] as? JsonObject ?: JsonObject(emptyMap())
        val result = dispatcher.execute(name, args)
        _events.tryEmit(ClientEvent.ToolRan(name, result.ok, result.summary))

        val reply = if (result.ok) {
            Envelope(
                type = MsgType.TOOL_RESULT,
                replyTo = env.id,
                payload = buildJsonObject {
                    put("ok", true)
                    put("result", result.result ?: JsonObject(emptyMap()))
                    result.undoToken?.let { put("undo_token", it) }
                },
            )
        } else {
            Envelope(
                type = MsgType.TOOL_ERROR,
                replyTo = env.id,
                payload = buildJsonObject { put("error", result.error ?: "failed") },
            )
        }
        ws.send(reply.toJson())
    }
}
