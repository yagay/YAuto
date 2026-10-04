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
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

class AndroidPpnFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.ppn"
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(NotificationManager::class.java)

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.notification.ppn.show"), FeatureKind.ACTION,
                "Show rich persistent notification",
                "Post a tagged YAuto notification with click action, buttons, sound/vibration and optional timeout",
                FeatureCategory.NOTIFICATION,
                fields = listOf(
                    FieldSchema.Text("tag", "Tag", true),
                    FieldSchema.Text("title", "Title"),
                    FieldSchema.Text("message", "Message", true, multiline = true),
                    FieldSchema.Toggle("vibrate", "Vibrate"),
                    FieldSchema.Toggle("sound", "Sound"),
                    FieldSchema.Text("clickAction", "Click action name"),
                    FieldSchema.Text("buttonLabels", "Button labels, one per line", multiline = true),
                    FieldSchema.Text("buttonActions", "Button action names, one per line", multiline = true),
                    FieldSchema.Number("displayTimeSeconds", "Auto remove after seconds (0 = keep)", min = 0.0, max = 86400.0),
                ),
                fieldBehaviors = mapOf(
                    "title" to FieldBehavior(supportsVariables = true),
                    "message" to FieldBehavior(supportsVariables = true),
                    "clickAction" to FieldBehavior(supportsVariables = true),
                    "buttonLabels" to FieldBehavior(supportsVariables = true),
                    "buttonActions" to FieldBehavior(supportsVariables = true),
                ),
                accessRequirements = setOf(AccessRequirement.POST_NOTIFICATIONS),
                keywords = setOf("ppn", "notification", "persistent notification", "buttons", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return@registerAction ActionExecutionResult(false, message = userText("feature.notification_permission_denied"))

            val tag = feature.config.string("tag").resolveVariables(ctx.variables).trim()
            if (tag.isBlank() || tag.length > 128) return@registerAction ActionExecutionResult(false)
            val vibrate = feature.config.boolean("vibrate", false)
            val sound = feature.config.boolean("sound", false)
            val channelId = "yauto.ppn." + (if (sound) "s" else "q") + (if (vibrate) "v" else "n")
            val channel = NotificationChannel(channelId, "YAuto rich notifications", NotificationManager.IMPORTANCE_DEFAULT).apply {
                enableVibration(vibrate)
                if (!sound) setSound(null, null)
            }
            manager.createNotificationChannel(channel)

            val title = feature.config.string("title").resolveVariables(ctx.variables)
            val message = feature.config.string("message").resolveVariables(ctx.variables)
            val clickAction = feature.config.string("clickAction").resolveVariables(ctx.variables)
            val builder = Notification.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)

            if (clickAction.isNotBlank()) {
                builder.setContentIntent(actionPendingIntent(tag, clickAction, -1))
            }

            val labels = feature.config.string("buttonLabels").resolveVariables(ctx.variables)
                .lineSequence().map(String::trim).filter(String::isNotEmpty).take(4).toList()
            val actions = feature.config.string("buttonActions").resolveVariables(ctx.variables)
                .lineSequence().map(String::trim).toList()
            labels.forEachIndexed { index, label ->
                val action = actions.getOrNull(index).orEmpty().ifBlank { "button_" + index }
                builder.addAction(
                    Notification.Action.Builder(
                        null,
                        label,
                        actionPendingIntent(tag, action, index),
                    ).build()
                )
            }
            val timeoutMs = feature.config.long("displayTimeSeconds", 0L).coerceIn(0L, 86_400L) * 1000L
            if (timeoutMs > 0) builder.setTimeoutAfter(timeoutMs)

            runCatching {
                manager.notify(tag, PPN_NOTIFICATION_ID, builder.build())
                ActionExecutionResult(true, ConfigValue.StringValue(tag))
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.notification.ppn.remove"), FeatureKind.ACTION,
                "Remove rich persistent notification",
                "Remove a YAuto rich notification by tag",
                FeatureCategory.NOTIFICATION,
                fields = listOf(FieldSchema.Text("tag", "Tag", true)),
                keywords = setOf("ppn", "notification", "remove", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val tag = feature.config.string("tag").resolveVariables(ctx.variables).trim()
            if (tag.isBlank()) return@registerAction ActionExecutionResult(false)
            manager.cancel(tag, PPN_NOTIFICATION_ID)
            ActionExecutionResult(true)
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.ppn_action"), FeatureKind.EVENT,
                "Rich notification action",
                "Run when a YAuto rich notification body or action button is pressed",
                FeatureCategory.NOTIFICATION,
                fields = listOf(
                    FieldSchema.Text("tag", "Tag"),
                    FieldSchema.Text("action", "Action name"),
                ),
                keywords = setOf("ppn", "notification button", "notification click", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.ppn_action") return@registerEvent false
            val tag = feature.config.string("tag")
            val action = feature.config.string("action")
            (tag.isBlank() || ctx.event.payload.string("tag") == tag) &&
                (action.isBlank() || ctx.event.payload.string("action") == action)
        }
    }

    private fun actionPendingIntent(tag: String, action: String, buttonIndex: Int): PendingIntent {
        val requestCode = (tag + "\u0000" + action + "\u0000" + buttonIndex).hashCode()
        val intent = Intent(PPN_ACTION)
            .setPackage(context.packageName)
            .putExtra("tag", tag)
            .putExtra("action", action)
            .putExtra("buttonIndex", buttonIndex)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val PPN_ACTION = "com.yagay.yauto.PPN_ACTION"
        private const val PPN_NOTIFICATION_ID = 52_001
    }
}
