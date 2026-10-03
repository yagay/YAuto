package com.yagay.yauto.platform.accessibility

import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.registry.AccessRequirement
import com.yagay.yauto.core.registry.FeatureRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityForegroundFallbackFeaturePackTest {
    @Test
    fun foregroundFeaturesExposeAccessibilityAndUsageStatsAsAlternatives() {
        val registry = FeatureRegistry()
        AccessibilityFeaturePack { AccessibilityWindowSnapshot("fallback.app", "FallbackActivity", 123L) }
            .install(registry)

        listOf(
            "android.event.app_foreground",
            "android.event.app_background",
            "android.state.app_foreground",
            "android.condition.app_foreground",
        ).forEach { id ->
            val descriptor = requireNotNull(registry.descriptor(id))
            val options = descriptor.resolvedImplementationOptions()
            assertEquals(listOf("accessibility", "usage_stats"), options.map { it.backendId })
            assertEquals(setOf(AccessRequirement.ACCESSIBILITY), options[0].requirements)
            assertEquals(setOf(AccessRequirement.USAGE_STATS), options[1].requirements)
            assertFalse(descriptor.capabilities.contains(CapabilityIds.ACCESSIBILITY))
        }
    }

    @Test
    fun windowChangedRemainsAccessibilityOnly() {
        val registry = FeatureRegistry()
        AccessibilityFeaturePack().install(registry)

        val descriptor = requireNotNull(registry.descriptor("android.event.window_changed"))
        assertTrue(descriptor.capabilities.contains(CapabilityIds.ACCESSIBILITY))
        assertEquals(listOf("accessibility"), descriptor.resolvedImplementationOptions().map { it.backendId })
    }
}
