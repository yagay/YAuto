package com.yagay.yauto.platform.android

import android.Manifest
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.view.KeyEvent
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

/** High-frequency device controls shared by MacroDroid/Tasker/ShortX style automations. */
class AndroidMediaDeviceFeaturePack(context: Context) : FeaturePack {
    override val id = "android.media_device"
    private val context = context.applicationContext

    override fun install(registry: FeatureRegistry) {
        mediaControl(registry)
        clipboardRead(registry)
        torch(registry)
        doNotDisturb(registry)
        musicActive(registry)
        doNotDisturbState(registry)
    }

    private fun mediaControl(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.media.control"), FeatureKind.ACTION,
                "Media control", "Send a standard Android media transport command",
                FeatureCategory.AUDIO,
                fields = listOf(FieldSchema.Choice("command", "Command", true, listOf(
                    "play_pause", "play", "pause", "next", "previous", "stop"
                ))),
                keywords = setOf("media", "play", "pause", "next", "previous", "音乐", "播放"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            val keyCode = when (feature.config.string("command", "play_pause")) {
                "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
                else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            }
            runCatching {
                val audio = context.getSystemService(AudioManager::class.java)
                val now = android.os.SystemClock.uptimeMillis()
                audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
                audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
                ActionExecutionResult(true)
            }.getOrElse { ActionExecutionResult(false, message = it.message ?: it.javaClass.simpleName) }
        }
    }

    private fun clipboardRead(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.clipboard.get"), FeatureKind.ACTION,
                "Read clipboard", "Read the current clipboard text into a variable when Android permits background clipboard access",
                FeatureCategory.DEVICE,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store text in variable", true)),
                keywords = setOf("clipboard", "paste", "剪贴板"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val variable = feature.config.string("resultVariable").trim()
            if (variable.isBlank()) return@registerAction ActionExecutionResult(false, message = "Result variable is empty")
            runCatching {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                val clip = clipboard.primaryClip
                val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                val value = ConfigValue.StringValue(text)
                ctx.variables.set(variable, value)
                ActionExecutionResult(true, value)
            }.getOrElse {
                ActionExecutionResult(false, message = "Clipboard is unavailable: ${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    private fun torch(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.torch.set"), FeatureKind.ACTION,
                "Torch / flashlight", "Turn the first available flash unit on or off",
                FeatureCategory.DEVICE,
                fields = listOf(FieldSchema.Toggle("enabled", "Torch on")),
                accessRequirements = setOf(AccessRequirement.CAMERA),
                keywords = setOf("torch", "flashlight", "flash", "手电筒", "闪光灯"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return@registerAction ActionExecutionResult(false, message = "Camera permission is not granted")
            }
            runCatching {
                val manager = context.getSystemService(CameraManager::class.java)
                val cameraId = manager.cameraIdList.firstOrNull { camera ->
                    manager.getCameraCharacteristics(camera).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } ?: error("No camera flash is available")
                manager.setTorchMode(cameraId, feature.config["enabled"].let { (it as? ConfigValue.BooleanValue)?.value ?: true })
                ActionExecutionResult(true)
            }.getOrElse { ActionExecutionResult(false, message = it.message ?: it.javaClass.simpleName) }
        }
    }

    private fun doNotDisturb(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.dnd.set"), FeatureKind.ACTION,
                "Do Not Disturb", "Set Android interruption filter when notification policy access is granted",
                FeatureCategory.DEVICE,
                fields = listOf(FieldSchema.Choice("mode", "Mode", true, listOf("all", "priority", "alarms", "none"))),
                accessRequirements = setOf(AccessRequirement.DND_POLICY),
                keywords = setOf("dnd", "do not disturb", "silent", "勿扰"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!manager.isNotificationPolicyAccessGranted) {
                return@registerAction ActionExecutionResult(false, message = "Notification policy access is not granted")
            }
            val filter = when (feature.config.string("mode", "all")) {
                "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
                "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
                "none" -> NotificationManager.INTERRUPTION_FILTER_NONE
                else -> NotificationManager.INTERRUPTION_FILTER_ALL
            }
            runCatching {
                manager.setInterruptionFilter(filter)
                ActionExecutionResult(true, ConfigValue.StringValue(feature.config.string("mode", "all")))
            }.getOrElse { ActionExecutionResult(false, message = it.message ?: it.javaClass.simpleName) }
        }
    }

    private fun musicActive(registry: FeatureRegistry) {
        registerStateAndCondition(
            registry,
            key = "music_active",
            title = "Music / media active",
            category = FeatureCategory.AUDIO,
            fields = listOf(FieldSchema.Toggle("value", "Playing / active")),
        ) { feature ->
            val actual = context.getSystemService(AudioManager::class.java).isMusicActive
            val expected = (feature.config["value"] as? ConfigValue.BooleanValue)?.value ?: true
            actual == expected
        }
    }

    private fun doNotDisturbState(registry: FeatureRegistry) {
        registerStateAndCondition(
            registry,
            key = "dnd",
            title = "Do Not Disturb mode",
            category = FeatureCategory.DEVICE,
            fields = listOf(FieldSchema.Choice("mode", "Mode", true, listOf("all", "priority", "alarms", "none"))),
            accessRequirements = setOf(AccessRequirement.DND_POLICY),
        ) { feature ->
            val manager = context.getSystemService(NotificationManager::class.java)
            val actual = when (manager.currentInterruptionFilter) {
                NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
                NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
                NotificationManager.INTERRUPTION_FILTER_NONE -> "none"
                else -> "all"
            }
            actual == feature.config.string("mode", "all")
        }
    }

    private fun registerStateAndCondition(
        registry: FeatureRegistry,
        key: String,
        title: String,
        category: FeatureCategory,
        fields: List<FieldSchema>,
        accessRequirements: Set<AccessRequirement> = emptySet(),
        evaluate: (FeatureRef) -> Boolean,
    ) {
        val stateId = "android.state.$key"
        val conditionId = "android.condition.$key"
        registry.registerState(
            FeatureDescriptor(FeatureId(stateId), FeatureKind.STATE, title, "Evaluate current Android state", category,
                fields = fields, accessRequirements = accessRequirements, ownerPackId = id)
        ) { feature, _ -> runCatching { evaluate(feature) }.getOrDefault(false) }
        registry.registerCondition(
            FeatureDescriptor(FeatureId(conditionId), FeatureKind.CONDITION, title, "Evaluate current Android state", category,
                fields = fields, accessRequirements = accessRequirements, ownerPackId = id)
        ) { feature, _ -> runCatching { evaluate(feature) }.getOrDefault(false) }
    }
}
