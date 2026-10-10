package com.yagay.yauto.platform.android

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.role.RoleManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.Context
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

class AndroidFinalParityFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.final_parity"
    internal val context = context.applicationContext
    internal val notifications = this.context.getSystemService(NotificationManager::class.java)
    internal val roles = this.context.getSystemService(RoleManager::class.java)

    override fun install(registry: FeatureRegistry) {
        registerNotificationChannels(registry)
        registerRoleManagement(registry)
        registerTelephonyShell(registry)
        registerTaskerPluginBridge(registry)
        registerWidgetBridge(registry)
        registerFinalUtilities(registry)
    }

    internal fun registerRoleManagement(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.role.request"), FeatureKind.ACTION,
                "Request default-app role",
                "Open Android's role confirmation UI for SMS, dialer, browser, home, assistant or call screening",
                FeatureCategory.APP,
                fields = listOf(FieldSchema.Choice("role", "Role", true, ROLE_OPTIONS)),
                keywords = setOf("default app", "role", "sms", "dialer", "browser", "home", "assistant"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            val role = roleName(feature.config.string("role")) ?: return@registerAction ActionExecutionResult(false)
            if (!roles.isRoleAvailable(role)) return@registerAction ActionExecutionResult(false)
            runCatching {
                context.startActivity(roles.createRequestRoleIntent(role).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionExecutionResult(true)
            }.getOrElse { failure(it) }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.role.holder.manage"), FeatureKind.ACTION,
                "Manage role holder",
                "Add, remove or clear an Android role holder through Root or Shizuku",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Choice("operation", "Operation", true, listOf("add", "remove", "clear")),
                    FieldSchema.Choice("role", "Role", true, ROLE_OPTIONS),
                    FieldSchema.AppPicker("package", "App / package"),
                    FieldSchema.Number("userId", "Android user ID", min = 0.0),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("role", "default app", "root", "shizuku", "sms", "dialer", "browser"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val role = roleName(feature.config.string("role")) ?: return@registerAction ActionExecutionResult(false)
            val op = feature.config.string("operation", "add")
            val pkg = feature.config.string("package").resolveVariables(ctx.variables).trim()
            val user = (feature.config["userId"].numberOrNull() ?: 0.0).toInt().coerceAtLeast(0)
            val command = when (op) {
                "clear" -> "cmd role clear-role-holders --user " + user + " " + shellArg(role)
                "remove" -> {
                    if (!PACKAGE.matches(pkg)) return@registerAction ActionExecutionResult(false)
                    "cmd role remove-role-holder --user " + user + " " + shellArg(role) + " " + shellArg(pkg)
                }
                else -> {
                    if (!PACKAGE.matches(pkg)) return@registerAction ActionExecutionResult(false)
                    "cmd role add-role-holder --user " + user + " " + shellArg(role) + " " + shellArg(pkg)
                }
            }
            shell(ctx, command)
        }
    }

    internal fun registerTelephonyShell(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.telephony.allowed_network_types.query"), FeatureKind.ACTION,
                "Query allowed mobile network types",
                "Query Android's user-selected mobile network-type bitmask for a SIM slot",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Number("slotId", "SIM slot ID", min = 0.0, max = 7.0),
                    FieldSchema.Variable("resultVariable", "Store shell result"),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("network type", "5g", "lte", "nr", "sim", "root", "shizuku"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val slot = (feature.config["slotId"].numberOrNull() ?: 0.0).toInt().coerceIn(0, 7)
            val result = ctx.capabilities.execute(shellRequest("cmd phone get-allowed-network-types-for-users -s " + slot))
            val resultName = feature.config.string("resultVariable").trim()
            if (resultName.isNotBlank() && result.value != null) ctx.variables.set(resultName, result.value!!)
            ActionExecutionResult(result.success, result.value, result.message)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.telephony.allowed_network_types.set"), FeatureKind.ACTION,
                "Set allowed mobile network types",
                "Set Android's user-selected network-type bitmask for a SIM slot using the phone shell service",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Number("slotId", "SIM slot ID", min = 0.0, max = 7.0),
                    FieldSchema.Text("bitmask", "Binary network-type bitmask", true),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("network type", "5g", "lte", "nr", "preferred network", "root", "shizuku"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val slot = (feature.config["slotId"].numberOrNull() ?: 0.0).toInt().coerceIn(0, 7)
            val bitmask = feature.config.string("bitmask").trim()
            if (!bitmask.matches(Regex("[01]{1,64}"))) return@registerAction ActionExecutionResult(false)
            shell(ctx, "cmd phone set-allowed-network-types-for-users -s " + slot + " " + bitmask)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.telephony.ims.set"), FeatureKind.ACTION,
                "Set IMS enabled",
                "Enable or disable IMS for a SIM slot through Android's phone shell service",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Number("slotId", "SIM slot ID", min = 0.0, max = 7.0),
                    FieldSchema.Toggle("enabled", "IMS enabled"),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("ims", "volte", "vowifi", "sim", "root", "shizuku"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val slot = (feature.config["slotId"].numberOrNull() ?: 0.0).toInt().coerceIn(0, 7)
            val command = "cmd phone ims " + (if (feature.config.boolean("enabled", true)) "enable" else "disable") + " -s " + slot
            shell(ctx, command)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.sms.default_app.query"), FeatureKind.ACTION,
                "Query default SMS app",
                "Return Android's current default SMS application through the phone shell service",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Number("userId", "Android user ID", min = 0.0),
                    FieldSchema.Variable("resultVariable", "Store shell result"),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("sms", "default app", "messaging", "root", "shizuku"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val user = (feature.config["userId"].numberOrNull() ?: 0.0).toInt().coerceAtLeast(0)
            val result = ctx.capabilities.execute(shellRequest("cmd phone sms get-default-app --user " + user))
            val resultName = feature.config.string("resultVariable").trim()
            if (resultName.isNotBlank() && result.value != null) ctx.variables.set(resultName, result.value!!)
            ActionExecutionResult(result.success, result.value, result.message)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.sms.default_app.set"), FeatureKind.ACTION,
                "Set default SMS app",
                "Set Android's default SMS application through Root or Shizuku",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.AppPicker("package", "App / package", true),
                    FieldSchema.Number("userId", "Android user ID", min = 0.0),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("sms", "default app", "messaging", "root", "shizuku"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val pkg = feature.config.string("package").resolveVariables(ctx.variables).trim()
            if (!PACKAGE.matches(pkg)) return@registerAction ActionExecutionResult(false)
            val user = (feature.config["userId"].numberOrNull() ?: 0.0).toInt().coerceAtLeast(0)
            shell(ctx, "cmd phone sms set-default-app --user " + user + " " + shellArg(pkg))
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.telephony.network_mode.set"), FeatureKind.ACTION,
                "Set mobile network mode",
                "Set a common Android allowed-network-types preset for a SIM slot",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Number("slotId", "SIM slot ID", min = 0.0, max = 7.0),
                    FieldSchema.Choice("mode", "Network mode", true, listOf("nr_only", "nr_lte", "all", "lte_legacy", "lte_only", "custom")),
                    FieldSchema.Text("customBitmask", "Custom binary bitmask"),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("5g", "4g", "lte", "nr", "preferred network", "network mode", "sim"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val slot = (feature.config["slotId"].numberOrNull() ?: 0.0).toInt().coerceIn(0, 7)
            val bitmask = when (feature.config.string("mode", "nr_lte")) {
                "nr_only" -> "10000000000000000000"
                "nr_lte" -> "11000001000000000000"
                "all" -> "11001111101111111111"
                "lte_legacy" -> "01001111101111111111"
                "lte_only" -> "01000001000000000000"
                "custom" -> feature.config.string("customBitmask").trim()
                else -> return@registerAction ActionExecutionResult(false)
            }
            if (!bitmask.matches(Regex("[01]{1,64}"))) return@registerAction ActionExecutionResult(false)
            shell(ctx, "cmd phone set-allowed-network-types-for-users -s " + slot + " " + bitmask)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.telephony.physical_subscription.set"), FeatureKind.ACTION,
                "Enable or disable physical SIM",
                "Enable or disable a physical subscription by subscription ID. Android restricts this shell command to Root.",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Number("subscriptionId", "Subscription ID", true, min = 0.0),
                    FieldSchema.Toggle("enabled", "SIM enabled"),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = listOf(FeatureImplementationOption("root", setOf(AccessRequirement.ROOT))),
                keywords = setOf("sim", "physical sim", "subscription", "enable sim", "root"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val subId = feature.config["subscriptionId"].numberOrNull()?.toInt()
                ?: return@registerAction ActionExecutionResult(false)
            val verb = if (feature.config.boolean("enabled", true)) "enable-physical-subscription" else "disable-physical-subscription"
            shell(ctx, "cmd phone " + verb + " " + subId)
        }
    }

    internal fun parseExtras(raw: String): List<Pair<String, String>> =
        raw.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .mapNotNull { line ->
                val split = line.split('=', limit = 2)
                if (split.size != 2) null
                else split[0].trim().takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,128}")) }
                    ?.let { it to split[1] }
            }
            .take(100)
            .toList()

    internal fun roleName(key: String): String? = when (key) {
        "sms" -> RoleManager.ROLE_SMS
        "dialer" -> RoleManager.ROLE_DIALER
        "browser" -> RoleManager.ROLE_BROWSER
        "home" -> RoleManager.ROLE_HOME
        "assistant" -> RoleManager.ROLE_ASSISTANT
        "call_screening" -> RoleManager.ROLE_CALL_SCREENING
        else -> null
    }

    internal suspend fun shell(ctx: FeatureExecutionContext, command: String): ActionExecutionResult {
        val result = ctx.capabilities.execute(shellRequest(command))
        return ActionExecutionResult(result.success, result.value, result.message)
    }

    internal fun shellRequest(command: String) = CapabilityRequest(
        capability = CapabilityIds.PRIVILEGED_SHELL,
        operationId = "system.shell.execute",
        payload = mapOf("command" to ConfigValue.StringValue(command)),
    )

    internal fun privilegedOptions() = listOf(
        FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
        FeatureImplementationOption("shizuku", setOf(AccessRequirement.SHIZUKU)),
    )

    internal fun store(feature: com.yagay.yauto.core.model.FeatureRef, ctx: FeatureExecutionContext, value: ConfigValue): ActionExecutionResult {
        val name = feature.config.string("resultVariable").trim()
        if (name.isBlank()) return ActionExecutionResult(false, message = userText("feature.destination_variable_empty"))
        ctx.variables.set(name, value)
        return ActionExecutionResult(true, value)
    }

    internal fun failure(error: Throwable) =
        ActionExecutionResult(false, message = userText("feature.operation_failed", error.message ?: error.javaClass.simpleName))

    internal fun safeId(value: String): Boolean = value.matches(Regex("[A-Za-z0-9._-]{1,100}"))

    internal fun shellArg(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        private val PACKAGE = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        private val ROLE_OPTIONS = listOf("sms", "dialer", "browser", "home", "assistant", "call_screening")
    }
}
