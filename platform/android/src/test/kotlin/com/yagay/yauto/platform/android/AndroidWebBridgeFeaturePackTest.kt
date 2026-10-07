package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.FeatureResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidWebBridgeFeaturePackTest {
    @Test
    fun `legacy websocket event ids migrate to one canonical trigger with implied event`() {
        val registry = FeatureRegistry()
        registry.install(AndroidWebBridgeFeaturePack())

        val expected = mapOf(
            "android.event.websocket_open" to "open",
            "android.event.websocket.message" to "message",
            "android.event.websocket.closed" to "closed",
            "android.event.websocket.failure" to "failure",
        )

        expected.forEach { (legacyId, event) ->
            val resolution = registry.resolve(legacyId, FeatureKind.EVENT)
            assertTrue(resolution is FeatureResolution.Aliased)

            val migrated = registry.canonicalRef(FeatureRef(legacyId), FeatureKind.EVENT)
            assertEquals("android.event.websocket", migrated.typeId)
            assertEquals(ConfigValue.StringValue(event), migrated.config["event"])
        }

        val descriptorIds = registry.allDescriptors().map { it.id.value }.toSet()
        assertTrue("android.event.websocket" in descriptorIds)
        assertFalse(expected.keys.any(descriptorIds::contains))
    }
}
