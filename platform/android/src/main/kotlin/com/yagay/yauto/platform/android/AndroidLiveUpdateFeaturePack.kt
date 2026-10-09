package com.yagay.yauto.platform.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

/**
 * Android 16+ first-party promoted ongoing notifications (Live Updates).
 *
 * This requests the genuine SystemUI chip and never substitutes a floating overlay.
 * System and user settings decide whether a promoted chip actually appears.
 *
 * It does not emulate ShortX click/long-click Action Any chains and is never mapped
 * automatically from ShortX ShowStatusBarChip/HideStatusBarClip imports.
 */
class AndroidLiveUpdateFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.notification.live_update"
    private val app = context.applicationContext
    private val manager = app.getSystemService(NotificationManager::class.java)

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.notification.live_update.control"),
                FeatureKind.ACTION,
                "Android 16 Live Update chip",
                "Request or end an Android promoted ongoing notification for active navigation, a ride, or delivery",
                FeatureCategory.NOTIFICATION,
                fields = listOf(
                    FieldSchema.Choice("mode", "Operation", true, listOf("show", "remove")),
                    FieldSchema.Text("tag", "Live Update ID", true),
                    FieldSchema.Choice("purpose", "Active, user-initiated activity", true, listOf("navigation", "ride", "delivery")),
                    FieldSchema.Text("title", "Activity title"),
                    FieldSchema.Text("message", "Current activity progress"),
                ),
                fieldBehaviors = mapOf(
                    "tag" to FieldBehavior(supportsVariables = true),
                    "title" to FieldBehavior(supportsVariables = true),
                    "message" to FieldBehavior(supportsVariables = true),
                ),
                minSdk = 36,
                accessRequirements = setOf(AccessRequirement.POST_NOTIFICATIONS),
                keywords = setOf("system chip", "status bar chip", "live update", "Android 16", "progress"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (Build.VERSION.SDK_INT < 36) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.live_update_unsupported"))
            }
            val mode = feature.config.string("mode", "show")
            val tag = feature.config.string("tag").resolveVariables(ctx.variables).trim()
            if (!liveUpdateTagValid(tag) || mode !in setOf("show", "remove")) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.live_update_invalid"))
            }
            val notificationTag = "yauto.live." + tag
            if (mode == "remove") {
                manager.cancel(notificationTag, NOTIFICATION_ID)
                return@registerAction ActionExecutionResult(true, ConfigValue.StringValue(tag))
            }
            val purpose = feature.config.string("purpose")
            val title = feature.config.string("title").resolveVariables(ctx.variables).trim()
            val message = feature.config.string("message").resolveVariables(ctx.variables).trim()
            if (purpose !in PURPOSES || title.isBlank() || message.isBlank() || title.length > 100 || message.length > 500) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.live_update_invalid"))
            }
            if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.notification_permission_denied"))
            }
            if (!liveUpdatePromotionAllowed(manager)) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.live_update_promotion_disabled"))
            }
            val channel = NotificationChannel(CHANNEL, "YAuto Live Updates", NotificationManager.IMPORTANCE_DEFAULT)
            manager.createNotificationChannel(channel)
            val launchIntent = app.packageManager.getLaunchIntentForPackage(app.packageName)
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.live_update_invalid"))
            val open = PendingIntent.getActivity(
                app, tag.hashCode(), launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = Notification.Builder(app, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setSubText(purpose)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
            // Android 16 initial and QPR versions can expose different platform methods.
            if (!liveUpdateRequestPromotion(builder)) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.live_update_unsupported"))
            }
            val notification = builder.build()
            if (!liveUpdatePromotable(notification)) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.live_update_not_eligible"))
            }
            return@registerAction try {
                manager.notify(notificationTag, NOTIFICATION_ID, notification)
                // True here means a valid promoted notification was posted, not that the OEM
                // rendered a chip; the latter is controlled by the system and user settings.
                ActionExecutionResult(true, ConfigValue.StringValue(tag))
            } catch (_: SecurityException) {
                ActionExecutionResult(false, message = userText("feature.notification_permission_denied"))
            }
        }
    }

    private companion object {
        const val CHANNEL = "yauto.live_updates"
        const val NOTIFICATION_ID = 7349
        val PURPOSES = setOf("navigation", "ride", "delivery")
    }
}

internal fun liveUpdateTagValid(value: String): Boolean =
    value.length in 1..48 && Regex("[a-zA-Z][a-zA-Z0-9_.-]*").matches(value)

/** Call QPR-gated platform methods defensively: SDK_INT 36 alone is insufficient. */
private fun liveUpdatePromotionAllowed(manager: NotificationManager): Boolean =
    runCatching {
        NotificationManager::class.java.getMethod("canPostPromotedNotifications")
            .invoke(manager) as? Boolean == true
    }.getOrDefault(false)

private fun liveUpdateRequestPromotion(builder: Notification.Builder): Boolean =
    runCatching {
        Notification.Builder::class.java.getMethod(
            "setRequestPromotedOngoing", Boolean::class.javaPrimitiveType
        ).invoke(builder, true)
        true
    }.getOrDefault(false)

private fun liveUpdatePromotable(notification: Notification): Boolean =
    runCatching {
        Notification::class.java.getMethod("hasPromotableCharacteristics")
            .invoke(notification) as? Boolean == true
    }.getOrDefault(false)
