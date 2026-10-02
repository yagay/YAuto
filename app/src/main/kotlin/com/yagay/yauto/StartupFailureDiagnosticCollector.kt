package com.yagay.yauto

import android.content.Context
import com.yagay.yauto.core.diagnostics.CollectorStatus
import com.yagay.yauto.core.diagnostics.DiagnosticCollector
import com.yagay.yauto.core.diagnostics.DiagnosticContext
import com.yagay.yauto.core.diagnostics.DiagnosticRecord
import com.yagay.yauto.core.diagnostics.DiagnosticSeverity
import com.yagay.yauto.core.diagnostics.DiagnosticSource
import com.yagay.yauto.core.model.userText

class StartupFailureDiagnosticCollector(context: Context) : DiagnosticCollector {
    private val appContext = context.applicationContext
    override val id: String = "yauto.startup.failures"

    override suspend fun status(): CollectorStatus {
        val available = StartupFailureRecorder.hasEntries(appContext)
        return CollectorStatus(
            collectorId = id,
            available = available,
            message = if (available) {
                userText("diagnostics.startup_failures.available")
            } else {
                userText("diagnostics.startup_failures.empty")
            },
        )
    }

    override suspend fun collect(context: DiagnosticContext): List<DiagnosticRecord> {
        val text = StartupFailureRecorder.read(appContext)
        if (text.isBlank()) return emptyList()
        return listOf(
            DiagnosticRecord(
                source = DiagnosticSource.YAUTO,
                timestampEpochMs = System.currentTimeMillis(),
                severity = DiagnosticSeverity.ERROR,
                title = userText("diagnostics.startup_failures.title"),
                message = text,
                context = context,
                attributes = mapOf("collectorId" to id),
            )
        )
    }
}
