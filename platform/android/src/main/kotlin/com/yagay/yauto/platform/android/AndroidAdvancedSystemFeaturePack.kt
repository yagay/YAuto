package com.yagay.yauto.platform.android

import android.content.Context
import android.provider.Settings
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import java.util.Locale

/** Stable AOSP shell/settings controls that are broader than ordinary public Android APIs. */
class AndroidAdvancedSystemFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.advanced_system"
    private val context = context.applicationContext

    override fun install(registry: FeatureRegistry) {
        registerRotation(registry)
        registerFontScale(registry)
        registerAnimationScale(registry)
        registerDisplaySize(registry)
        registerDisplayDensity(registry)
        registerDataSaver(registry)
        registerPrivateDns(registry)
        registerAutomaticTime(registry)
        registerDeviceIdle(registry)
        registerShutdown(registry)
        registerAutoTimeState(registry, FeatureKind.STATE, "android.state.auto_time")
        registerAutoTimeState(registry, FeatureKind.CONDITION, "android.condition.auto_time")
    }

    private fun registerRotation(registry: FeatureRegistry) {
        action(
            registry,
            "android.display.rotation.set",
            "Set fixed screen rotation",
            "Disable automatic rotation and lock Android to a selected display rotation",
            FeatureCategory.DISPLAY,
            listOf(FieldSchema.Choice("rotation", "Rotation", true, listOf("0", "90", "180", "270"))),
            setOf("rotation", "orientation", "portrait", "landscape"),
        ) { feature, ctx ->
            val command = rotationCommand(feature.config.string("rotation", "0"))
                ?: return@action ActionExecutionResult(false, message = userText("feature.rotation_invalid"))
            executeShell("android.display.rotation.set", command, ctx)
        }
    }

    private fun registerFontScale(registry: FeatureRegistry) {
        action(
            registry,
            "android.display.font_scale.set",
            "Set system font scale",
            "Change Android system font scaling through privileged settings",
            FeatureCategory.DISPLAY,
            listOf(FieldSchema.Number("scale", "Font scale", true, min = 0.5, max = 2.0)),
            setOf("font", "scale", "text size", "display"),
        ) { feature, ctx ->
            val scale = feature.config["scale"].numberOrNull()
                ?: return@action ActionExecutionResult(false, message = userText("feature.font_scale_invalid"))
            val command = fontScaleCommand(scale)
                ?: return@action ActionExecutionResult(false, message = userText("feature.font_scale_invalid"))
            executeShell("android.display.font_scale.set", command, ctx)
        }
    }

    private fun registerAnimationScale(registry: FeatureRegistry) {
        action(
            registry,
            "android.display.animation_scale.set",
            "Set system animation scale",
            "Set Android window, transition and animator duration scales together",
            FeatureCategory.DISPLAY,
            listOf(FieldSchema.Number("scale", "Animation scale", true, min = 0.0, max = 10.0)),
            setOf("animation", "scale", "developer", "transition"),
        ) { feature, ctx ->
            val scale = feature.config["scale"].numberOrNull()
                ?: return@action ActionExecutionResult(false, message = userText("feature.animation_scale_invalid"))
            val command = animationScaleCommand(scale)
                ?: return@action ActionExecutionResult(false, message = userText("feature.animation_scale_invalid"))
            executeShell("android.display.animation_scale.set", command, ctx)
        }
    }

    private fun registerDisplaySize(registry: FeatureRegistry) {
        action(
            registry,
            "android.display.size.set",
            "Set display resolution override",
            "Apply or reset Android wm display-size override",
            FeatureCategory.DISPLAY,
            listOf(
                FieldSchema.Toggle("reset", "Reset to physical size"),
                FieldSchema.Number("width", "Width pixels", min = 320.0, max = 10000.0),
                FieldSchema.Number("height", "Height pixels", min = 320.0, max = 10000.0),
            ),
            setOf("resolution", "display size", "wm size", "width", "height"),
        ) { feature, ctx ->
            val reset = feature.config.boolean("reset")
            val width = feature.config["width"].numberOrNull()?.toInt()
            val height = feature.config["height"].numberOrNull()?.toInt()
            val command = displaySizeCommand(reset, width, height)
                ?: return@action ActionExecutionResult(false, message = userText("feature.display_size_invalid"))
            executeShell("android.display.size.set", command, ctx)
        }
    }

    private fun registerDisplayDensity(registry: FeatureRegistry) {
        action(
            registry,
            "android.display.density.set",
            "Set display density override",
            "Apply or reset Android wm display-density override",
            FeatureCategory.DISPLAY,
            listOf(
                FieldSchema.Toggle("reset", "Reset to physical density"),
                FieldSchema.Number("dpi", "Density DPI", min = 120.0, max = 1000.0),
            ),
            setOf("dpi", "density", "display", "wm density"),
        ) { feature, ctx ->
            val command = displayDensityCommand(
                feature.config.boolean("reset"),
                feature.config["dpi"].numberOrNull()?.toInt(),
            ) ?: return@action ActionExecutionResult(false, message = userText("feature.display_density_invalid"))
            executeShell("android.display.density.set", command, ctx)
        }
    }

    private fun registerDataSaver(registry: FeatureRegistry) {
        action(
            registry,
            "android.network.data_saver.set",
            "Set data saver",
            "Enable or disable Android background-data restriction through netpolicy",
            FeatureCategory.NETWORK,
            listOf(FieldSchema.Toggle("enabled", "Data saver enabled")),
            setOf("data saver", "netpolicy", "background data", "network"),
        ) { feature, ctx ->
            executeShell("android.network.data_saver.set", dataSaverCommand(feature.config.boolean("enabled", true)), ctx)
        }
    }

    private fun registerPrivateDns(registry: FeatureRegistry) {
        action(
            registry,
            "android.network.private_dns.set",
            "Set private DNS",
            "Set Android private DNS to off, automatic, or a validated hostname",
            FeatureCategory.NETWORK,
            listOf(
                FieldSchema.Choice("mode", "Private DNS mode", true, listOf("off", "automatic", "hostname")),
                FieldSchema.Text("hostname", "Private DNS hostname"),
            ),
            setOf("private dns", "dns over tls", "hostname", "network"),
        ) { feature, ctx ->
            val mode = feature.config.string("mode", "automatic")
            val hostname = feature.config.string("hostname").resolveVariables(ctx.variables).trim()
            val command = privateDnsCommand(mode, hostname)
                ?: return@action ActionExecutionResult(false, message = userText("feature.private_dns_invalid"))
            executeShell("android.network.private_dns.set", command, ctx)
        }
    }

    private fun registerAutomaticTime(registry: FeatureRegistry) {
        action(
            registry,
            "android.time.automatic.set",
            "Set automatic time settings",
            "Enable or disable Android automatic time and automatic time-zone settings",
            FeatureCategory.SYSTEM,
            listOf(
                FieldSchema.Toggle("autoTime", "Automatic time"),
                FieldSchema.Toggle("autoTimeZone", "Automatic time zone"),
            ),
            setOf("time", "time zone", "automatic", "clock"),
        ) { feature, ctx ->
            executeShell(
                "android.time.automatic.set",
                automaticTimeCommand(feature.config.boolean("autoTime", true), feature.config.boolean("autoTimeZone", true)),
                ctx,
            )
        }
    }

    private fun registerDeviceIdle(registry: FeatureRegistry) {
        action(
            registry,
            "android.power.device_idle.set",
            "Force or release device idle",
            "Force Android DeviceIdle into idle mode or release a previous forced-idle state",
            FeatureCategory.SYSTEM,
            listOf(FieldSchema.Toggle("forcedIdle", "Forced idle")),
            setOf("doze", "device idle", "battery", "force idle"),
        ) { feature, ctx ->
            executeShell("android.power.device_idle.set", deviceIdleCommand(feature.config.boolean("forcedIdle", true)), ctx)
        }
    }

    private fun registerShutdown(registry: FeatureRegistry) {
        action(
            registry,
            "android.device.shutdown",
            "Shut down device",
            "Request a normal Android shutdown through the power service",
            FeatureCategory.SYSTEM,
            emptyList(),
            setOf("shutdown", "power off", "device"),
        ) { _, ctx -> executeShell("android.device.shutdown", "svc power shutdown", ctx) }
    }

    private fun registerAutoTimeState(registry: FeatureRegistry, kind: FeatureKind, typeId: String) {
        val descriptor = FeatureDescriptor(
            FeatureId(typeId), kind,
            "Automatic time settings", "Check Android automatic time and time-zone settings",
            FeatureCategory.SYSTEM,
            fields = listOf(
                FieldSchema.Choice("setting", "Setting", true, listOf("time", "time_zone")),
                FieldSchema.Toggle("value", "Enabled"),
            ),
            keywords = setOf("automatic time", "time zone", "clock"),
            ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, _ ->
            val enabled = when (feature.config.string("setting", "time")) {
                "time_zone" -> Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME_ZONE, 0) == 1
                else -> Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME, 0) == 1
            }
            enabled == feature.config.boolean("value", true)
        }
        if (kind == FeatureKind.STATE) registry.registerState(descriptor, evaluator) else registry.registerCondition(descriptor, evaluator)
    }

    private fun action(
        registry: FeatureRegistry,
        typeId: String,
        title: String,
        description: String,
        category: FeatureCategory,
        fields: List<FieldSchema>,
        keywords: Set<String>,
        execute: suspend (com.yagay.yauto.core.model.FeatureRef, FeatureExecutionContext) -> ActionExecutionResult,
    ) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId(typeId), FeatureKind.ACTION, title, description, category,
                fields = fields,
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                keywords = keywords,
                ownerPackId = id,
            ),
            ActionExecutor(execute),
        )
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

