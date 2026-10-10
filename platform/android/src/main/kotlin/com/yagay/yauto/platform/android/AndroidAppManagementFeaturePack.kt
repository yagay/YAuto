package com.yagay.yauto.platform.android

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
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
        registerSuspend(registry)
        registerUninstallForUser(registry)
        registerInstallExisting(registry)
        registerPermission(registry)
        registerStandbyBucket(registry)
        registerInactive(registry)
        registerOpenInfo(registry)
        registerPackageInfo(registry)
        registerEnabledState(registry)
        registerProcessRunning(registry)
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
                    FieldSchema.Number("userId", "Android user ID (optional)", min = 0.0, max = 99.0),
                    FieldSchema.Toggle("enabled", "Enabled"),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                keywords = setOf("enable", "disable", "freeze", "package", "root", "shizuku"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val packageName = resolvePackage(feature.config.string("package"), ctx)
                ?: return@registerAction invalidPackage()
            val enabled = feature.config.boolean("enabled", true)
            if (!enabled && packageName == selfPackage) return@registerAction selfBlocked()
            val user = appUserArgument(feature.config) ?: return@registerAction ActionExecutionResult(false)
            executeShell("android.app.enabled.set", appEnabledCommand(packageName, enabled, user), ctx)
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
            val packageName = resolvePackage(feature.config.string("package"), ctx) ?: return@registerAction invalidPackage()
            if (packageName == selfPackage) return@registerAction selfBlocked()
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
            val packageName = resolvePackage(feature.config.string("package"), ctx) ?: return@registerAction invalidPackage()
            if (packageName == selfPackage) return@registerAction selfBlocked()
            executeShell("android.app.background.kill", "am kill --user current $packageName", ctx)
        }
    }

    private fun registerSuspend(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.app.suspended.set"), FeatureKind.ACTION,
                "Suspend or unsuspend app", "Suspend an installed package for the current user, or make it usable again",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.AppPicker("package", "App / package", true),
                    FieldSchema.Number("userId", "Android user ID (optional)", min = 0.0, max = 99.0),
                    FieldSchema.Toggle("suspended", "Suspended"),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                keywords = setOf("suspend", "unsuspend", "freeze", "package"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val packageName = resolvePackage(feature.config.string("package"), ctx) ?: return@registerAction invalidPackage()
            val suspended = feature.config.boolean("suspended", true)
            if (suspended && packageName == selfPackage) return@registerAction selfBlocked()
            val user = appUserArgument(feature.config) ?: return@registerAction ActionExecutionResult(false)
            executeShell("android.app.suspended.set", appSuspendedCommand(packageName, suspended, user), ctx)
        }
    }

    private fun registerUninstallForUser(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.app.uninstall_user"), FeatureKind.ACTION,
                "Uninstall app for current user", "Remove an installed package for the current Android user, optionally keeping its data",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.AppPicker("package", "App / package", true),
                    FieldSchema.Toggle("keepData", "Keep app data"),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                keywords = setOf("uninstall", "remove app", "current user", "package"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val packageName = resolvePackage(feature.config.string("package"), ctx) ?: return@registerAction invalidPackage()
            if (packageName == selfPackage) return@registerAction selfBlocked()
            executeShell("android.app.uninstall_user", uninstallForUserCommand(packageName, feature.config.boolean("keepData")), ctx)
        }
    }

    private fun registerInstallExisting(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.app.install_existing"), FeatureKind.ACTION,
                "Restore existing app for current user", "Re-enable a package that still exists on the device but was removed for the current user",
                FeatureCategory.APP,
                fields = listOf(FieldSchema.Text("package", "Package name", true)),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                keywords = setOf("install existing", "restore app", "package", "current user"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val packageName = resolvePackage(feature.config.string("package"), ctx) ?: return@registerAction invalidPackage()
            executeShell("android.app.install_existing", "cmd package install-existing --user current $packageName", ctx)
        }
    }

    private fun registerPermission(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.app.permission.set"), FeatureKind.ACTION,
                "Grant or revoke app permission", "Grant or revoke a runtime permission for an installed package through Root or Shizuku",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.AppPicker("package", "App / package", true),
                    FieldSchema.Text("permission", "Permission name", true),
                    FieldSchema.Toggle("granted", "Granted"),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                keywords = setOf("permission", "grant", "revoke", "package"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val packageName = resolvePackage(feature.config.string("package"), ctx) ?: return@registerAction invalidPackage()
            val permission = feature.config.string("permission").resolveVariables(ctx.variables).trim()
            if (!isValidPermissionName(permission)) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.invalid_permission_name"))
            }
            val granted = feature.config.boolean("granted", true)
            if (!granted && packageName == selfPackage) return@registerAction selfBlocked()
            executeShell("android.app.permission.set", permissionCommand(packageName, permission, granted), ctx)
        }
    }

    private fun registerStandbyBucket(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.app.standby_bucket.set"), FeatureKind.ACTION,
                "Set app standby bucket", "Place an application in an Android app standby bucket for the current user",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.AppPicker("package", "App / package", true),
                    FieldSchema.Choice("bucket", "Standby bucket", true, listOf("active", "working_set", "frequent", "rare", "restricted")),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                keywords = setOf("standby", "bucket", "battery", "background", "app"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val packageName = resolvePackage(feature.config.string("package"), ctx) ?: return@registerAction invalidPackage()
            val command = standbyBucketCommand(packageName, feature.config.string("bucket", "active"))
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.standby_bucket_invalid"))
            executeShell("android.app.standby_bucket.set", command, ctx)
        }
    }

    private fun registerInactive(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.app.inactive.set"), FeatureKind.ACTION,
                "Set app inactive state", "Mark an application inactive or active for Android app-idle policy",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.AppPicker("package", "App / package", true),
                    FieldSchema.Number("userId", "Android user ID (optional)", min = 0.0, max = 99.0),
                    FieldSchema.Toggle("inactive", "Inactive"),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                keywords = setOf("inactive", "idle", "background", "battery", "app"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val packageName = resolvePackage(feature.config.string("package"), ctx) ?: return@registerAction invalidPackage()
            val user = appUserArgument(feature.config) ?: return@registerAction ActionExecutionResult(false)
            executeShell("android.app.inactive.set", "am set-inactive --user $user $packageName ${feature.config.boolean("inactive", true)}", ctx)
        }
    }

    private fun registerOpenInfo(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.app.details.open"), FeatureKind.ACTION,
                "Open app details", "Open Android application details settings for an installed package",
                FeatureCategory.APP,
                fields = listOf(FieldSchema.AppPicker("package", "App / package", true)),
                keywords = setOf("app info", "details", "settings", "package"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val packageName = resolvePackage(feature.config.string("package"), ctx) ?: return@registerAction invalidPackage()
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                ActionExecutionResult(true)
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }
    }

    @Suppress("DEPRECATION")
    private fun registerPackageInfo(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.app.package_info"), FeatureKind.ACTION,
                "Get app package information", "Read version, install times, UID and package flags into an object variable",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.AppPicker("package", "App / package", true),
                    FieldSchema.Variable("resultVariable", "Store object in variable", true),
                ),
                keywords = setOf("package info", "version", "uid", "install time", "app"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val packageName = resolvePackage(feature.config.string("package"), ctx) ?: return@registerAction invalidPackage()
            runCatching {
                val info = context.packageManager.getPackageInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS)
                val appInfo = info.applicationInfo
                val output = ConfigValue.ObjectValue(
                    mapOf(
                        "package" to ConfigValue.StringValue(packageName),
                        "versionName" to ConfigValue.StringValue(info.versionName.orEmpty()),
                        "versionCode" to ConfigValue.NumberValue(info.longVersionCode.toDouble()),
                        "firstInstallTime" to ConfigValue.NumberValue(info.firstInstallTime.toDouble()),
                        "lastUpdateTime" to ConfigValue.NumberValue(info.lastUpdateTime.toDouble()),
                        "uid" to ConfigValue.NumberValue((appInfo?.uid ?: -1).toDouble()),
                        "enabled" to ConfigValue.BooleanValue(appInfo?.enabled == true),
                        "systemApp" to ConfigValue.BooleanValue(appInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) != 0),
                    )
                )
                ctx.variables.set(feature.config.string("resultVariable"), output)
                ActionExecutionResult(true, output)
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
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

    private fun registerProcessRunning(registry: FeatureRegistry) {
        val fields = listOf(
            FieldSchema.AppPicker("package", "App / package", true),
            FieldSchema.Toggle("value", "Running"),
        )
        for (kind in listOf(FeatureKind.STATE, FeatureKind.CONDITION)) {
            val featureId = if (kind == FeatureKind.STATE) "android.state.app_process_running" else "android.condition.app_process_running"
            val descriptor = FeatureDescriptor(
                FeatureId(featureId), kind,
                "App process running", "Check whether a package currently has a process by querying a privileged shell",
                FeatureCategory.APP,
                fields = fields,
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                keywords = setOf("process", "running", "pid", "app"),
                ownerPackId = id,
            )
            val evaluator = ConditionEvaluator { feature, ctx ->
                val packageName = resolvePackage(feature.config.string("package"), ctx) ?: return@ConditionEvaluator false
                val result = ctx.capabilities.execute(
                    CapabilityRequest(
                        capability = CapabilityIds.PRIVILEGED_SHELL,
                        operationId = "android.app.process.running",
                        payload = mapOf("command" to ConfigValue.StringValue("pidof $packageName")),
                        allowFallback = true,
                    )
                )
                result.success == feature.config.boolean("value", true)
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

    private fun invalidPackage() = ActionExecutionResult(false, message = userText("feature.invalid_package_name"))
    private fun selfBlocked() = ActionExecutionResult(false, message = userText("feature.app_self_management_blocked"))

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

internal fun isValidPackageName(packageName: String): Boolean = PACKAGE_NAME.matches(packageName)
internal fun isValidPermissionName(permission: String): Boolean = PERMISSION_NAME.matches(permission)

internal fun appUserArgument(config: Map<String, ConfigValue>): String? {
    if ("userId" !in config) return "current" // preserve all older YAuto rules
    val value = config["userId"].numberOrNull() ?: return null
    return value.takeIf { it.isFinite() && it in 0.0..99.0 && it == it.toInt().toDouble() }?.toInt()?.toString()
}

internal fun appEnabledCommand(packageName: String, enabled: Boolean, user: String = "current"): String =
    if (enabled) "pm enable --user $user $packageName" else "pm disable-user --user $user $packageName"

internal fun appSuspendedCommand(packageName: String, suspended: Boolean, user: String = "current"): String =
    if (suspended) "pm suspend --user $user $packageName" else "pm unsuspend --user $user $packageName"

internal fun uninstallForUserCommand(packageName: String, keepData: Boolean): String =
    if (keepData) "pm uninstall -k --user current $packageName" else "pm uninstall --user current $packageName"

internal fun permissionCommand(packageName: String, permission: String, granted: Boolean): String =
    if (granted) "pm grant --user current $packageName $permission" else "pm revoke --user current $packageName $permission"

internal fun standbyBucketCommand(packageName: String, bucket: String): String? = when (bucket) {
    "active", "working_set", "frequent", "rare", "restricted" -> "am set-standby-bucket --user current $packageName $bucket"
    else -> null
}

internal fun rebootCommand(mode: String): String? = when (mode) {
    "normal" -> "svc power reboot"
    "recovery" -> "svc power reboot recovery"
    "bootloader" -> "svc power reboot bootloader"
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
private val PERMISSION_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+")
