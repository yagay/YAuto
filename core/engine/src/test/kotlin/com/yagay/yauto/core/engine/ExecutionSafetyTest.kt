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
        assertTrue(result.error!!.contains("timed out"))
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
        assertEquals("Execution cancelled", tracer.snapshot().last().message)
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
}
