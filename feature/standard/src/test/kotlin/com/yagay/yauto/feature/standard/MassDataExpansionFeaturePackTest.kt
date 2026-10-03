package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeatureRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MassDataExpansionFeaturePackTest {
    @Test
    fun `expansion packs install seventy five executable actions`() {
        val registry = FeatureRegistry()
        val packs = listOf(
            CollectionExpansionFeaturePack(),
            DataCodecFeaturePack(),
            VariableAdvancedFeaturePack(),
        )

        packs.forEach { it.install(registry) }

        val descriptors = registry.allDescriptors()
        assertEquals(75, descriptors.size)
        assertTrue(descriptors.all { it.kind == FeatureKind.ACTION })
        descriptors.forEach { descriptor ->
            assertNotNull("Missing executor for ${descriptor.id.value}", registry.actionExecutor(descriptor.id.value))
        }
    }

    @Test
    fun `representative advanced features are registered`() {
        val registry = FeatureRegistry()
        CollectionExpansionFeaturePack().install(registry)
        DataCodecFeaturePack().install(registry)
        VariableAdvancedFeaturePack().install(registry)

        listOf(
            "data.list.slice",
            "data.list.flatten",
            "data.list.intersection",
            "data.object.merge",
            "data.text.base64_encode",
            "data.text.sha256",
            "data.value.to_number",
            "data.list.frequency",
            "variable.swap",
            "variable.divide",
            "variable.snapshot",
        ).forEach { id ->
            assertNotNull("Expected feature $id", registry.descriptor(id))
            assertNotNull("Expected executor $id", registry.actionExecutor(id))
        }
    }
}
