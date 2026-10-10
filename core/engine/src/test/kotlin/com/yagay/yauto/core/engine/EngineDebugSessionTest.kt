package com.yagay.yauto.core.engine

import com.yagay.yauto.core.model.ActionNode
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.NodeId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class EngineDebugSessionTest {
    @Test fun stepPausesThenRecordsResult() = runBlocking {
        val debugger = EngineDebugSession()
        val node = ActionNode.Label(
            id = NodeId("debug-label"),
            name = "mark",
        )
        val waiting = async {
            debugger.beforeNode(1L, node, mapOf("counter" to ConfigValue.StringValue("before")))
            debugger.afterNode(
                1L, node, mapOf("counter" to ConfigValue.StringValue("after")),
                true, 17L,
            )
        }
        repeat(100) {
            if (debugger.pausedAt() != null) return@repeat
            yield()
        }
        assertNotNull(debugger.pausedAt())
        assertFalse(waiting.isCompleted)
        debugger.step()
        waiting.await()
        val steps = debugger.snapshot()
        assertEquals(1, steps.size)
        assertEquals(1L, steps.single().invocationId)
        assertTrue(steps.single().success)
        assertEquals(17L, steps.single().elapsedMs)
        assertEquals(ConfigValue.StringValue("before"), steps.single().variablesBefore["counter"])
        assertEquals(ConfigValue.StringValue("after"), steps.single().variablesAfter["counter"])
    }
    @Test fun concurrentIdenticalNodeInvocationsAreNotOverwritten() = runBlocking {
        val debugger = EngineDebugSession()
        val node = ActionNode.Label(NodeId("shared-node"), "loop")
        val first = async {
            debugger.beforeNode(10L, node, mapOf("iteration" to ConfigValue.StringValue("one")))
            debugger.afterNode(10L, node, emptyMap(), true, 12L)
        }
        val second = async {
            debugger.beforeNode(11L, node, mapOf("iteration" to ConfigValue.StringValue("two")))
            debugger.afterNode(11L, node, emptyMap(), true, 14L)
        }
        repeat(100) { yield() }
        assertNotNull(debugger.pausedAt())
        debugger.step()
        repeat(100) { yield() }
        assertNotNull(debugger.pausedAt())
        debugger.step()
        first.await()
        second.await()
        val steps = debugger.snapshot().associateBy { it.invocationId }
        assertEquals(2, steps.size)
        assertEquals(ConfigValue.StringValue("one"), steps[10L]?.variablesBefore?.get("iteration"))
        assertEquals(ConfigValue.StringValue("two"), steps[11L]?.variablesBefore?.get("iteration"))
    }

    @Test fun continueRunsUntilConfiguredBreakpoint() = runBlocking {
        val debugger = EngineDebugSession()
        val regular = ActionNode.Label(NodeId("regular-node"), "regular")
        val stop = ActionNode.Label(NodeId("breakpoint-node"), "stop")
        debugger.setBreakpoint(stop.id, true)
        debugger.continueExecution()

        debugger.beforeNode(1L, regular, emptyMap())
        debugger.afterNode(1L, regular, emptyMap(), true, 1L)

        val stopped = async {
            debugger.beforeNode(2L, stop, emptyMap())
            debugger.afterNode(2L, stop, emptyMap(), true, 2L)
        }
        repeat(100) {
            if (debugger.pausedAt() != null) return@repeat
            yield()
        }
        assertEquals(stop.id, debugger.pausedAt()?.nodeId)
        assertFalse(stopped.isCompleted)
        debugger.continueExecution()
        stopped.await()
        assertNull(debugger.pausedAt())
        assertEquals(2, debugger.snapshot().size)
    }

    @Test fun removingBreakpointLetsContinueRunWithoutPause() = runBlocking {
        val debugger = EngineDebugSession()
        val node = ActionNode.Label(NodeId("removed-breakpoint"), "removed")
        debugger.setBreakpoint(node.id, true)
        debugger.setBreakpoint(node.id, false)
        debugger.continueExecution()
        debugger.beforeNode(3L, node, emptyMap())
        debugger.afterNode(3L, node, emptyMap(), true, 3L)
        assertNull(debugger.pausedAt())
        assertEquals(1, debugger.snapshot().size)
    }

    @Test fun historyKeepsOnlyMostRecentThousandSteps() = runBlocking {
        val debugger = EngineDebugSession()
        val node = ActionNode.Label(NodeId("history-node"), "history")
        debugger.continueExecution()
        repeat(1_005) { index ->
            val invocation = index.toLong()
            debugger.beforeNode(invocation, node, emptyMap())
            debugger.afterNode(invocation, node, emptyMap(), true, 1L)
        }
        val steps = debugger.snapshot()
        assertEquals(1_000, steps.size)
        assertEquals(5L, steps.first().invocationId)
        assertEquals(1_004L, steps.last().invocationId)
    }

    @Test fun cancelPauseReleasesWaitingInvocationsWithoutRecordingSteps() = runBlocking {
        val debugger = EngineDebugSession()
        val node = ActionNode.Label(NodeId("cancelled-node"), "cancelled")
        val pending = async {
            try {
                debugger.beforeNode(101L, node, emptyMap())
                false
            } catch (_: CancellationException) {
                true
            }
        }
        repeat(100) {
            if (debugger.pausedAt() != null) return@repeat
            yield()
        }
        assertEquals(node.id, debugger.pausedAt()?.nodeId)
        debugger.cancelPause()
        assertTrue(pending.await())
        assertNull(debugger.pausedAt())
        assertTrue(debugger.snapshot().isEmpty())
    }

}
