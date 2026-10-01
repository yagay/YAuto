package com.yagay.yauto.platform.android

import android.content.Context
import com.yagay.yauto.core.diagnostics.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ExecutionFileDiagnosticCollector(
    context: Context,
) : DiagnosticCollector {
    override val id = "yauto.execution.files"
    private val dir = File(context.filesDir, "logs")

    override suspend fun status(): CollectorStatus = CollectorStatus(id, dir.exists(), if (dir.exists()) "Persistent execution logs available" else "No persistent execution logs yet")

    override suspend fun collect(context: DiagnosticContext): List<DiagnosticRecord> = withContext(Dispatchers.IO) {
        dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".jsonl") }
            ?.sortedBy { it.name }
            ?.map { file ->
                DiagnosticRecord(
                    source = DiagnosticSource.YAUTO,
                    timestampEpochMs = file.lastModified(),
                    title = "Execution log: ${file.name}",
                    message = file.readText().takeLast(600_000),
                    context = context,
                )
            }.orEmpty()
    }
}

class ImportReportDiagnosticCollector(
    context: Context,
) : DiagnosticCollector {
    override val id = "yauto.import.reports"
    private val dir = File(context.filesDir, "import-reports")

    override suspend fun status(): CollectorStatus = CollectorStatus(id, dir.exists(), if (dir.exists()) "Import compatibility reports available" else "No import reports yet")

    override suspend fun collect(context: DiagnosticContext): List<DiagnosticRecord> = withContext(Dispatchers.IO) {
        dir.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.take(5)
            ?.map { file ->
                DiagnosticRecord(
                    source = DiagnosticSource.IMPORTER,
                    timestampEpochMs = file.lastModified(),
                    title = "Import report: ${file.name}",
                    message = file.readText().take(300_000),
                    context = context,
                )
            }.orEmpty()
    }
}
