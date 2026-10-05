package com.yagay.yauto.core.registry

import com.yagay.yauto.core.model.FeatureRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureRegistryResolutionTest {
    @Test
    fun `canonical feature resolves without migration`() {
        val registry = FeatureRegistry()
        registry.registerAction(descriptor("android.test.action")) { _, _ ->
            ActionExecutionResult(success = true)
        }

        val resolution = registry.resolve("android.test.action")

        assertTrue(resolution is FeatureResolution.Available)
        assertEquals("android.test.action", registry.canonicalId("android.test.action"))
        assertNotNull(registry.actionExecutor("android.test.action"))
    }

    @Test
    fun `legacy alias resolves to canonical feature without losing config`() {
        val registry = FeatureRegistry()
        registry.registerAction(
            descriptor(
                id = "android.test.action",
                aliases = setOf("android.legacy.action"),
            )
        ) { _, _ -> ActionExecutionResult(success = true) }

        val resolution = registry.resolve("android.legacy.action")
        val original = FeatureRef(typeId = "android.legacy.action", schemaVersion = 7)
        val canonical = registry.canonicalRef(original)

        assertTrue(resolution is FeatureResolution.Aliased)
        assertEquals("android.test.action", registry.canonicalId("android.legacy.action"))
        assertEquals("android.test.action", canonical.typeId)
        assertEquals(7, canonical.schemaVersion)
        assertNotNull(registry.actionExecutor("android.legacy.action"))
        assertEquals("android.test.action", registry.descriptor("android.legacy.action")?.id?.value)
    }

    @Test
    fun `known feature of wrong kind resolves as incompatible and is not canonicalized`() {
        val registry = FeatureRegistry()
        registry.registerDescriptor(
            descriptor(
                id = "android.test.condition",
                aliases = setOf("android.legacy.condition"),
                kind = FeatureKind.CONDITION,
            )
        )
        val original = FeatureRef("android.legacy.condition")

        val resolution = registry.resolve("android.legacy.condition", FeatureKind.ACTION)

        assertTrue(resolution is FeatureResolution.Incompatible)
        resolution as FeatureResolution.Incompatible
        assertEquals(FeatureKind.ACTION, resolution.expectedKind)
        assertEquals(FeatureKind.CONDITION, resolution.actualKind)
        assertNull(registry.canonicalId("android.legacy.condition", FeatureKind.ACTION))
        assertEquals(
            "android.legacy.condition",
            registry.canonicalRef(original, FeatureKind.ACTION).typeId,
        )
        assertNull(registry.actionExecutor("android.legacy.condition"))
    }

    @Test
    fun `unknown feature is a safe missing resolution`() {
        val registry = FeatureRegistry()

        val resolution = registry.resolve("android.missing.feature")

        assertTrue(resolution is FeatureResolution.Missing)
        assertNull(registry.canonicalId("android.missing.feature"))
        assertNull(registry.descriptor("android.missing.feature"))
        assertNull(registry.actionExecutor("android.missing.feature"))
        assertNull(registry.conditionEvaluator("android.missing.feature"))
        assertNull(registry.eventMatcher("android.missing.feature"))
        assertNull(registry.stateEvaluator("android.missing.feature"))
        assertEquals("android.missing.feature", registry.canonicalRef(FeatureRef("android.missing.feature")).typeId)
    }

    @Test
    fun `alias collisions are rejected without partial registration`() {
        val registry = FeatureRegistry()
        registry.registerDescriptor(
            descriptor(
                id = "android.first.action",
                aliases = setOf("android.old.action"),
            )
        )

        assertFails {
            registry.registerDescriptor(
                descriptor(
                    id = "android.second.action",
                    aliases = linkedSetOf("android.new.alias", "android.old.action"),
                )
            )
        }
        assertTrue(registry.resolve("android.second.action") is FeatureResolution.Missing)
        assertTrue(registry.resolve("android.new.alias") is FeatureResolution.Missing)
        assertEquals("android.first.action", registry.canonicalId("android.old.action"))

        assertFails {
            registry.registerDescriptor(descriptor(id = "android.old.action"))
        }
        assertEquals("android.first.action", registry.canonicalId("android.old.action"))
        assertEquals(1, registry.allDescriptors().size)
    }

    @Test
    fun `uninstalling pack removes its aliases as well as implementation`() {
        val registry = FeatureRegistry()
        val pack = object : FeaturePack {
            override val id = "test.pack"
            override fun install(registry: FeatureRegistry) {
                registry.registerAction(
                    descriptor(
                        id = "android.pack.action",
                        aliases = setOf("android.pack.legacy"),
                        ownerPackId = id,
                    )
                ) { _, _ -> ActionExecutionResult(success = true) }
            }
        }
        registry.install(pack)
        assertNotNull(registry.actionExecutor("android.pack.legacy"))

        registry.uninstallPack(pack.id)

        assertTrue(registry.resolve("android.pack.action") is FeatureResolution.Missing)
        assertTrue(registry.resolve("android.pack.legacy") is FeatureResolution.Missing)
        assertNull(registry.actionExecutor("android.pack.legacy"))
        assertFalse(registry.allDescriptors().any { it.ownerPackId == pack.id })
    }

    @Test
    fun `descriptor snapshot is reused until registry changes`() {
        val registry = FeatureRegistry()
        registry.registerDescriptor(descriptor("android.first"))

        val first = registry.allDescriptors()
        val second = registry.allDescriptors()
        assertSame(first, second)

        registry.registerDescriptor(descriptor("android.second"))
        val third = registry.allDescriptors()

        assertEquals(2, third.size)
        assertFalse(first === third)
        assertSame(third, registry.allDescriptors())
    }

    private fun descriptor(
        id: String,
        aliases: Set<String> = emptySet(),
        ownerPackId: String = "test",
        kind: FeatureKind = FeatureKind.ACTION,
    ) = FeatureDescriptor(
        id = FeatureId(id),
        kind = kind,
        title = id,
        description = "test",
        category = FeatureCategory.CORE,
        ownerPackId = ownerPackId,
        aliases = aliases,
    )

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue("Expected IllegalArgumentException", failed)
    }
}
