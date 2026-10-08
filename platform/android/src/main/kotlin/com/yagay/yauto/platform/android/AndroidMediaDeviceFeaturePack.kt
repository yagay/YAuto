package com.yagay.yauto.platform.android

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*
import com.yagay.yauto.core.model.userText

/** High-frequency device controls shared by MacroDroid/Tasker/ShortX style automations. */
class AndroidMediaDeviceFeaturePack(context: Context) : FeaturePack {
    override val id = "android.media_device"
    private val context = context.applicationContext

    override fun install(registry: FeatureRegistry) {
        torch(registry)
        doNotDisturb(registry)
        doNotDisturbState(registry)
    }

    private fun torch(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.torch.set"), FeatureKind.ACTION,
                "Torch / flashlight", "Turn the first available flash unit on or off",
                FeatureCategory.DEVICE,
                fields = listOf(FieldSchema.Toggle("enabled", "Torch on")),
                accessRequirements = setOf(AccessRequirement.CAMERA),
                keywords = setOf("torch", "flashlight", "flash"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.camera_permission_denied"))
            }
            runCatching {
                val manager = context.getSystemService(CameraManager::class.java)
                val cameraId = manager.cameraIdList.firstOrNull { camera ->
                    manager.getCameraCharacteristics(camera).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } ?: error("No camera flash is available")
                manager.setTorchMode(cameraId, feature.config["enabled"].let { (it as? ConfigValue.BooleanValue)?.value ?: true })
                ActionExecutionResult(true)
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
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
                keywords = setOf("dnd", "do not disturb", "silent"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!manager.isNotificationPolicyAccessGranted) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.notification_policy_denied"))
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
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
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