internal fun rotationCommand(rotation: String): String? {
    val value = when (rotation) {
        "0" -> 0
        "90" -> 1
        "180" -> 2
        "270" -> 3
        else -> return null
    }
    return "settings put system accelerometer_rotation 0; settings put system user_rotation $value"
}

internal fun fontScaleCommand(scale: Double): String? =
    scale.takeIf { it.isFinite() && it in 0.5..2.0 }?.let { "settings put system font_scale ${decimal(it)}" }

internal fun animationScaleCommand(scale: Double): String? =
    scale.takeIf { it.isFinite() && it in 0.0..10.0 }?.let {
        val value = decimal(it)
        "settings put global window_animation_scale $value; settings put global transition_animation_scale $value; settings put global animator_duration_scale $value"
    }

internal fun displaySizeCommand(reset: Boolean, width: Int?, height: Int?): String? = when {
    reset -> "wm size reset"
    width != null && height != null && width in 320..10000 && height in 320..10000 -> "wm size ${width}x$height"
    else -> null
}

internal fun displayDensityCommand(reset: Boolean, dpi: Int?): String? = when {
    reset -> "wm density reset"
    dpi != null && dpi in 120..1000 -> "wm density $dpi"
    else -> null
}

internal fun dataSaverCommand(enabled: Boolean): String =
    "cmd netpolicy set restrict-background ${if (enabled) "true" else "false"}"

