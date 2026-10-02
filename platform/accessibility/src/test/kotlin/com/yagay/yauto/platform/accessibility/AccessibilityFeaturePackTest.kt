package com.yagay.yauto.platform.accessibility

import com.yagay.yauto.core.capability.CapabilityClient
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.capability.CapabilityResult
import com.yagay.yauto.core.logging.NoOpExecutionTracer
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.ExecutionId
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.NodeId
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.registry.EventMatchContext
import com.yagay.yauto.core.registry.FeatureExecutionContext
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.VariableAccess
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityFeaturePackTest {
    private val variables = object : VariableAccess {
        override fun get(name: String): ConfigValue? = null
        override fun set(name: String, value: ConfigValue) = Unit
        override fun snapshot(): Map<String, ConfigValue> = emptyMap()
    }

    private fun context(client: CapabilityClient) = FeatureExecutionContext(
        executionId = ExecutionId("test"),
        nodeId = NodeId("node"),
        variables = variables,
        capabilities = client,
        tracer = NoOpExecutionTracer,
    )

    private fun eventContext(client: CapabilityClient, event: RuntimeEvent) = EventMatchContext(
        executionId = ExecutionId("event-test"),
        event = event,
        variables = variables,
        capabilities = client,
        tracer = NoOpExecutionTracer,
    )

    @Test
    fun `pack registers removable UI automation actions conditions and foreground features`() = runBlocking {
        val registry = FeatureRegistry().apply { install(AccessibilityFeaturePack()) }
        assertEquals(17, registry.allDescriptors().count { it.ownerPackId == "accessibility.actions" })
        assertNotNull(registry.actionExecutor(AccessibilityOperations.CLICK_TEXT))
        assertNotNull(registry.actionExecutor(AccessibilityOperations.LONG_CLICK_TEXT))
        assertNotNull(registry.actionExecutor(AccessibilityOperations.INPUT_TEXT_VIEW_ID))
        assertNotNull(registry.actionExecutor(AccessibilityOperations.SCROLL))
        assertNotNull(registry.conditionEvaluator("accessibility.condition.text_present"))
        assertNotNull(registry.conditionEvaluator("accessibility.condition.view_id_present"))
        assertNotNull(registry.conditionEvaluator("android.condition.app_foreground"))
        assertNotNull(registry.stateEvaluator("android.state.app_foreground"))
        assertNotNull(registry.eventMatcher("android.event.app_foreground"))
        assertNotNull(registry.eventMatcher("android.event.app_background"))
        assertNotNull(registry.eventMatcher("android.event.window_changed"))

        var captured: CapabilityRequest? = null
        val result = registry.actionExecutor(AccessibilityOperations.CLICK_TEXT)!!.execute(
            FeatureRef(
                AccessibilityOperations.CLICK_TEXT,
                config = mapOf(
                    "text" to ConfigValue.StringValue("OK"),
                    "exact" to ConfigValue.BooleanValue(true),
                ),
            ),
            context(CapabilityClient { request ->
                captured = request
                CapabilityResult(true)
            }),
        )
        assertTrue(result.success)
        assertEquals(AccessibilityOperations.CLICK_TEXT, captured?.operationId)
        assertEquals(ConfigValue.StringValue("OK"), captured?.payload?.get("text"))

        registry.uninstallPack("accessibility.actions")
        assertTrue(registry.allDescriptors().none { it.ownerPackId == "accessibility.actions" })
    }

    @Test
    fun `screen presence conditions distinguish successful false result from backend failure`() = runBlocking {
        val registry = FeatureRegistry().apply { install(AccessibilityFeaturePack()) }
        val feature = FeatureRef(
            "accessibility.condition.text_present",
            config = mapOf("text" to ConfigValue.StringValue("Missing")),
        )

        val notFound = registry.conditionEvaluator(feature.typeId)!!.evaluate(
            feature,
            context(CapabilityClient { request ->
                assertEquals(AccessibilityOperations.FIND_TEXT, request.operationId)
                CapabilityResult(success = true, value = ConfigValue.BooleanValue(false))
            }),
        )
        assertFalse(notFound)

        val found = registry.conditionEvaluator(feature.typeId)!!.evaluate(
            feature,
            context(CapabilityClient { CapabilityResult(success = true, value = ConfigValue.BooleanValue(true)) }),
        )
        assertTrue(found)

        val unavailable = registry.conditionEvaluator(feature.typeId)!!.evaluate(
            feature,
            context(CapabilityClient { CapabilityResult(false, message = "service unavailable") }),
        )
        assertFalse(unavailable)
    }

    @Test
    fun `foreground event filters package and class`() = runBlocking {
        val registry = FeatureRegistry().apply { install(AccessibilityFeaturePack()) }
        val feature = FeatureRef(
            "android.event.app_foreground",
            config = mapOf(
                "package" to ConfigValue.StringValue("com.example.app"),
                "classContains" to ConfigValue.StringValue("Main"),
            ),
        )
        val event = RuntimeEvent(
            "android.event.app_foreground",
            mapOf(
                "package" to ConfigValue.StringValue("com.example.app"),
                "class" to ConfigValue.StringValue("com.example.app.MainActivity"),
            ),
        )
        assertTrue(
            registry.eventMatcher(feature.typeId)!!.matches(
                feature,
                eventContext(CapabilityClient { CapabilityResult(false) }, event),
            )
        )
    }
}
