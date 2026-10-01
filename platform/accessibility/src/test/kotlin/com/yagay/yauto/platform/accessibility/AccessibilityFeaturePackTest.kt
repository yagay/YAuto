package com.yagay.yauto.platform.accessibility

import com.yagay.yauto.core.capability.CapabilityClient
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.capability.CapabilityResult
import com.yagay.yauto.core.logging.NoOpExecutionTracer
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.ExecutionId
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.NodeId
import com.yagay.yauto.core.registry.FeatureExecutionContext
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.VariableAccess
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityFeaturePackTest {
    @Test
    fun `pack registers removable UI automation actions and routes through capability`() = runBlocking {
        val registry = FeatureRegistry().apply { install(AccessibilityFeaturePack()) }
        assertEquals(6, registry.allDescriptors().count { it.ownerPackId == "accessibility.actions" })
        assertNotNull(registry.actionExecutor(AccessibilityOperations.CLICK_TEXT))

        var captured: CapabilityRequest? = null
        val context = FeatureExecutionContext(
            executionId = ExecutionId("test"),
            nodeId = NodeId("node"),
            variables = object : VariableAccess {
                override fun get(name: String): ConfigValue? = null
                override fun set(name: String, value: ConfigValue) = Unit
                override fun snapshot(): Map<String, ConfigValue> = emptyMap()
            },
            capabilities = CapabilityClient { request ->
                captured = request
                CapabilityResult(true)
            },
            tracer = NoOpExecutionTracer,
        )

        val result = registry.actionExecutor(AccessibilityOperations.CLICK_TEXT)!!.execute(
            FeatureRef(
                AccessibilityOperations.CLICK_TEXT,
                config = mapOf(
                    "text" to ConfigValue.StringValue("OK"),
                    "exact" to ConfigValue.BooleanValue(true),
                ),
            ),
            context,
        )
        assertTrue(result.success)
        assertEquals(AccessibilityOperations.CLICK_TEXT, captured?.operationId)
        assertEquals(ConfigValue.StringValue("OK"), captured?.payload?.get("text"))

        registry.uninstallPack("accessibility.actions")
        assertTrue(registry.allDescriptors().none { it.ownerPackId == "accessibility.actions" })
    }
}
