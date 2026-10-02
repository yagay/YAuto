package com.yagay.yauto.platform.android

import android.content.Context
import android.content.pm.PackageManager
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

/** Privileged package/device management kept isolated from normal Android API features. */
class AndroidAppManagementFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.app_management"
    private val context = context.applicationContext
    private val selfPackage = context.packageName

    override fun install(registry: FeatureRegistry) {
        registerEnabled(registry)
        registerClearData(registry)
        registerKillBackground(registry)
        registerEnabledState(registry)
        registerReboot(registry)
    }

    private fun registerEnabled(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.app.enabled.set"), FeatureKind.ACTION,
                "Enable or disable app", "Enable or disable an installed package through Root or Shizuku",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.AppPicker("package", "App / package", true),
                    FieldSchema.Toggle("enabled", "Enabled"),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                keywords = setOf("enable", "disable", "freeze", "package", "root", "shizuku"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val packageName = resolvePackage(feature.config.string("package"), ctx)
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.invalid_package_name"))
            val enabled = feature.config.boolean("enabled", true)
            if (!enabled && packageName == selfPackage) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.app_self_management_blocked"))
            }
            executeShell("android.app.enabled.set", appEnabledCommand(packageName, enabled), ctx)
        }
    }

    private fun registerClearData(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.app.data.clear"), FeatureKind.ACTION,
                "Clear app data", "Clear all user data for an installed package through Root or Shizuku",
                FeatureCategory.APP,
                fields = listOf(FieldSchema.AppPicker("package", "App / package", true)),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                keywords = setOf("clear data", "reset app", "package", "root", "shizuku"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val packageName = resolvePackage(feature.config.string("package"), ctx)
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.invalid_package_name"))
            if (packageName == selfPackage) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.app_self_management_blocked"))
            }
            executeShell("android.app.data.clear", "pm clear --user current $packageName", ctx)
        }
    }

    private fun registerKillBackground(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.app.background.kill"), FeatureKind.ACTION,
                "Kill app background processes", "Ask Android to kill background processes for a package without force-stopping it",
                FeatureCategory.APP,
                fields = listOf(FieldSchema.AppPicker("package", "App / package", true)),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                keywords = setOf("kill background", "background process", "package", "root", "shizuku"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val packageName = resolvePackage(feature.config.string("package"), ctx)
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.invalid_package_name"))
            if (packageName == selfPackage) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.app_self_management_blocked"))
            }
            executeShell("android.app.background.kill", "am kill --user current $packageName", ctx)
        }
    }

    private fun registerEnabledState(registry: FeatureRegistry) {
        val fields = listOf(
            FieldSchema.AppPicker("package", "App / package", true),
            FieldSchema.Toggle("value", "Enabled"),
        )
        for (kind in listOf(FeatureKind.STATE, FeatureKind.CONDITION)) {
            val featureId = if (kind == FeatureKind.STATE) "android.state.app_enabled" else "android.condition.app_enabled"
            val descriptor = FeatureDescriptor(
                FeatureId(featureId), kind,
                "App enabled", "Check whether an installed package is enabled for the current user",
                FeatureCategory.APP,
                fields = fields,
                keywords = setOf("app enabled", "disabled", "package", "freeze"),
                ownerPackId = id,
            )
            val evaluator = ConditionEvaluator { feature, ctx ->
                val packageName = resolvePackage(feature.config.string("package"), ctx) ?: return@ConditionEvaluator false
                isPackageEnabled(context.packageManager, packageName) == feature.config.boolean("value", true)
            }
            if (kind == FeatureKind.STATE) registry.registerState(descriptor, evaluator) else registry.registerCondition(descriptor, evaluator)
        }
    }

    private fun registerReboot(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.device.reboot"), FeatureKind.ACTION,
                "Reboot device", "Reboot Android normally, to recovery, or to the bootloader through a privileged shell",
                FeatureCategory.SYSTEM,
                fields = listOf(FieldSchema.Choice("mode", "Reboot mode", true, listOf("normal", "recovery", "bootloader"))),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                keywords = setOf("reboot", "restart", "recovery", "bootloader", "root", "shizuku"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val command = rebootCommand(feature.config.string("mode", "normal"))
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.reboot_mode_invalid"))
            executeShell("android.device.reboot", command, ctx)
        }
    }

    private fun resolvePackage(raw: String, ctx: FeatureExecutionContext): String? =
        raw.resolveVariables(ctx.variables).trim().takeIf(::isValidPackageName)

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

internal fun isValidPackageName(packageName: String): Boolean =
    PACKAGE_NAME.matches(packageName)

internal fun appEnabledCommand(packageName: String, enabled: Boolean): String =
    if (enabled) "pm enable --user current $packageName" else "pm disable-user --user current $packageName"

internal fun rebootCommand(mode: String): String? = when (mode) {
    "normal" -> "reboot"
    "recovery" -> "reboot recovery"
    "bootloader" -> "reboot bootloader"
    else -> null
}

@Suppress("DEPRECATION")
internal fun isPackageEnabled(packageManager: PackageManager, packageName: String): Boolean = runCatching {
    when (packageManager.getApplicationEnabledSetting(packageName)) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
        else -> packageManager.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS).enabled
    }
}.getOrDefault(false)

private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
