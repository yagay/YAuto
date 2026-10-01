package com.yagay.yauto.core.importer

import com.yagay.yauto.core.model.Automation
import com.yagay.yauto.core.model.Flow
import kotlinx.serialization.Serializable

@Serializable
enum class ImportSeverity { INFO, WARNING, ERROR }

@Serializable
data class CompatibilityIssue(
    val severity: ImportSeverity,
    val sourcePath: String,
    val sourceType: String? = null,
    val message: String,
    val suggestedFeatureId: String? = null,
)

@Serializable
data class ImportTrace(
    val sourcePath: String,
    val targetId: String? = null,
    val status: String,
    val message: String? = null,
)

@Serializable
data class ImportBundle(
    val automations: List<Automation> = emptyList(),
    val flows: List<Flow> = emptyList(),
    val globalVariables: Map<String, String> = emptyMap(),
)

@Serializable
data class ImportResult(
    val importerId: String,
    val success: Boolean,
    val bundle: ImportBundle = ImportBundle(),
    val issues: List<CompatibilityIssue> = emptyList(),
    val trace: List<ImportTrace> = emptyList(),
)

data class ImportInput(
    val fileName: String? = null,
    val mimeType: String? = null,
    val bytes: ByteArray,
) {
    fun utf8OrNull(): String? = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull()
}


interface ImportReportSink {
    suspend fun save(result: ImportResult)
}

interface AutomationImporter {
    val id: String
    val displayName: String
    fun confidence(input: ImportInput): Int
    fun import(input: ImportInput): ImportResult
}

class ImporterRegistry {
    private val importers = linkedMapOf<String, AutomationImporter>()

    fun register(importer: AutomationImporter) { importers[importer.id] = importer }
    fun unregister(id: String) { importers.remove(id) }
    fun all(): List<AutomationImporter> = importers.values.toList()

    fun bestFor(input: ImportInput): AutomationImporter? = importers.values
        .map { it to runCatching { it.confidence(input) }.getOrDefault(0) }
        .filter { it.second > 0 }
        .maxByOrNull { it.second }
        ?.first

    fun import(input: ImportInput): ImportResult = bestFor(input)?.import(input)
        ?: ImportResult(
            importerId = "unknown",
            success = false,
            issues = listOf(CompatibilityIssue(ImportSeverity.ERROR, input.fileName ?: "input", message = "No compatible importer found")),
        )
}
