package com.yagay.yauto.platform.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Build
import android.text.format.DateFormat
import android.view.View
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import java.util.TimeZone

/**
 * Native Android environment features selected from gaps found while comparing
 * MacroDroid, ShortX and Tasker with YAuto. Keep this pack limited to public
 * Android APIs and read-only environment state so it stays portable.
 *
 * This pack intentionally contributes exactly 50 user-facing features:
 * 2 actions + 24 state/condition pairs.
 */
class AndroidNativeEnvironmentFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.native_environment"
    private val context = context.applicationContext
    private val clipboard = this.context.getSystemService(ClipboardManager::class.java)
    private val battery = this.context.getSystemService(BatteryManager::class.java)

    override fun install(registry: FeatureRegistry) {
        registerClipboardRead(registry)
        registerClipboardWrite(registry)

        numberPair(registry, "battery_current", "Battery current", "Compare Android battery current-now in microamps", FeatureCategory.DEVICE, -20_000_000.0, 20_000_000.0) {
            battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).takeUnless { it == Int.MIN_VALUE }?.toDouble()
        }
        numberPair(registry, "battery_charge_counter", "Battery charge counter", "Compare Android remaining battery charge in microamp-hours", FeatureCategory.DEVICE, 0.0, 100_000_000.0) {
            battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).takeUnless { it == Int.MIN_VALUE }?.toDouble()
        }
        numberPair(registry, "battery_energy_counter", "Battery energy counter", "Compare Android remaining battery energy in nanowatt-hours", FeatureCategory.DEVICE, 0.0, 1.0e12) {
            battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER).takeUnless { it == Long.MIN_VALUE }?.toDouble()
        }
        booleanPair(registry, "clock_24h", "24-hour clock", "Check whether Android currently uses 24-hour time", FeatureCategory.SYSTEM) {
            DateFormat.is24HourFormat(context)
        }
        textPair(registry, "timezone", "Time zone", "Match the current Android time-zone ID", FeatureCategory.SYSTEM, "zoneContains", "Time-zone ID contains") {
            TimeZone.getDefault().id
        }
        textPair(registry, "locale", "System locale", "Match any configured Android language tag", FeatureCategory.SYSTEM, "languageTagContains", "Language tag contains") {
            val locales = context.resources.configuration.locales
            buildString {
                for (index in 0 until locales.size()) {
                    if (isNotEmpty()) append(',')
                    append(locales[index].toLanguageTag())
                }
            }
        }
        textPair(registry, "battery_technology", "Battery technology", "Match the battery technology reported by Android", FeatureCategory.DEVICE, "technologyContains", "Technology contains") {
            batteryIntent()?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY).orEmpty()
        }
        numberPair(registry, "build_sdk", "Android SDK level", "Compare the current Android SDK/API level", FeatureCategory.DEVICE, 1.0, 100.0) { Build.VERSION.SDK_INT.toDouble() }
        textPair(registry, "build_release", "Android release", "Match the Android release version string", FeatureCategory.DEVICE, "valueContains", "Release contains") { Build.VERSION.RELEASE.orEmpty() }
        textPair(registry, "build_security_patch", "Security patch level", "Match the Android security patch level", FeatureCategory.DEVICE, "valueContains", "Patch level contains") { Build.VERSION.SECURITY_PATCH.orEmpty() }
        textPair(registry, "build_manufacturer", "Device manufacturer", "Match the device manufacturer", FeatureCategory.DEVICE, "valueContains", "Manufacturer contains") { Build.MANUFACTURER.orEmpty() }
        textPair(registry, "build_brand", "Device brand", "Match the Android device brand", FeatureCategory.DEVICE, "valueContains", "Brand contains") { Build.BRAND.orEmpty() }
        textPair(registry, "build_model", "Device model", "Match the Android device model", FeatureCategory.DEVICE, "valueContains", "Model contains") { Build.MODEL.orEmpty() }
        textPair(registry, "build_device", "Build device", "Match the Android build device identifier", FeatureCategory.DEVICE, "valueContains", "Device ID contains") { Build.DEVICE.orEmpty() }
        textPair(registry, "build_product", "Build product", "Match the Android build product identifier", FeatureCategory.DEVICE, "valueContains", "Product contains") { Build.PRODUCT.orEmpty() }
        textPair(registry, "build_type", "Build type", "Match the Android build type such as user or userdebug", FeatureCategory.DEVICE, "valueContains", "Build type contains") { Build.TYPE.orEmpty() }
        textPair(registry, "build_tags", "Build tags", "Match Android build tags", FeatureCategory.DEVICE, "valueContains", "Build tags contain") { Build.TAGS.orEmpty() }
        textPair(registry, "primary_abi", "Primary ABI", "Match the primary supported CPU ABI", FeatureCategory.DEVICE, "valueContains", "ABI contains") { Build.SUPPORTED_ABIS.firstOrNull().orEmpty() }
        numberPair(registry, "screen_density_dpi", "Screen density DPI", "Compare the current display density DPI", FeatureCategory.DISPLAY, 1.0, 4000.0) { context.resources.displayMetrics.densityDpi.toDouble() }
        numberPair(registry, "screen_width_dp", "Screen width DP", "Compare the current configuration screen width in dp", FeatureCategory.DISPLAY, 0.0, 20_000.0) { context.resources.configuration.screenWidthDp.toDouble() }
        numberPair(registry, "screen_height_dp", "Screen height DP", "Compare the current configuration screen height in dp", FeatureCategory.DISPLAY, 0.0, 20_000.0) { context.resources.configuration.screenHeightDp.toDouble() }
        numberPair(registry, "smallest_width_dp", "Smallest screen width DP", "Compare Android smallestScreenWidthDp", FeatureCategory.DISPLAY, 0.0, 20_000.0) { context.resources.configuration.smallestScreenWidthDp.toDouble() }
        choicePair(registry, "ui_night_mode", "Night mode configuration", "Match the current Android UI night-mode configuration", FeatureCategory.DISPLAY, "mode", "Night mode", listOf("yes", "no", "undefined")) {
            when (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
                Configuration.UI_MODE_NIGHT_YES -> "yes"
                Configuration.UI_MODE_NIGHT_NO -> "no"
                else -> "undefined"
            }
        }
        booleanPair(registry, "layout_direction_rtl", "Right-to-left layout", "Check whether the current configuration uses RTL layout direction", FeatureCategory.DISPLAY) {
            context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        }
    }

    private fun registerClipboardRead(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.clipboard.read"), FeatureKind.ACTION,
                "Read clipboard text", "Read current clipboard text into a YAuto variable when Android privacy rules allow it",
                FeatureCategory.DATA,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store clipboard text in variable", true)),
                keywords = setOf("clipboard", "copy", "paste", "text"), ownerPackId = id,
            )
        ) { feature, ctx ->
            runCatching {
                val clip = clipboard.primaryClip
                val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                val output = ConfigValue.StringValue(text)
                ctx.variables.set(feature.config.string("resultVariable"), output)
                ActionExecutionResult(true, output)
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }
    }

    private fun registerClipboardWrite(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.clipboard.write"), FeatureKind.ACTION,
                "Write clipboard text", "Replace the Android primary clipboard text after resolving YAuto variables",
                FeatureCategory.DATA,
                fields = listOf(FieldSchema.Text("text", "Clipboard text", true), FieldSchema.Text("label", "Clipboard label")),
                keywords = setOf("clipboard", "copy", "paste", "text"), ownerPackId = id,
            )
        ) { feature, ctx ->
            runCatching {
                val text = feature.config.string("text").resolveVariables(ctx.variables)
                val label = feature.config.string("label", "YAuto").resolveVariables(ctx.variables).ifBlank { "YAuto" }
                clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
                ActionExecutionResult(true, ConfigValue.StringValue(text))
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }
    }

    private fun batteryIntent(): Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun booleanPair(registry: FeatureRegistry, key: String, title: String, description: String, category: FeatureCategory, query: () -> Boolean) =
        pair(registry, key, title, description, category, listOf(FieldSchema.Toggle("value", "Enabled / true"))) { feature, _ -> query() == feature.config.boolean("value", true) }

    private fun textPair(registry: FeatureRegistry, key: String, title: String, description: String, category: FeatureCategory, fieldKey: String, fieldLabel: String, query: () -> String) =
        pair(registry, key, title, description, category, listOf(FieldSchema.Text(fieldKey, fieldLabel, true))) { feature, ctx ->
            val expected = feature.config.string(fieldKey).resolveVariables(ctx.variables).trim()
            expected.isNotEmpty() && query().contains(expected, ignoreCase = true)
        }

    private fun choicePair(registry: FeatureRegistry, key: String, title: String, description: String, category: FeatureCategory, fieldKey: String, fieldLabel: String, options: List<String>, query: () -> String) =
        pair(registry, key, title, description, category, listOf(FieldSchema.Choice(fieldKey, fieldLabel, true, options))) { feature, _ -> query() == feature.config.string(fieldKey, options.first()) }

    private fun numberPair(registry: FeatureRegistry, key: String, title: String, description: String, category: FeatureCategory, allowedMin: Double, allowedMax: Double, query: () -> Double?) =
        pair(registry, key, title, description, category, listOf(FieldSchema.Number("min", "Minimum", min = allowedMin, max = allowedMax), FieldSchema.Number("max", "Maximum", min = allowedMin, max = allowedMax))) { feature, _ ->
            val value = query() ?: return@pair false
            matchesBoundedNumber(value, feature.config["min"].numberOrNull(), feature.config["max"].numberOrNull(), allowedMin, allowedMax)
        }

    private fun pair(registry: FeatureRegistry, key: String, title: String, description: String, category: FeatureCategory, fields: List<FieldSchema>, evaluate: suspend (com.yagay.yauto.core.model.FeatureRef, FeatureExecutionContext) -> Boolean) {
        val evaluator = ConditionEvaluator { feature, ctx -> runCatching { evaluate(feature, ctx) }.getOrDefault(false) }
        val state = FeatureDescriptor(FeatureId("android.state.$key"), FeatureKind.STATE, title, description, category, fields = fields, ownerPackId = id)
        registry.registerState(state, evaluator)
        registry.registerCondition(state.copy(id = FeatureId("android.condition.$key"), kind = FeatureKind.CONDITION), evaluator)
    }
}

internal fun matchesBoundedNumber(value: Double, min: Double?, max: Double?, allowedMin: Double, allowedMax: Double): Boolean {
    if (!value.isFinite() || value !in allowedMin..allowedMax) return false
    val safeMin = min ?: allowedMin
    val safeMax = max ?: allowedMax
    if (!safeMin.isFinite() || !safeMax.isFinite() || safeMin !in allowedMin..allowedMax || safeMax !in allowedMin..allowedMax || safeMax < safeMin) return false
    return value in safeMin..safeMax
}
