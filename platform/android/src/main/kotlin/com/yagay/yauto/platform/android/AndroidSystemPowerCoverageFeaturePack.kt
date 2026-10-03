package com.yagay.yauto.platform.android

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

/** Native power/device capabilities that were missing from the existing YAuto Android catalog. */
class AndroidSystemPowerCoverageFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.system_power_coverage"
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver

    override fun install(registry: FeatureRegistry) {
        registerAirplaneModeAction(registry)
        registerTorchAction(registry)
        registerDndAction(registry)

        booleanPair(registry, "airplane_mode", "Airplane mode", "Check whether airplane mode is enabled", FeatureCategory.NETWORK) {
            Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        }
        choicePair(
            registry, "battery_status", "Battery status", "Match the current Android battery status",
            FeatureCategory.DEVICE, "status", "Status", listOf("charging", "discharging", "not_charging", "full", "unknown")
        ) { batteryStatus() }
        choicePair(
            registry, "battery_health", "Battery health", "Match the current battery health",
            FeatureCategory.DEVICE, "health", "Health", listOf("good", "overheat", "dead", "over_voltage", "failure", "cold", "unknown")
        ) { batteryHealth() }
        rangePair(
            registry, "battery_voltage", "Battery voltage", "Compare the current battery voltage",
            FeatureCategory.DEVICE, "minMv", "Minimum mV", "maxMv", "Maximum mV", 0.0, 20_000.0
        ) { batteryIntent()?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it >= 0 }?.toDouble() }
        rangePair(
            registry, "memory_available", "Available memory", "Compare currently available system memory",
            FeatureCategory.DEVICE, "minMb", "Minimum MB", "maxMb", "Maximum MB", 0.0, 1_000_000.0
        ) { memoryInfo().availMem / (1024.0 * 1024.0) }
        booleanPair(registry, "memory_low", "Low-memory state", "Check Android's low-memory signal", FeatureCategory.DEVICE) {
            memoryInfo().lowMemory
        }
        booleanPair(registry, "device_idle", "Device idle mode", "Check whether Android is in device idle mode", FeatureCategory.DEVICE) {
            context.getSystemService(PowerManager::class.java).isDeviceIdleMode
        }
        booleanPair(
            registry, "low_power_standby", "Low-power standby", "Check whether Android low-power standby is enabled",
            FeatureCategory.DEVICE, minSdk = 33
        ) { Build.VERSION.SDK_INT >= 33 && context.getSystemService(PowerManager::class.java).isLowPowerStandbyEnabled }
        choicePair(
            registry, "orientation", "Screen orientation", "Match the current screen orientation",
            FeatureCategory.DISPLAY, "orientation", "Orientation", listOf("portrait", "landscape", "square", "undefined")
        ) {
            when (context.resources.configuration.orientation) {
                Configuration.ORIENTATION_PORTRAIT -> "portrait"
                Configuration.ORIENTATION_LANDSCAPE -> "landscape"
                Configuration.ORIENTATION_SQUARE -> "square"
                else -> "undefined"
            }
        }
        rangePair(
            registry, "font_scale", "System font scale", "Compare the Android system font scale",
            FeatureCategory.DISPLAY, "minScale", "Minimum scale", "maxScale", "Maximum scale", 0.1, 5.0
        ) { context.resources.configuration.fontScale.toDouble() }
        booleanPair(
            registry, "internet_validated", "Validated internet", "Check whether the active network is validated for internet access",
            FeatureCategory.NETWORK
        ) { activeCapabilities()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true }
        booleanPair(registry, "network_roaming", "Network roaming", "Check whether the active network is roaming", FeatureCategory.NETWORK) {
            val caps = activeCapabilities() ?: return@booleanPair false
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
        }
        booleanPair(registry, "vpn_active", "VPN active", "Check whether the active network uses a VPN transport", FeatureCategory.NETWORK) {
            activeCapabilities()?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        booleanPair(registry, "device_secure", "Device secure", "Check whether the device has secure lock-screen credentials", FeatureCategory.DEVICE) {
            context.getSystemService(KeyguardManager::class.java).isDeviceSecure
        }
        batteryOptimizationPair(registry)
    }

    private fun registerAirplaneModeAction(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.airplane_mode.set"), FeatureKind.ACTION,
                "Set airplane mode", "Enable or disable airplane mode through an authorized privileged shell backend",
                FeatureCategory.NETWORK,
                fields = listOf(FieldSchema.Toggle("enabled", "Enabled")),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                keywords = setOf("airplane", "flight mode", "radio", "network"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val enabled = feature.config.boolean("enabled", true)
            val value = if (enabled) 1 else 0
            executeShell(
                "android.airplane_mode.set",
                "settings put global airplane_mode_on $value; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $enabled",
                ctx,
            )
        }
    }

    private fun registerTorchAction(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.flashlight.set"), FeatureKind.ACTION,
                "Set flashlight", "Turn the first available camera flash unit on or off",
                FeatureCategory.DEVICE,
                fields = listOf(FieldSchema.Toggle("enabled", "Enabled")),
                accessRequirements = setOf(AccessRequirement.CAMERA),
                keywords = setOf("flashlight", "torch", "camera flash"), ownerPackId = id,
            )
        ) { feature, _ ->
            runCatching {
                val manager = context.getSystemService(CameraManager::class.java)
                val cameraId = manager.cameraIdList.firstOrNull { cameraId ->
                    manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } ?: error("No flashlight is available")
                manager.setTorchMode(cameraId, feature.config.boolean("enabled", true))
                ActionExecutionResult(true)
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }
    }

    private fun registerDndAction(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.dnd.set"), FeatureKind.ACTION,
                "Set Do Not Disturb", "Request an Android Do Not Disturb interruption filter",
                FeatureCategory.NOTIFICATION,
                fields = listOf(FieldSchema.Choice("filter", "Interruption filter", true, listOf("all", "priority", "alarms", "none"))),
                accessRequirements = setOf(AccessRequirement.DND_POLICY),
                keywords = setOf("dnd", "do not disturb", "zen", "interruption"), ownerPackId = id,
            )
        ) { feature, _ ->
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!manager.isNotificationPolicyAccessGranted) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", "Do Not Disturb access is not granted"))
            }
            val filter = when (feature.config.string("filter", "all")) {
                "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
                "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
                "none" -> NotificationManager.INTERRUPTION_FILTER_NONE
                else -> NotificationManager.INTERRUPTION_FILTER_ALL
            }
            runCatching {
                manager.setInterruptionFilter(filter)
                ActionExecutionResult(true)
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }
    }

    private fun batteryOptimizationPair(registry: FeatureRegistry) {
        pair(
            registry, "battery_optimization_ignored", "Battery optimization ignored",
            "Check whether a package is exempt from battery optimization", FeatureCategory.APP,
            listOf(FieldSchema.AppPicker("package", "Package"), FieldSchema.Toggle("ignored", "Ignored / exempt")),
        ) { feature, ctx ->
            val packageName = feature.config.string("package").resolveVariables(ctx.variables).ifBlank { context.packageName }
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) ==
                feature.config.boolean("ignored", true)
        }
    }

    private fun booleanPair(
        registry: FeatureRegistry,
        key: String,
        title: String,
        description: String,
        category: FeatureCategory,
        minSdk: Int = 31,
        query: () -> Boolean,
    ) = pair(registry, key, title, description, category, listOf(FieldSchema.Toggle("value", "Enabled / true")), minSdk) { feature, _ ->
        query() == feature.config.boolean("value", true)
    }

    private fun choicePair(
        registry: FeatureRegistry,
        key: String,
        title: String,
        description: String,
        category: FeatureCategory,
        fieldKey: String,
        fieldLabel: String,
        options: List<String>,
        query: () -> String,
    ) = pair(registry, key, title, description, category, listOf(FieldSchema.Choice(fieldKey, fieldLabel, true, options))) { feature, _ ->
        query() == feature.config.string(fieldKey, options.first())
    }

    private fun rangePair(
        registry: FeatureRegistry,
        key: String,
        title: String,
        description: String,
        category: FeatureCategory,
        minKey: String,
        minLabel: String,
        maxKey: String,
        maxLabel: String,
        allowedMin: Double,
        allowedMax: Double,
        query: () -> Double?,
    ) = pair(
        registry, key, title, description, category,
        listOf(FieldSchema.Number(minKey, minLabel, min = allowedMin, max = allowedMax), FieldSchema.Number(maxKey, maxLabel, min = allowedMin, max = allowedMax)),
    ) { feature, _ ->
        val min = feature.config[minKey].numberOrNull() ?: allowedMin
        val max = feature.config[maxKey].numberOrNull() ?: allowedMax
        if (!min.isFinite() || !max.isFinite() || min !in allowedMin..allowedMax || max !in min..allowedMax) return@pair false
        query()?.let { it in min..max } ?: false
    }

    private fun pair(
        registry: FeatureRegistry,
        key: String,
        title: String,
        description: String,
        category: FeatureCategory,
        fields: List<FieldSchema>,
        minSdk: Int = 31,
        evaluate: (com.yagay.yauto.core.model.FeatureRef, FeatureExecutionContext) -> Boolean,
    ) {
        val evaluator = ConditionEvaluator { feature, ctx -> runCatching { evaluate(feature, ctx) }.getOrDefault(false) }
        val state = FeatureDescriptor(
            FeatureId("android.state.$key"), FeatureKind.STATE, title, description, category,
            minSdk = minSdk, fields = fields, ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(state.copy(id = FeatureId("android.condition.$key"), kind = FeatureKind.CONDITION), evaluator)
    }

    private fun batteryIntent(): Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun batteryStatus(): String = when (batteryIntent()?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        else -> "unknown"
    }

    private fun batteryHealth(): String = when (batteryIntent()?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
        BatteryManager.BATTERY_HEALTH_COLD -> "cold"
        else -> "unknown"
    }

    private fun memoryInfo(): ActivityManager.MemoryInfo = ActivityManager.MemoryInfo().also {
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
    }

    private fun activeCapabilities(): NetworkCapabilities? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.activeNetwork?.let(manager::getNetworkCapabilities)
    }

    private suspend fun executeShell(operationId: String, command: String, ctx: FeatureExecutionContext): ActionExecutionResult {
        val result = ctx.capabilities.execute(
            CapabilityRequest(
                capability = CapabilityIds.PRIVILEGED_SHELL,
                operationId = operationId,
                payload = mapOf("command" to ConfigValue.StringValue(command)),
            )
        )
        return ActionExecutionResult(result.success, result.value, result.message)
    }
}
