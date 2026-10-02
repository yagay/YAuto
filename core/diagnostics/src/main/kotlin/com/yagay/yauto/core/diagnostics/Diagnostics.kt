package com.yagay.yauto.core.diagnostics

import com.yagay.yauto.core.model.userText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable

@Serializable
enum class DiagnosticSource {
    YAUTO, ANDROID, ROOT, LSPOSED, SYSTEM_UI, SYSTEM_SERVER, ZYGOTE, IMPORTER
}

@Serializable
enum class DiagnosticSeverity { DEBUG, INFO, WARNING, ERROR }

@Serializable
data class DiagnosticContext(
    val executionId: String? = null,
    val automationId: String? = null,
    val flowId: String? = null,
    val featureId: String? = null,
    val backendId: String? = null,
)

@Serializable
data class DiagnosticRecord(
    val source: DiagnosticSource,
    val timestampEpochMs: Long,
    val severity: DiagnosticSeverity = DiagnosticSeverity.INFO,
    val title: String,
    val message: String,
    val context: DiagnosticContext = DiagnosticContext(),
    val attributes: Map<String, String> = emptyMap(),
)

@Serializable
data class CollectorStatus(
    val collectorId: String,
    val available: Boolean,
    val message: String? = null,
)

@Serializable
data class DiagnosticSnapshot(
    val createdAtEpochMs: Long,
    val records: List<DiagnosticRecord>,
    val statuses: List<CollectorStatus>,
)

interface DiagnosticCollector {
    val id: String
    suspend fun status(): CollectorStatus
    suspend fun collect(context: DiagnosticContext = DiagnosticContext()): List<DiagnosticRecord>
}

class DiagnosticRegistry {
    private val collectors = linkedMapOf<String, DiagnosticCollector>()

    fun register(collector: DiagnosticCollector) {
        collectors[collector.id] = collector
    }

    fun unregister(id: String) {
        collectors.remove(id)
    }

    fun all(): List<DiagnosticCollector> = collectors.values.toList()
}

class DiagnosticCoordinator(
    private val registry: DiagnosticRegistry,
) {
    suspend fun snapshot(context: DiagnosticContext = DiagnosticContext()): DiagnosticSnapshot = coroutineScope {
        val collectors = registry.all()
        val statusDeferred = collectors.map { collector ->
            async { runCatching { collector.status() }.getOrElse { error -> CollectorStatus(collector.id, false, error.message) } }
        }
        val recordDeferred = collectors.map { collector ->
            async {
                runCatching { collector.collect(context) }.getOrElse { error ->
                    listOf(
                        DiagnosticRecord(
                            source = DiagnosticSource.YAUTO,
                            timestampEpochMs = System.currentTimeMillis(),
                            severity = DiagnosticSeverity.ERROR,
                            title = userText("diagnostics.collector_failed", "Collector failed: %s", collector.id),
                            message = error.stackTraceToString().take(16_000),
                            context = context,
                        )
                    )
                }
            }
        }
        DiagnosticSnapshot(
            createdAtEpochMs = System.currentTimeMillis(),
            records = recordDeferred.flatMap { it.await() }.sortedBy { it.timestampEpochMs },
            statuses = statusDeferred.map { it.await() },
        )
    }
}

interface DiagnosticCommandRunner {
    suspend fun run(command: String, timeoutMs: Long = 10_000): CommandOutput
}

@Serializable
data class CommandOutput(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
)
