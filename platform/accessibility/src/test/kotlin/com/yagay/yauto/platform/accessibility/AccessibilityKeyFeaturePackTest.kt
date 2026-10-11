package com.yagay.yauto.platform.accessibility

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.registry.AccessRequirement
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.resolvedImplementationOptions
import org.junit.Assert.*
import org.junit.Test

class AccessibilityKeyFeaturePackTest {
    @Test fun allKeyTriggersExposeLsposedRootAndAccessibilityAsAlternatives() {
        val registry = FeatureRegistry()
        AccessibilityKeyFeaturePack().install(registry)
        listOf("android.event.hardware_key", "android.event.hardware_key_gesture",
            "android.event.hardware_key_combo").forEach { featureId ->
            val descriptor = requireNotNull(registry.descriptor(featureId))
            val implementations = descriptor.resolvedImplementationOptions()
            assertEquals(listOf("lsposed", "root", "accessibility"),
                implementations.map { it.backendId })
            assertEquals(listOf(
                setOf(AccessRequirement.LSPOSED),
                setOf(AccessRequirement.ROOT),
                setOf(AccessRequirement.ACCESSIBILITY),
            ), implementations.map { it.requirements })
            assertTrue(descriptor.accessRequirements.isEmpty())
        }
    }

    @Test fun `hardware key matcher filters key action and repeat`() {
        val payload = mapOf(
            "keyCode" to ConfigValue.NumberValue(24.0),
            "scanCode" to ConfigValue.NumberValue(115.0),
            "action" to ConfigValue.StringValue("down"),
            "repeatCount" to ConfigValue.NumberValue(0.0),
        )
        assertTrue(matchesHardwareKey(mapOf(
            "keyCode" to ConfigValue.NumberValue(24.0),
            "action" to ConfigValue.StringValue("down"),
            "initialOnly" to ConfigValue.BooleanValue(true),
        ), payload))
        assertFalse(matchesHardwareKey(mapOf(
            "keyCode" to ConfigValue.NumberValue(25.0),
        ), payload))
        assertTrue(matchesHardwareKey(mapOf(
            "scanCode" to ConfigValue.NumberValue(115.0),
            "action" to ConfigValue.StringValue("down"),
        ), payload))
        assertFalse(matchesHardwareKey(mapOf(
            "scanCode" to ConfigValue.NumberValue(114.0),
        ), payload))
        assertFalse(matchesHardwareKey(mapOf(
            "initialOnly" to ConfigValue.BooleanValue(true),
        ), payload + ("repeatCount" to ConfigValue.NumberValue(2.0))))
    }
}
