package com.zibi.app.tools

import com.zibi.app.data.ActionLogDao
import com.zibi.app.data.ActionLogEntry
import com.zibi.app.protocol.Risk
import com.zibi.app.protocol.ToolSpec
import com.zibi.app.protocol.ZibiJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

data class ToolResult(
    val ok: Boolean,
    val summary: String,                 // human-readable, shown in chat and History
    val result: JsonObject? = null,      // what the model sees
    val error: String? = null,
    val undoToken: String? = null,
    val uiRef: String? = null,           // e.g. "handoff:12": asks the chat to show a card for this thing
) {
    companion object {
        fun fail(message: String) = ToolResult(ok = false, summary = message, error = message)
    }
}

/** A capability the agent can use. Add a new app/feature = write one of these and register it in AppContainer. */
interface ZibiTool {
    val spec: ToolSpec
    suspend fun execute(args: JsonObject): ToolResult
    suspend fun undo(token: String): Boolean = false
}

fun schema(json: String): JsonObject = ZibiJson.parseToJsonElement(json).jsonObject

class ToolRegistry(tools: List<ZibiTool>) {
    private val byName = tools.associateBy { it.spec.name }
    fun get(name: String): ZibiTool? = byName[name]
    fun manifest(): List<ToolSpec> = byName.values.map { it.spec }
}

/**
 * Records that the user tapped "Yes" on a confirmation card. HIGH_STAKES tools refuse to run without
 * a fresh approval, even if the server asks: this is the on-device safety layer.
 */
class ApprovalLedger(private val clock: () -> Long = { System.currentTimeMillis() }) {
    private var approvedAt = 0L

    @Synchronized fun record() { approvedAt = clock() }

    @Synchronized fun consume(): Boolean {
        val fresh = approvedAt != 0L && clock() - approvedAt < WINDOW_MS
        approvedAt = 0L
        return fresh
    }

    companion object { const val WINDOW_MS = 5 * 60_000L }
}

class ToolDispatcher(
    private val registry: ToolRegistry,
    private val log: ActionLogDao,
    private val approvals: ApprovalLedger,
) {
    suspend fun execute(name: String, args: JsonObject): ToolResult {
        val tool = registry.get(name) ?: return ToolResult.fail("unknown tool: $name")
        // trust our own manifest for risk, never the server's claim
        if (tool.spec.risk == Risk.HIGH_STAKES && !approvals.consume()) {
            return ToolResult.fail("needs_user_approval")
        }
        val result = try {
            tool.execute(args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.fail("tool crashed: ${e.message}")
        }
        log.insert(
            ActionLogEntry(
                ts = System.currentTimeMillis(),
                tool = name,
                argsJson = args.toString(),
                ok = result.ok,
                summary = result.summary,
                undoToken = result.undoToken,
            )
        )
        return result
    }

    suspend fun undo(entry: ActionLogEntry): Boolean {
        val token = entry.undoToken ?: return false
        val tool = registry.get(entry.tool) ?: return false
        val ok = tool.undo(token)
        if (ok) log.markUndone(entry.id)
        return ok
    }
}
