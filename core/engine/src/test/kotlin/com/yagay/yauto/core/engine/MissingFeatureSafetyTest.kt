package com.yagay.yauto.core.engine

import com.yagay.yauto.core.capability.CapabilityClient
import com.yagay.yauto.core.capability.CapabilityResult
import com.yagay.yauto.core.logging.InMemoryExecutionTracer
import com.yagay.yauto.core.logging.TraceKind
import com.yagay.yauto.core.logging.TraceLevel
import com.yagay.yauto.core.model.ActionNode
import com.yagay.yauto.core.model.Automation
import com.yagay.yauto.core.model.AutomationId
import com.yagay.yauto.core.model.AutomationPhase
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.NodeId
import com.yagay.yauto.core.model.PredicateNode
import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeatureRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MissingFeatureSafetyTest {
    @Test
    fun `missing condition evaluates false and the automation continues through else branch`() = runBlocking {
        val registry = FeatureRegistry()
        val tracer = InMemoryExecutionTracer()
        registry.registerAction(
            FeatureDescriptor(
                id = FeatureId("test.mark"),
                kind = FeatureKind.ACTION,
                title = "mark",
                description = "",
                category = FeatureCategory.CORE,
            )
        ) { _, context ->
            context.variables.set("branch", ConfigValue.StringValue("else"))
            ActionExecutionResult(success = true)
        }
        val engine = AutomationEngine(
            registry = registry,
            capabilities = CapabilityClient { CapabilityResult(false) },
            tracer = tracer,
        )
        val automation = Automation(
            id = AutomationId("missing-condition"),
            name = "Missing condition",
            onEvent = listOf(
                ActionNode.If(
                    id = NodeId("if"),
                    condition = PredicateNode.Condition(FeatureRef("legacy.condition.removed")),
                    thenActions = emptyList(),
                    elseActions = listOf(ActionNode.Action(NodeId("mark"), FeatureRef("test.mark"))),
                )
            ),
        )

        val result = engine.execute(automation, AutomationPhase.EVENT)

        assertTrue(result.success)
        assertEquals(ConfigValue.StringValue("else"), result.variables["branch"])
        val warning = tracer.snapshot().first { it.kind == TraceKind.CONDITION }
        assertEquals(TraceLevel.WARN, warning.level)
        assertEquals("legacy.condition.removed", warning.featureId)
    }
}
