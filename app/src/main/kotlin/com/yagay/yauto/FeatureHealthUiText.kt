package com.yagay.yauto

import android.content.Context
import com.yagay.yauto.core.registry.AccessRequirement
import com.yagay.yauto.ui.design.R as TextR

internal fun featureHealthStatusLabel(context: Context, status: FeatureHealthStatus): String =
    context.getString(
        when (status) {
            FeatureHealthStatus.READY -> TextR.string.feature_health_ready
            FeatureHealthStatus.BLOCKED -> TextR.string.feature_health_blocked
            FeatureHealthStatus.BROKEN -> TextR.string.feature_health_broken
            FeatureHealthStatus.UNSUPPORTED -> TextR.string.feature_health_unsupported
        }
    )

internal fun featureHealthReason(context: Context, item: FeatureHealthItem): String {
    val missing = item.missingRequirements.joinToString(", ") { healthRequirementLabel(context, it) }
    return when (item.status) {
        FeatureHealthStatus.READY -> context.getString(TextR.string.feature_health_ready_detail)
        FeatureHealthStatus.BLOCKED -> context.getString(TextR.string.feature_health_blocked_detail_format, missing)
        FeatureHealthStatus.BROKEN -> context.getString(TextR.string.feature_health_broken_detail)
        FeatureHealthStatus.UNSUPPORTED -> context.getString(TextR.string.feature_health_unsupported_detail)
    }
}

internal fun featureHealthResolution(context: Context, item: FeatureHealthItem): String? =
    when (item.status) {
        FeatureHealthStatus.READY -> null
        FeatureHealthStatus.BROKEN -> context.getString(TextR.string.feature_health_solution_broken)
        FeatureHealthStatus.UNSUPPORTED -> context.getString(TextR.string.feature_health_solution_unsupported)
        FeatureHealthStatus.BLOCKED -> item.missingRequirements
            .map { healthRequirementResolution(context, it) }
            .distinct()
            .joinToString("；")
            .ifBlank { context.getString(TextR.string.feature_health_solution_check_diagnostics) }
    }

internal fun featureHealthListSummary(context: Context, item: FeatureHealthItem): String? {
    if (item.status == FeatureHealthStatus.READY) return null
    val reason = featureHealthReason(context, item)
    val solution = featureHealthResolution(context, item)
    return if (solution.isNullOrBlank()) reason
    else context.getString(TextR.string.feature_health_reason_solution_format, reason, solution)
}

internal fun healthRequirementLabel(context: Context, value: AccessRequirement): String =
    context.getString(
        when (value) {
            AccessRequirement.ROOT -> TextR.string.access_root
            AccessRequirement.SHIZUKU -> TextR.string.access_shizuku
            AccessRequirement.LSPOSED -> TextR.string.access_lsposed
            AccessRequirement.ZYGISK -> TextR.string.access_zygisk
            AccessRequirement.ACCESSIBILITY -> TextR.string.access_accessibility
            AccessRequirement.USAGE_STATS -> TextR.string.access_usage_stats
            AccessRequirement.NOTIFICATION_LISTENER -> TextR.string.access_notification_listener
            AccessRequirement.POST_NOTIFICATIONS -> TextR.string.access_post_notifications
            AccessRequirement.OVERLAY -> TextR.string.access_overlay
            AccessRequirement.WRITE_SETTINGS -> TextR.string.access_write_settings
            AccessRequirement.CAMERA -> TextR.string.access_camera
            AccessRequirement.LOCATION -> TextR.string.access_location
            AccessRequirement.BLUETOOTH_CONNECT -> TextR.string.access_bluetooth
            AccessRequirement.DND_POLICY -> TextR.string.access_dnd_policy
            AccessRequirement.DEVICE_ADMIN -> TextR.string.access_device_admin
            AccessRequirement.CALENDAR -> TextR.string.access_calendar
            AccessRequirement.CONTACTS -> TextR.string.access_contacts
            AccessRequirement.CALL_LOG -> TextR.string.access_call_log
            AccessRequirement.SMS -> TextR.string.access_sms
            AccessRequirement.PHONE -> TextR.string.access_phone
            AccessRequirement.RECORD_AUDIO -> TextR.string.access_record_audio
            AccessRequirement.ACTIVITY_RECOGNITION -> TextR.string.access_activity_recognition
        }
    )

private fun healthRequirementResolution(context: Context, value: AccessRequirement): String =
    when (value) {
        AccessRequirement.ROOT -> context.getString(TextR.string.feature_health_solution_root)
        AccessRequirement.SHIZUKU -> context.getString(TextR.string.feature_health_solution_shizuku)
        AccessRequirement.LSPOSED -> context.getString(TextR.string.feature_health_solution_lsposed)
        AccessRequirement.ZYGISK -> context.getString(TextR.string.feature_health_solution_zygisk)
        else -> context.getString(
            TextR.string.feature_health_solution_generic_access_format,
            healthRequirementLabel(context, value),
        )
    }
