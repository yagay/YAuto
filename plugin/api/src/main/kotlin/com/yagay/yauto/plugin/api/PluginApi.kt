package com.yagay.yauto.plugin.api

import com.yagay.yauto.core.diagnostics.DiagnosticCollector
import com.yagay.yauto.core.diagnostics.DiagnosticRegistry
import com.yagay.yauto.core.importer.AutomationImporter
import com.yagay.yauto.core.importer.ImporterRegistry
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry

object PluginApiVersion {
    const val CURRENT: Int = 1
}

/** Stable, intentionally small extension contract. Dynamic discovery/loading is a separate concern. */
interface YAutoPlugin {
    val id: String
    val displayName: String
    val apiVersion: Int get() = PluginApiVersion.CURRENT

    fun featurePacks(): List<FeaturePack> = emptyList()
    fun importers(): List<AutomationImporter> = emptyList()
    fun diagnosticCollectors(): List<DiagnosticCollector> = emptyList()
}

data class InstalledPlugin(
    val id: String,
    val displayName: String,
    val featurePackIds: Set<String>,
    val importerIds: Set<String>,
    val diagnosticCollectorIds: Set<String>,
)

class PluginCoordinator(
    private val features: FeatureRegistry,
    private val importers: ImporterRegistry,
    private val diagnostics: DiagnosticRegistry,
) {
    private val installed = linkedMapOf<String, InstalledPlugin>()

    fun install(plugin: YAutoPlugin): InstalledPlugin {
        require(plugin.id.isNotBlank()) { "Plugin ID is blank" }
        require(plugin.apiVersion == PluginApiVersion.CURRENT) {
            "Unsupported plugin API ${plugin.apiVersion}; expected ${PluginApiVersion.CURRENT}"
        }
        require(plugin.id !in installed) { "Plugin already installed: ${plugin.id}" }

        val packs = plugin.featurePacks()
        val pluginImporters = plugin.importers()
        val collectors = plugin.diagnosticCollectors()
        require(packs.map { it.id }.distinct().size == packs.size) { "Duplicate FeaturePack ID in plugin ${plugin.id}" }
        require(pluginImporters.map { it.id }.distinct().size == pluginImporters.size) { "Duplicate importer ID in plugin ${plugin.id}" }
        require(collectors.map { it.id }.distinct().size == collectors.size) { "Duplicate diagnostic collector ID in plugin ${plugin.id}" }

        // Install is rollback-safe: if any later registration fails, everything already added is removed.
        val addedPacks = mutableListOf<String>()
        val addedImporters = mutableListOf<String>()
        val addedCollectors = mutableListOf<String>()
        try {
            packs.forEach { pack -> features.install(pack); addedPacks += pack.id }
            pluginImporters.forEach { importer -> importers.register(importer); addedImporters += importer.id }
            collectors.forEach { collector -> diagnostics.register(collector); addedCollectors += collector.id }
        } catch (error: Throwable) {
            addedPacks.asReversed().forEach(features::uninstallPack)
            addedImporters.asReversed().forEach(importers::unregister)
            addedCollectors.asReversed().forEach(diagnostics::unregister)
            throw error
        }

        return InstalledPlugin(
            id = plugin.id,
            displayName = plugin.displayName,
            featurePackIds = addedPacks.toSet(),
            importerIds = addedImporters.toSet(),
            diagnosticCollectorIds = addedCollectors.toSet(),
        ).also { installed[plugin.id] = it }
    }

    fun uninstall(pluginId: String): Boolean {
        val plugin = installed.remove(pluginId) ?: return false
        plugin.featurePackIds.forEach(features::uninstallPack)
        plugin.importerIds.forEach(importers::unregister)
        plugin.diagnosticCollectorIds.forEach(diagnostics::unregister)
        return true
    }

    fun installed(): List<InstalledPlugin> = installed.values.toList()
}
