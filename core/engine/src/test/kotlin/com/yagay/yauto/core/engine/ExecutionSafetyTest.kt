package com.yagay.yauto.core.engine

import com.yagay.yauto.core.capability.*
import com.yagay.yauto.core.logging.*
import com.yagay.yauto.core.model.*
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ExecutionSafetyTest {
    private val registry = FeatureRegistry()
    private val tracer = InMemoryExecutionTracer()
    private fun engine(resolver: FlowResolver = EmptyFlowResolver) =
        AutomationEngine(registry, CapabilityClient { CapabilityResult(false) }, tracer, resolver)
    private fun rule(nodes: List<ActionNode>, timeout: Long = 1000) = Automation(AutomationId("safety"), "Safety",
        onEvent = nodes, executionPolicy = ExecutionPolicy(maxRuntimeMs = timeout))

    @Test fun `runtime deadline stops a suspended action`() = runBlocking {
        registry.registerAction(FeatureDescriptor(FeatureId("wait"), FeatureKind.ACTION, "wait", "", FeatureCategory.CORE)) { _, _ ->
            delay(60_000); ActionExecutionResult(true)
        }
        val result = engine().execute(rule(listOf(ActionNode.Action(NodeId("wait"), FeatureRef("wait"))), 50), AutomationPhase.EVENT)
        assertFalse(result.success)
        // Headless core tests deliberately resolve user-facing text to stable keys. Android installs
        // the resource-backed resolver at Application.onCreate().
        assertEquals("engine.execution_timeout", result.error)
        assertEquals(TraceKind.EXECUTION_END, tracer.snapshot().last().kind)
    }

    @Test fun `parent cancellation remains cancellation`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        registry.registerAction(FeatureDescriptor(FeatureId("wait"), FeatureKind.ACTION, "wait", "", FeatureCategory.CORE)) { _, _ ->
            entered.complete(Unit); awaitCancellation()
        }
        val job = async { engine().execute(rule(listOf(ActionNode.Action(NodeId("wait"), FeatureRef("wait")))), AutomationPhase.EVENT) }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals("engine.execution_cancelled", tracer.snapshot().last().message)
    }

    @Test fun `try catches thrown feature exceptions and executes finally`() = runBlocking {
        registry.registerAction(FeatureDescriptor(FeatureId("throw"), FeatureKind.ACTION, "throw", "", FeatureCategory.CORE)) { _, _ -> error("feature failure") }
        registry.registerAction(FeatureDescriptor(FeatureId("finally"), FeatureKind.ACTION, "finally", "", FeatureCategory.CORE)) { _, ctx ->
            ctx.variables.set("finished", ConfigValue.BooleanValue(true)); ActionExecutionResult(true)
        }
        val result = engine().execute(rule(listOf(ActionNode.Try(NodeId("try"),
            listOf(ActionNode.Action(NodeId("throw"), FeatureRef("throw"))),
            onError = listOf(ActionNode.Return(NodeId("handled"), ConfigValue.StringValue("handled"))),
            finallyActions = listOf(ActionNode.Action(NodeId("finally"), FeatureRef("finally")))))), AutomationPhase.EVENT)
        assertTrue(result.success)
        assertEquals(ConfigValue.StringValue("handled"), result.returnValue)
        assertEquals(ConfigValue.BooleanValue(true), result.variables["finished"])
    }

    @Test fun `recursive flows fail with a bounded depth`() = runBlocking {
        val call = ActionNode.CallFlow(NodeId("call"), FlowId("recursive"))
        val result = engine(FlowResolver { Flow(FlowId("recursive"), "Recursive", actions = listOf(call)) })
            .execute(rule(listOf(call)), AutomationPhase.EVENT)
        assertFalse(result.success)
        assertTrue(result.error!!.contains("depth"))
    }

    @Test fun `loop limits fail rather than silently truncate work`() = runBlocking {
        val nodes = listOf(
            ActionNode.Repeat(NodeId("repeat"), 3, emptyList()),
            ActionNode.While(NodeId("while"), PredicateNode.Literal(true), emptyList()),
            ActionNode.ForEach(NodeId("each"), List(3) { ConfigValue.NullValue }, "item", emptyList()),
        )
        for (node in nodes) {
            val automation = rule(listOf(node)).copy(executionPolicy = ExecutionPolicy(maxLoopIterations = 2))
            val result = engine().execute(automation, AutomationPhase.EVENT)
            assertFalse(node::class.simpleName, result.success)
            assertTrue(result.error!!.contains("limit"))
        }
    }

    @Test fun `finally executes when the error handler also throws`() = runBlocking {
        registry.registerAction(FeatureDescriptor(FeatureId("throw"), FeatureKind.ACTION, "throw", "", FeatureCategory.CORE)) { _, _ -> error("handler failure") }
        registry.registerAction(FeatureDescriptor(FeatureId("cleanup"), FeatureKind.ACTION, "cleanup", "", FeatureCategory.CORE)) { _, ctx ->
            ctx.variables.set("cleaned", ConfigValue.BooleanValue(true)); ActionExecutionResult(true)
        }
        val action = ActionNode.Action(NodeId("throw"), FeatureRef("throw"))
        val result = engine().execute(rule(listOf(ActionNode.Try(NodeId("try"), listOf(action),
            onError = listOf(action), finallyActions = listOf(ActionNode.Action(NodeId("cleanup"), FeatureRef("cleanup")))))), AutomationPhase.EVENT)
        assertFalse(result.success)
        assertEquals(ConfigValue.BooleanValue(true), result.variables["cleaned"])
    }
}
