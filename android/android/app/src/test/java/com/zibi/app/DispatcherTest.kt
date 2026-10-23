package com.zibi.app

import com.zibi.app.data.ActionLogDao
import com.zibi.app.data.ActionLogEntry
import com.zibi.app.protocol.Risk
import com.zibi.app.protocol.ToolSpec
import com.zibi.app.tools.ApprovalLedger
import com.zibi.app.tools.ToolDispatcher
import com.zibi.app.tools.ToolRegistry
import com.zibi.app.tools.ToolResult
import com.zibi.app.tools.ZibiTool
import com.zibi.app.tools.schema
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeLog : ActionLogDao {
    val rows = mutableListOf<ActionLogEntry>()
    val undone = mutableListOf<Long>()
    override suspend fun insert(entry: ActionLogEntry): Long {
        rows += entry.copy(id = rows.size + 1L)
        return rows.size.toLong()
    }
    override fun observeAll(): Flow<List<ActionLogEntry>> = flowOf(rows)
    override suspend fun markUndone(id: Long) { undone += id }
}

private class FakeTool(risk: Risk, private val crash: Boolean = false) : ZibiTool {
    var runs = 0
    var undoneToken: String? = null
    override val spec = ToolSpec("fake", "d", schema("""{"type":"object"}"""), risk)
    override suspend fun execute(args: JsonObject): ToolResult {
        runs++
        if (crash) error("boom")
        return ToolResult(true, "did it", undoToken = "tok")
    }
    override suspend fun undo(token: String): Boolean { undoneToken = token; return true }
}

class DispatcherTest {
    private fun setup(tool: FakeTool, approvals: ApprovalLedger = ApprovalLedger()): Triple<ToolDispatcher, FakeLog, ApprovalLedger> {
        val log = FakeLog()
        return Triple(ToolDispatcher(ToolRegistry(listOf(tool)), log, approvals), log, approvals)
    }

    @Test
    fun unknownToolFailsCleanly() = runTest {
        val (d, _, _) = setup(FakeTool(Risk.WRITE))
        val r = d.execute("nope", JsonObject(emptyMap()))
        assertFalse(r.ok)
    }

    @Test
    fun successIsLoggedWithUndoToken() = runTest {
        val (d, log, _) = setup(FakeTool(Risk.WRITE))
        val r = d.execute("fake", JsonObject(emptyMap()))
        assertTrue(r.ok)
        assertEquals("tok", log.rows.single().undoToken)
    }

    @Test
    fun highStakesRefusesWithoutApprovalAndNeedsAFreshOneEachTime() = runTest {
        val tool = FakeTool(Risk.HIGH_STAKES)
        val (d, _, approvals) = setup(tool)

        assertFalse(d.execute("fake", JsonObject(emptyMap())).ok)
        assertEquals(0, tool.runs)

        approvals.record()
        assertTrue(d.execute("fake", JsonObject(emptyMap())).ok)
        assertEquals(1, tool.runs)

        // the approval was consumed
        assertFalse(d.execute("fake", JsonObject(emptyMap())).ok)
        assertEquals(1, tool.runs)
    }

    @Test
    fun staleApprovalIsRejected() = runTest {
        var now = 1_000L
        val approvals = ApprovalLedger { now }
        val tool = FakeTool(Risk.HIGH_STAKES)
        val (d, _, _) = setup(tool, approvals)
        approvals.record()
        now += ApprovalLedger.WINDOW_MS + 1
        assertFalse(d.execute("fake", JsonObject(emptyMap())).ok)
        assertEquals(0, tool.runs)
    }

    @Test
    fun crashingToolBecomesAFailureNotAnException() = runTest {
        val (d, _, _) = setup(FakeTool(Risk.WRITE, crash = true))
        val r = d.execute("fake", JsonObject(emptyMap()))
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("boom"))
    }

    @Test
    fun undoCallsTheToolAndMarksTheEntry() = runTest {
        val tool = FakeTool(Risk.WRITE)
        val (d, log, _) = setup(tool)
        d.execute("fake", JsonObject(emptyMap()))
        assertTrue(d.undo(log.rows.single()))
        assertEquals("tok", tool.undoneToken)
        assertEquals(listOf(1L), log.undone)
    }
}
