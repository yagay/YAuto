package com.yagay.yauto

import com.yagay.yauto.core.importer.ImporterRegistry
import com.yagay.yauto.importer.macrodroid.MacroDroidImporter
import com.yagay.yauto.importer.shortx.EnhancedShortXImporter
import com.yagay.yauto.importer.tasker.EnhancedTaskerImporter

/**
 * Optional compatibility importer boundary.
 *
 * Only lightweight descriptors/factories are registered here. Parser implementations are
 * constructed by [ImporterRegistry] only after the user explicitly starts an import.
 */
internal object CompatibilityImporterCatalog {
    fun registerInto(registry: ImporterRegistry) {
        registry.registerLazy("macrodroid", "MacroDroid") { MacroDroidImporter() }
        registry.registerLazy("shortx", "ShortX") { EnhancedShortXImporter() }
        registry.registerLazy("tasker", "Tasker") { EnhancedTaskerImporter() }
    }
}
