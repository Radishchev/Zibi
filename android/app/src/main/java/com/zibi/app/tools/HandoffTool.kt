package com.zibi.app.tools

import com.zibi.app.data.Handoff
import com.zibi.app.data.HandoffDao
import com.zibi.app.data.LibraryDao
import com.zibi.app.data.LibraryDoc
import com.zibi.app.protocol.Risk
import com.zibi.app.protocol.ToolSpec
import com.zibi.app.protocol.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** "notes.pdf", "Notes", and "my notes" should find the same document. */
fun findDocument(name: String, docs: List<LibraryDoc>): LibraryDoc? {
    fun norm(s: String) = s.lowercase().removeSuffix(".pdf").replace(Regex("[^a-z0-9]+"), " ").trim()
    val wanted = norm(name)
    if (wanted.isEmpty()) return null
    return docs.firstOrNull { norm(it.name) == wanted }
        ?: docs.firstOrNull { norm(it.name).contains(wanted) || wanted.contains(norm(it.name)) }
}

/**
 * For BIG requests (teach me a topic, summarise a whole document, write an essay). Zibi doesn't do these itself.
 * It writes a complete prompt, and the user chooses whether and where to send it. Nothing is sent by this tool.
 */
class ProposeHandoffTool(
    private val library: LibraryDao,
    private val handoffs: HandoffDao,
) : ZibiTool {
    override val spec = ToolSpec(
        name = "propose_handoff",
        description = "Prepare a prompt for the user to send to ChatGPT or Claude for a BIG request (teaching a " +
            "whole topic, summarising a whole document, essays, study plans). Does NOT send anything: the user " +
            "reviews the prompt and picks the app. 'prompt' must be complete and self-contained. 'documents' lists " +
            "library files to attach, by exact name.",
        parameters = schema(
            """
            {"type":"object",
             "properties":{
               "title":{"type":"string","description":"short label, e.g. 'Learn backpropagation'"},
               "prompt":{"type":"string"},
               "documents":{"type":"array","items":{"type":"string"}}},
             "required":["title","prompt"],
             "additionalProperties":false}
            """.trimIndent()
        ),
        risk = Risk.WRITE,
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val title = args.string("title")?.trim()?.takeIf { it.isNotEmpty() } ?: return ToolResult.fail("title is required")
        val prompt = args.string("prompt")?.trim()?.takeIf { it.isNotEmpty() } ?: return ToolResult.fail("prompt is required")
        val wanted = (args["documents"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
            .orEmpty()

        val all = library.all()
        val found = mutableListOf<LibraryDoc>()
        val missing = mutableListOf<String>()
        for (name in wanted) {
            val doc = findDocument(name, all)
            if (doc == null) missing += name else if (doc !in found) found += doc
        }
        if (missing.isNotEmpty()) {
            val available = all.joinToString { it.name }.ifEmpty { "none" }
            return ToolResult.fail("no library document named ${missing.joinToString()}. Available: $available")
        }

        val id = handoffs.insert(
            Handoff(
                title = title, prompt = prompt, docIds = found.joinToString(",") { it.id.toString() },
                createdAt = System.currentTimeMillis(), openedAt = null, done = false,
            )
        )
        return ToolResult(
            ok = true,
            summary = "Prepared a prompt for ChatGPT/Claude: $title",
            result = buildJsonObject {
                put("handoff_id", id)
                put("status", "prepared_user_chooses_where_to_send")
                put("attached", buildJsonArray { found.forEach { add(JsonPrimitive(it.name)) } })
            },
            undoToken = id.toString(),
            uiRef = "handoff:$id",
        )
    }

    override suspend fun undo(token: String): Boolean {
        val id = token.toLongOrNull() ?: return false
        return handoffs.deleteById(id) > 0
    }
}
