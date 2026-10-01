package com.yagay.yauto.plugin.api

import com.yagay.yauto.core.diagnostics.*
import com.yagay.yauto.core.importer.*
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.registry.*
import org.junit.Assert.*
import org.junit.Test

class PluginCoordinatorTest {
    @Test
    fun `installs and removes all plugin contributions`() {
        val features = FeatureRegistry()
        val importers = ImporterRegistry()
        val diagnostics = DiagnosticRegistry()
        val coordinator = PluginCoordinator(features, importers, diagnostics)

        val plugin = object : YAutoPlugin {
            override val id = "demo"
            override val displayName = "Demo"
            override fun featurePacks() = listOf(object : FeaturePack {
                override val id = "demo.features"
                override fun install(registry: FeatureRegistry) {
                    registry.registerAction(
                        FeatureDescriptor(FeatureId("demo.action"), FeatureKind.ACTION, "Demo", "", FeatureCategory.CORE, ownerPackId = id)
                    ) { _, _ -> ActionExecutionResult(true) }
                }
            })
            override fun importers() = listOf(object : AutomationImporter {
                override val id = "demo.importer"
                override val displayName = "Demo importer"
                override fun confidence(input: ImportInput) = 1
                override fun import(input: ImportInput) = ImportResult(id, true)
            })
            override fun diagnosticCollectors() = listOf(object : DiagnosticCollector {
                override val id = "demo.diagnostics"
                override suspend fun status() = CollectorStatus(id, true)
                override suspend fun collect(context: DiagnosticContext) = emptyList<DiagnosticRecord>()
            })
        }

        val installed = coordinator.install(plugin)
        assertEquals(setOf("demo.features"), installed.featurePackIds)
        assertNotNull(features.actionExecutor("demo.action"))
        assertTrue(importers.all().any { it.id == "demo.importer" })
        assertTrue(diagnostics.all().any { it.id == "demo.diagnostics" })

        assertTrue(coordinator.uninstall("demo"))
        assertNull(features.actionExecutor("demo.action"))
        assertTrue(importers.all().none { it.id == "demo.importer" })
        assertTrue(diagnostics.all().none { it.id == "demo.diagnostics" })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects unsupported API version`() {
        PluginCoordinator(FeatureRegistry(), ImporterRegistry(), DiagnosticRegistry()).install(object : YAutoPlugin {
            override val id = "old"
            override val displayName = "Old"
            override val apiVersion = 999
        })
    }
}
