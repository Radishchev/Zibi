package com.zibi.app

import com.zibi.app.protocol.Envelope
import com.zibi.app.protocol.ToolSpec
import com.zibi.app.protocol.helloEnvelope
import com.zibi.app.protocol.parseEnvelope
import com.zibi.app.protocol.string
import com.zibi.app.protocol.toJson
import com.zibi.app.tools.schema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test
    fun replyToIsSnakeCaseOnTheWire() {
        val json = Envelope(type = "tool.result", replyTo = "tc_1").toJson()
        assertTrue(json, json.contains("\"reply_to\":\"tc_1\""))
    }

    @Test
    fun parsesServerMessagesAndIgnoresUnknownFields() {
        val env = parseEnvelope(
            """{"v":1,"id":"x","type":"tool.call","extra":1,
                "payload":{"tool":"create_reminder","args":{"title":"a"}}}"""
        )
        assertEquals("tool.call", env.type)
        assertEquals("create_reminder", env.payload.string("tool"))
    }

    @Test
    fun helloCarriesTheToolManifest() {
        val tool = ToolSpec(name = "t", description = "d", parameters = schema("""{"type":"object"}"""))
        val env = helloEnvelope("dev", "UTC", listOf(tool))
        val tools = env.payload["tools"] as JsonArray
        val first = tools[0] as JsonObject
        assertEquals("t", first.string("name"))
        assertEquals("WRITE", first.string("risk"))
        assertEquals("UTC", env.payload.string("timezone"))
    }
}