internal fun privateDnsCommand(mode: String, hostname: String): String? = when (mode) {
    "off" -> "settings put global private_dns_mode off; settings delete global private_dns_specifier"
    "automatic" -> "settings put global private_dns_mode opportunistic; settings delete global private_dns_specifier"
    "hostname" -> hostname.takeIf(::isValidDnsHostname)?.let {
        "settings put global private_dns_mode hostname; settings put global private_dns_specifier $it"
    }
    else -> null
}

internal fun automaticTimeCommand(autoTime: Boolean, autoTimeZone: Boolean): String =
    "settings put global auto_time ${if (autoTime) 1 else 0}; settings put global auto_time_zone ${if (autoTimeZone) 1 else 0}"

internal fun deviceIdleCommand(forcedIdle: Boolean): String =
    if (forcedIdle) "cmd deviceidle force-idle" else "cmd deviceidle unforce"

internal fun isValidDnsHostname(hostname: String): Boolean {
    if (hostname.length !in 1..253 || hostname.contains("..")) return false
    return hostname.split('.').all { label ->
        label.length in 1..63 && label.firstOrNull()?.isLetterOrDigit() == true &&
            label.lastOrNull()?.isLetterOrDigit() == true && label.all { it.isLetterOrDigit() || it == '-' }
    }
}

private fun decimal(value: Double): String = String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')
