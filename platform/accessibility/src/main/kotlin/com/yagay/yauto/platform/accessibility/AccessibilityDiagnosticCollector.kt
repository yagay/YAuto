package com.yagay.yauto.platform.accessibility

import com.yagay.yauto.core.diagnostics.CollectorStatus
import com.yagay.yauto.core.diagnostics.DiagnosticCollector
import com.yagay.yauto.core.diagnostics.DiagnosticContext
import com.yagay.yauto.core.diagnostics.DiagnosticRecord
import com.yagay.yauto.core.diagnostics.DiagnosticSeverity
import com.yagay.yauto.core.diagnostics.DiagnosticSource

class AccessibilityDiagnosticCollector : DiagnosticCollector {
    override val id: String = "accessibility"

    override suspend fun status(): CollectorStatus {
        val available = YAutoAccessibilityService.current != null
        return CollectorStatus(
            collectorId = id,
            available = available,
            message = if (available) "YAuto Accessibility Service connected" else "Accessibility Service not connected",
        )
    }

    override suspend fun collect(context: DiagnosticContext): List<DiagnosticRecord> {
        val connected = YAutoAccessibilityService.current != null
        return listOf(
            DiagnosticRecord(
                source = DiagnosticSource.ANDROID,
                timestampEpochMs = System.currentTimeMillis(),
                severity = if (connected) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                title = "Accessibility backend",
                message = if (connected) "YAuto Accessibility Service is connected and ready" else "YAuto Accessibility Service is not connected",
                context = context,
                attributes = mapOf(
                    "backend" to "accessibility",
                    "connected" to connected.toString(),
                ),
            )
        )
    }
}
