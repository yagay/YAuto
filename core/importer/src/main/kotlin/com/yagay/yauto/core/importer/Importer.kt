package com.yagay.yauto.core.importer

import com.yagay.yauto.core.model.Automation
import com.yagay.yauto.core.model.Flow
import com.yagay.yauto.core.model.userText
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

data class ImporterDescriptor(
    val id: String,
    val displayName: String,
)

interface ImportReportSink {
    suspend fun save(result: ImportResult)
}

interface AutomationImporter {
    val id: String
    val displayName: String
    fun confidence(input: ImportInput): Int
    fun import(input: ImportInput): ImportResult
}

/**
 * Registry for optional compatibility importers.
 *
 * Importers can be registered as factories so third-party compatibility code is not constructed on
 * normal application startup. Construction and probing are isolated to the explicit import path.
 */
class ImporterRegistry {
    private class Registration(
        val descriptor: ImporterDescriptor,
        factory: () -> AutomationImporter,
    ) {
        val instance = lazy(LazyThreadSafetyMode.SYNCHRONIZED, factory)
    }

    private val registrations = linkedMapOf<String, Registration>()

    @Synchronized
    fun register(importer: AutomationImporter) {
        registrations[importer.id] = Registration(
            ImporterDescriptor(importer.id, importer.displayName),
        ) { importer }
    }

    @Synchronized
    fun registerLazy(
        id: String,
        displayName: String,
        factory: () -> AutomationImporter,
    ) {
        require(id.isNotBlank()) { "Importer ID cannot be blank" }
        require(displayName.isNotBlank()) { "Importer display name cannot be blank" }
        registrations[id] = Registration(ImporterDescriptor(id, displayName), factory)
    }

    @Synchronized
    fun unregister(id: String) {
        registrations.remove(id)
    }

    @Synchronized
    fun descriptors(): List<ImporterDescriptor> = registrations.values.map { it.descriptor }

    fun displayNames(): List<String> = descriptors().map { it.displayName }

    /**
     * Compatibility API used by existing UI. Returned handles expose metadata immediately but do
     * not construct the real importer until confidence/import is explicitly requested.
     */
    fun all(): List<AutomationImporter> = registrationsSnapshot().map(::handle)

    fun bestFor(input: ImportInput): AutomationImporter? = registrationsSnapshot()
        .mapNotNull { registration ->
            val importer = resolve(registration) ?: return@mapNotNull null
            importer to runCatching { importer.confidence(input) }.getOrDefault(0)
        }
        .filter { it.second > 0 }
        .maxByOrNull { it.second }
        ?.first

    fun import(input: ImportInput): ImportResult {
        val importer = bestFor(input) ?: return noCompatibleImporter(input)
        return importSafely(importer, input)
    }

    @Synchronized
    private fun registrationsSnapshot(): List<Registration> = registrations.values.toList()

    private fun handle(registration: Registration): AutomationImporter = object : AutomationImporter {
        override val id: String = registration.descriptor.id
        override val displayName: String = registration.descriptor.displayName

        override fun confidence(input: ImportInput): Int = resolve(registration)
            ?.let { importer -> runCatching { importer.confidence(input) }.getOrDefault(0) }
            ?: 0

        override fun import(input: ImportInput): ImportResult {
            val importer = resolve(registration) ?: return ImportResult(
                importerId = id,
                success = false,
                issues = listOf(
                    CompatibilityIssue(
                        severity = ImportSeverity.ERROR,
                        sourcePath = input.fileName ?: "input",
                        message = userText("import.no_compatible_importer"),
                    )
                ),
            )
            return importSafely(importer, input)
        }
    }

    private fun resolve(registration: Registration): AutomationImporter? = runCatching {
        registration.instance.value.also { importer ->
            require(importer.id == registration.descriptor.id) {
                "Importer factory ID mismatch: expected ${registration.descriptor.id}, got ${importer.id}"
            }
        }
    }.getOrNull()

    private fun importSafely(importer: AutomationImporter, input: ImportInput): ImportResult =
        runCatching { importer.import(input) }.getOrElse { error ->
            ImportResult(
                importerId = importer.id,
                success = false,
                issues = listOf(
                    CompatibilityIssue(
                        severity = ImportSeverity.ERROR,
                        sourcePath = input.fileName ?: "input",
                        message = error.message ?: error.javaClass.simpleName,
                    )
                ),
            )
        }

    private fun noCompatibleImporter(input: ImportInput): ImportResult = ImportResult(
        importerId = "unknown",
        success = false,
        issues = listOf(
            CompatibilityIssue(
                ImportSeverity.ERROR,
                input.fileName ?: "input",
                message = userText("import.no_compatible_importer"),
            )
        ),
    )
}
