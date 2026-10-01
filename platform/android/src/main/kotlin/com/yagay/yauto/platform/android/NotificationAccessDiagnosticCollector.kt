package com.yagay.yauto.platform.android

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.yagay.yauto.core.diagnostics.*

class NotificationAccessDiagnosticCollector(
    context: Context,
) : DiagnosticCollector {
    override val id: String = "android.notification_listener_access"
    private val context = context.applicationContext

    private fun enabled(): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    override suspend fun status(): CollectorStatus = CollectorStatus(
        collectorId = id,
        available = enabled(),
        message = if (enabled()) "Notification listener access granted" else "Notification listener access is not granted",
    )

    override suspend fun collect(context: DiagnosticContext): List<DiagnosticRecord> = listOf(
        DiagnosticRecord(
            source = DiagnosticSource.ANDROID,
            timestampEpochMs = System.currentTimeMillis(),
            severity = if (enabled()) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
            title = "Notification listener access",
            message = if (enabled()) "Enabled" else "Disabled",
            context = context,
        )
    )
}
