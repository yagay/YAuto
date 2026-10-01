package com.yagay.yauto.platform.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*
import kotlin.math.roundToInt

/**
 * Common Android controls kept separate from the base interaction pack so the whole group can be
 * added, replaced or removed without changing the engine, editor or importers.
 */
class AndroidControlFeaturePack(
    context: Context,
) : FeaturePack {
    override val id: String = "android.controls"
    private val context = context.applicationContext

    override fun install(registry: FeatureRegistry) {
        mediaVolume(registry)
        vibration(registry)
        brightness(registry)
        sendIntent(registry)
        showNotification(registry)
        cancelNotification(registry)
        forceStop(registry)
    }

    private fun mediaVolume(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.audio.media_volume.set"), FeatureKind.ACTION,
                "Set media volume", "Set the music/media stream volume by percentage",
                FeatureCategory.AUDIO,
                fields = listOf(
                    FieldSchema.Number("percent", "Volume percent", true, min = 0.0, max = 100.0),
                    FieldSchema.Toggle("showUi", "Show system volume UI"),
                ),
                keywords = setOf("volume", "media", "music", "音量"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            runCatching {
                val percent = (feature.config["percent"].numberOrNull() ?: 50.0).coerceIn(0.0, 100.0)
                val audio = context.getSystemService(AudioManager::class.java)
                val min = audio.getStreamMinVolume(AudioManager.STREAM_MUSIC)
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val value = (min + (max - min) * percent / 100.0).roundToInt().coerceIn(min, max)
                val flags = if (feature.config.boolean("showUi")) AudioManager.FLAG_SHOW_UI else 0
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, flags)
                ActionExecutionResult(true, ConfigValue.NumberValue(percent))
            }.getOrElse { ActionExecutionResult(false, message = it.message ?: it.javaClass.simpleName) }
        }
    }

    private fun vibration(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.vibrate"), FeatureKind.ACTION,
                "Vibrate", "Vibrate the device for a configurable duration and amplitude",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Duration("durationMs", "Duration"),
                    FieldSchema.Number("amplitude", "Amplitude (1-255)", min = 1.0, max = 255.0),
                ),
                keywords = setOf("vibrate", "haptic", "震动"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            runCatching {
                val duration = feature.config.long("durationMs", 300).coerceIn(1, 60_000)
                val amplitude = (feature.config["amplitude"].numberOrNull()?.roundToInt() ?: VibrationEffect.DEFAULT_AMPLITUDE)
                    .let { if (it == VibrationEffect.DEFAULT_AMPLITUDE) it else it.coerceIn(1, 255) }
                val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
                vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
                ActionExecutionResult(true)
            }.getOrElse { ActionExecutionResult(false, message = it.message ?: it.javaClass.simpleName) }
        }
    }

    private fun brightness(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.display.brightness.set"), FeatureKind.ACTION,
                "Set brightness", "Set manual brightness or enable automatic brightness",
                FeatureCategory.DISPLAY,
                fields = listOf(
                    FieldSchema.Choice("mode", "Mode", true, listOf("manual", "auto")),
                    FieldSchema.Number("percent", "Brightness percent", min = 0.0, max = 100.0),
                ),
                keywords = setOf("brightness", "display", "亮度"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            if (!Settings.System.canWrite(context)) {
                return@registerAction ActionExecutionResult(false, message = "Modify system settings access is not granted")
            }
            runCatching {
                when (feature.config.string("mode", "manual")) {
                    "auto" -> Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
                    )
                    else -> {
                        val percent = (feature.config["percent"].numberOrNull() ?: 50.0).coerceIn(0.0, 100.0)
                        Settings.System.putInt(
                            context.contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS_MODE,
                            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                        )
                        Settings.System.putInt(
                            context.contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS,
                            (255.0 * percent / 100.0).roundToInt().coerceIn(0, 255),
                        )
                    }
                }
                ActionExecutionResult(true)
            }.getOrElse { ActionExecutionResult(false, message = it.message ?: it.javaClass.simpleName) }
        }
    }

    private fun sendIntent(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.intent.send"), FeatureKind.ACTION,
                "Send intent", "Start an activity/service or send an Android broadcast",
                FeatureCategory.SYSTEM,
                fields = listOf(
                    FieldSchema.Choice("target", "Target", true, listOf("activity", "broadcast", "service")),
                    FieldSchema.Text("action", "Action"),
                    FieldSchema.Text("package", "Package"),
                    FieldSchema.Text("class", "Class"),
                    FieldSchema.Text("data", "Data URI"),
                    FieldSchema.Text("mimeType", "MIME type"),
                    FieldSchema.Text("category", "Category"),
                    FieldSchema.Text("extra1Key", "Extra 1 key"),
                    FieldSchema.Text("extra1Value", "Extra 1 value", multiline = true),
                    FieldSchema.Text("extra2Key", "Extra 2 key"),
                    FieldSchema.Text("extra2Value", "Extra 2 value", multiline = true),
                    FieldSchema.Text("extra3Key", "Extra 3 key"),
                    FieldSchema.Text("extra3Value", "Extra 3 value", multiline = true),
                    FieldSchema.Number("flags", "Intent flags"),
                ),
                keywords = setOf("intent", "broadcast", "activity", "service"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            runCatching {
                val action = feature.config.string("action").resolveVariables(ctx.variables).ifBlank { null }
                val intent = if (action != null) Intent(action) else Intent()
                val packageName = feature.config.string("package").resolveVariables(ctx.variables).trim()
                val className = feature.config.string("class").resolveVariables(ctx.variables).trim()
                if (packageName.isNotBlank() && className.isNotBlank()) intent.setClassName(packageName, className)
                else if (packageName.isNotBlank()) intent.setPackage(packageName)

                val data = feature.config.string("data").resolveVariables(ctx.variables).trim()
                val mime = feature.config.string("mimeType").resolveVariables(ctx.variables).trim()
                when {
                    data.isNotBlank() && mime.isNotBlank() -> intent.setDataAndType(Uri.parse(data), mime)
                    data.isNotBlank() -> intent.data = Uri.parse(data)
                    mime.isNotBlank() -> intent.type = mime
                }
                feature.config.string("category").resolveVariables(ctx.variables).trim().takeIf { it.isNotBlank() }?.let(intent::addCategory)
                for (index in 1..3) {
                    val key = feature.config.string("extra${index}Key").resolveVariables(ctx.variables).trim()
                    if (key.isNotBlank()) intent.putExtra(key, feature.config.string("extra${index}Value").resolveVariables(ctx.variables))
                }
                feature.config["flags"].numberOrNull()?.toInt()?.takeIf { it != 0 }?.let(intent::addFlags)

                when (feature.config.string("target", "broadcast")) {
                    "activity" -> context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "service" -> context.startService(intent)
                    else -> context.sendBroadcast(intent)
                }
                ActionExecutionResult(true)
            }.getOrElse { ActionExecutionResult(false, message = it.message ?: it.javaClass.simpleName) }
        }
    }

    private fun showNotification(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.notification.show"), FeatureKind.ACTION,
                "Show notification", "Post a YAuto system notification",
                FeatureCategory.NOTIFICATION,
                fields = listOf(
                    FieldSchema.Text("title", "Title"),
                    FieldSchema.Text("text", "Text", true, true),
                    FieldSchema.Number("id", "Notification ID"),
                    FieldSchema.Text("channelId", "Channel ID"),
                    FieldSchema.Text("channelName", "Channel name"),
                    FieldSchema.Toggle("ongoing", "Ongoing / persistent"),
                ),
                keywords = setOf("notification", "通知"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return@registerAction ActionExecutionResult(false, message = "Notification permission is not granted")
            }
            runCatching {
                val manager = context.getSystemService(NotificationManager::class.java)
                val channelId = feature.config.string("channelId", "yauto.automation").ifBlank { "yauto.automation" }
                val channelName = feature.config.string("channelName", "YAuto Automation").ifBlank { "YAuto Automation" }
                manager.createNotificationChannel(NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT))
                val notification = Notification.Builder(context, channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(feature.config.string("title").resolveVariables(ctx.variables))
                    .setContentText(feature.config.string("text").resolveVariables(ctx.variables))
                    .setStyle(Notification.BigTextStyle().bigText(feature.config.string("text").resolveVariables(ctx.variables)))
                    .setOngoing(feature.config.boolean("ongoing"))
                    .setAutoCancel(!feature.config.boolean("ongoing"))
                    .build()
                val notificationId = feature.config.long("id", 1001).toInt()
                manager.notify(notificationId, notification)
                ActionExecutionResult(true, ConfigValue.NumberValue(notificationId.toDouble()))
            }.getOrElse { ActionExecutionResult(false, message = it.message ?: it.javaClass.simpleName) }
        }
    }

    private fun cancelNotification(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.notification.cancel"), FeatureKind.ACTION,
                "Cancel notification", "Cancel a notification previously posted by YAuto",
                FeatureCategory.NOTIFICATION,
                fields = listOf(FieldSchema.Number("id", "Notification ID", true)),
                ownerPackId = id,
            )
        ) { feature, _ ->
            context.getSystemService(NotificationManager::class.java).cancel(feature.config.long("id", 1001).toInt())
            ActionExecutionResult(true)
        }
    }

    private fun forceStop(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.app.force_stop"), FeatureKind.ACTION,
                "Force stop app", "Force stop a package through Root or Shizuku",
                FeatureCategory.APP,
                fields = listOf(FieldSchema.AppPicker("package", "App / package", true)),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                keywords = setOf("force stop", "kill", "root", "shizuku"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val packageName = feature.config.string("package").resolveVariables(ctx.variables).trim()
            if (!PACKAGE_NAME.matches(packageName)) {
                return@registerAction ActionExecutionResult(false, message = "Invalid package name")
            }
            val result = ctx.capabilities.execute(
                CapabilityRequest(
                    capability = CapabilityIds.PRIVILEGED_SHELL,
                    operationId = "android.app.force_stop",
                    payload = mapOf("command" to ConfigValue.StringValue("am force-stop $packageName")),
                )
            )
            ActionExecutionResult(result.success, result.value, result.message)
        }
    }

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    }
}
