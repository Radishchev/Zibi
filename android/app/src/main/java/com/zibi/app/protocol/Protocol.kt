package com.zibi.app.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

/** Wire protocol v1. Mirrors /protocol/PROTOCOL.md and backend/zibi/protocol.py. */
val ZibiJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun newId(prefix: String): String = "${prefix}_${UUID.randomUUID().toString().replace("-", "").take(12)}"

object MsgType {
    const val HELLO = "hello"
    const val HELLO_ACK = "hello.ack"
    const val INPUT_TEXT = "input.text"
    const val CONTEXT_UPDATE = "context.update"
    const val TOOL_CALL = "tool.call"
    const val TOOL_RESULT = "tool.result"
    const val TOOL_ERROR = "tool.error"
    const val UI_ACTION = "ui.action"
    const val RESPONSE_CARD = "response.card"
    const val ERROR = "error"
}

@Serializable
data class Envelope(
    val v: Int = 1,
    val id: String = newId("msg"),
    val type: String,
    val session: String? = null,
    @SerialName("reply_to") val replyTo: String? = null,
    val ts: String = Instant.now().toString(),
    val payload: JsonObject = JsonObject(emptyMap()),
)

@Serializable
enum class Risk { READ, WRITE, HIGH_STAKES }

/** What the phone tells the server about one of its tools. */
@Serializable
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val risk: Risk = Risk.WRITE,
    val enabled: Boolean = true,
    val location: String = "device",
)

fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

fun helloEnvelope(
    deviceId: String,
    timezone: String,
    tools: List<ToolSpec>,
    context: JsonObject = JsonObject(emptyMap()),
): Envelope = Envelope(
    type = MsgType.HELLO,
    payload = buildJsonObject {
        put("device_id", deviceId)
        put("timezone", timezone)
        put("tools", ZibiJson.encodeToJsonElement(ListSerializer(ToolSpec.serializer()), tools))
        put("context", context)
    },
)

fun Envelope.toJson(): String = ZibiJson.encodeToString(Envelope.serializer(), this)

fun parseEnvelope(text: String): Envelope = ZibiJson.decodeFromString(Envelope.serializer(), text)
