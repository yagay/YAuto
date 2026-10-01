package com.yagay.yauto.core.engine

import com.yagay.yauto.core.capability.CapabilityClient
import com.yagay.yauto.core.capability.CapabilityResult
import com.yagay.yauto.core.logging.InMemoryExecutionTracer
import com.yagay.yauto.core.model.*
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AutomationEngineTest {
    @Test fun structuredIfExecutesExpectedBranch() = runBlocking {
        val registry = FeatureRegistry()
        registry.registerAction(FeatureDescriptor(FeatureId("test.set"), FeatureKind.ACTION, "set", "", FeatureCategory.CORE)) { _, ctx ->
            ctx.variables.set("result", ConfigValue.StringValue("yes")); ActionExecutionResult(true)
        }
        val engine = AutomationEngine(registry, CapabilityClient { CapabilityResult(false) }, InMemoryExecutionTracer())
        val automation = Automation(
            AutomationId("a"), "test",
            onEvent = listOf(ActionNode.If(NodeId("if"), PredicateNode.Literal(true), listOf(ActionNode.Action(NodeId("x"), FeatureRef("test.set")))))
        )
        val result = engine.execute(automation, AutomationPhase.EVENT)
        assertTrue(result.success)
        assertEquals("yes", (result.variables["result"] as ConfigValue.StringValue).value)
    }
}
