package com.yagay.yauto.core.engine

import com.yagay.yauto.core.model.ActionNode
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.NodeId
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
            debugger.beforeNode(node, mapOf("counter" to ConfigValue.StringValue("before")))
            debugger.afterNode(
                node, mapOf("counter" to ConfigValue.StringValue("after")),
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
        assertTrue(steps.single().success)
        assertEquals(17L, steps.single().elapsedMs)
        assertEquals(ConfigValue.StringValue("before"), steps.single().variablesBefore["counter"])
        assertEquals(ConfigValue.StringValue("after"), steps.single().variablesAfter["counter"])
    }
}
