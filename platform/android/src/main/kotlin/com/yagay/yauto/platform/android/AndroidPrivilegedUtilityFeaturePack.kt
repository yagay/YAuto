package com.yagay.yauto.platform.android

import android.content.Context
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.stringOrNull
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

/** Structured privileged operations for common tasks that otherwise require raw shell commands. */
class AndroidPrivilegedUtilityFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.privileged.utility"
    private val selfPackage = context.applicationContext.packageName

    override fun install(registry: FeatureRegistry) {
        registerSettingGet(registry)
        registerSettingPut(registry)
        registerSettingDelete(registry)
        registerPropertyGet(registry)
        registerAppOpGet(registry)
        registerAppOpSet(registry)
        registerComponentEnabled(registry)
    }

    private fun registerSettingGet(registry: FeatureRegistry) {
        registry.registerAction(
            descriptor(
                "android.settings.value.get", "Get Android setting",
                "Read a System, Global or Secure settings value through a privileged shell",
                FeatureCategory.SYSTEM,
                listOf(
                    FieldSchema.Choice("namespace", "Settings namespace", true, listOf("system", "global", "secure")),
                    FieldSchema.Text("key", "Setting key", true),
                    FieldSchema.Variable("resultVariable", "Store value in variable", true),
                ),
                setOf("settings", "secure", "global", "system", "read"),
            )
        ) { feature, ctx ->
            val command = settingsGetCommand(feature.config.string("namespace"), feature.config.string("key"))
                ?: return@registerAction invalidStructuredInput()
            val result = executeShell("android.settings.value.get", command, ctx)
            if (!result.success) return@registerAction ActionExecutionResult(false, result.value, result.message)
            val text = shellStdout(result.value).trimEnd('\r', '\n')
            val output = ConfigValue.StringValue(text)
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerSettingPut(registry: FeatureRegistry) {
        registry.registerAction(
            descriptor(
                "android.settings.value.put", "Write Android setting",
                "Write a System, Global or Secure settings value through a privileged shell",
                FeatureCategory.SYSTEM,
                listOf(
                    FieldSchema.Choice("namespace", "Settings namespace", true, listOf("system", "global", "secure")),
                    FieldSchema.Text("key", "Setting key", true),
                    FieldSchema.Text("value", "Setting value", true),
                ),
                setOf("settings", "secure", "global", "system", "write"),
            )
        ) { feature, ctx ->
            val value = feature.config.string("value").resolveVariables(ctx.variables)
            val command = settingsPutCommand(feature.config.string("namespace"), feature.config.string("key"), value)
                ?: return@registerAction invalidStructuredInput()
            shellAction("android.settings.value.put", command, ctx)
        }
    }

    private fun registerSettingDelete(registry: FeatureRegistry) {
        registry.registerAction(
            descriptor(
                "android.settings.value.delete", "Delete Android setting",
                "Delete a System, Global or Secure settings key through a privileged shell",
                FeatureCategory.SYSTEM,
                listOf(
                    FieldSchema.Choice("namespace", "Settings namespace", true, listOf("system", "global", "secure")),
                    FieldSchema.Text("key", "Setting key", true),
                ),
                setOf("settings", "secure", "global", "system", "delete", "reset"),
            )
        ) { feature, ctx ->
            val command = settingsDeleteCommand(feature.config.string("namespace"), feature.config.string("key"))
                ?: return@registerAction invalidStructuredInput()
            shellAction("android.settings.value.delete", command, ctx)
        }
    }

    private fun registerPropertyGet(registry: FeatureRegistry) {
        registry.registerAction(
            descriptor(
                "android.system.property.get", "Get Android system property",
                "Read one Android system property with getprop and store the text in a variable",
                FeatureCategory.SYSTEM,
                listOf(
                    FieldSchema.Text("key", "Property key", true),
                    FieldSchema.Variable("resultVariable", "Store value in variable", true),
                ),
                setOf("getprop", "property", "system property", "build"),
            )
        ) { feature, ctx ->
            val key = feature.config.string("key").trim()
            if (!isValidPropertyName(key)) return@registerAction invalidStructuredInput()
            val result = executeShell("android.system.property.get", "getprop $key", ctx)
            if (!result.success) return@registerAction ActionExecutionResult(false, result.value, result.message)
            val output = ConfigValue.StringValue(shellStdout(result.value).trimEnd('\r', '\n'))
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerAppOpGet(registry: FeatureRegistry) {
        registry.registerAction(
            descriptor(
                "android.app.appop.get", "Get app operation mode",
                "Read Android AppOps state for an application and operation name",
                FeatureCategory.APP,
                listOf(
                    FieldSchema.AppPicker("package", "App / package", true),
                    FieldSchema.Text("operation", "App operation", true),
                    FieldSchema.Variable("resultVariable", "Store command output", true),
                ),
                setOf("appops", "permission", "operation", "mode", "package"),
            )
        ) { feature, ctx ->
            val pkg = feature.config.string("package").resolveVariables(ctx.variables).trim()
            val operation = feature.config.string("operation").trim()
            val command = appOpGetCommand(pkg, operation) ?: return@registerAction invalidStructuredInput()
            val result = executeShell("android.app.appop.get", command, ctx)
            if (!result.success) return@registerAction ActionExecutionResult(false, result.value, result.message)
            val output = ConfigValue.StringValue(shellStdout(result.value).trim())
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerAppOpSet(registry: FeatureRegistry) {
        registry.registerAction(
            descriptor(
                "android.app.appop.set", "Set app operation mode",
                "Set an Android AppOps operation to allow, ignore, deny, default or foreground",
                FeatureCategory.APP,
                listOf(
                    FieldSchema.AppPicker("package", "App / package", true),
                    FieldSchema.Text("operation", "App operation", true),
                    FieldSchema.Choice("mode", "AppOps mode", true, listOf("allow", "ignore", "deny", "default", "foreground")),
                ),
                setOf("appops", "permission", "operation", "allow", "deny"),
            )
        ) { feature, ctx ->
            val pkg = feature.config.string("package").resolveVariables(ctx.variables).trim()
            val command = appOpSetCommand(pkg, feature.config.string("operation"), feature.config.string("mode"))
                ?: return@registerAction invalidStructuredInput()
            shellAction("android.app.appop.set", command, ctx)
        }
    }

    private fun registerComponentEnabled(registry: FeatureRegistry) {
        registry.registerAction(
            descriptor(
                "android.app.component_enabled.set", "Enable or disable app component",
                "Enable or disable one Android component by package and class name",
                FeatureCategory.APP,
                listOf(
                    FieldSchema.AppPicker("package", "App / package", true),
                    FieldSchema.Text("class", "Component class", true),
                    FieldSchema.Toggle("enabled", "Enabled"),
                ),
                setOf("component", "activity", "service", "receiver", "enable", "disable"),
            )
        ) { feature, ctx ->
            val pkg = feature.config.string("package").resolveVariables(ctx.variables).trim()
            val className = feature.config.string("class").resolveVariables(ctx.variables).trim()
            val enabled = feature.config.boolean("enabled", true)
            if (!enabled && pkg == selfPackage) return@registerAction ActionExecutionResult(false, message = userText("feature.app_self_management_blocked"))
            val command = componentEnabledCommand(pkg, className, enabled) ?: return@registerAction invalidStructuredInput()
            shellAction("android.app.component_enabled.set", command, ctx)
        }
    }

    private fun descriptor(
        typeId: String,
        title: String,
        description: String,
        category: FeatureCategory,
        fields: List<FieldSchema>,
        keywords: Set<String>,
    ) = FeatureDescriptor(
        FeatureId(typeId), FeatureKind.ACTION, title, description, category,
        fields = fields,
        capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
        keywords = keywords,
        ownerPackId = id,
    )

    private fun invalidStructuredInput() = ActionExecutionResult(false, message = userText("feature.privileged_input_invalid"))

    private suspend fun shellAction(operationId: String, command: String, ctx: FeatureExecutionContext): ActionExecutionResult {
        val result = executeShell(operationId, command, ctx)
        return ActionExecutionResult(result.success, result.value, result.message)
    }

    private suspend fun executeShell(operationId: String, command: String, ctx: FeatureExecutionContext) =
        ctx.capabilities.execute(
            CapabilityRequest(
                capability = CapabilityIds.PRIVILEGED_SHELL,
                operationId = operationId,
                payload = mapOf("command" to ConfigValue.StringValue(command)),
            )
        )
}

internal fun settingsGetCommand(namespace: String, key: String): String? =
    if (namespace in SETTINGS_NAMESPACES && isValidSettingKey(key)) "settings get $namespace $key" else null

internal fun settingsPutCommand(namespace: String, key: String, value: String): String? =
    if (namespace in SETTINGS_NAMESPACES && isValidSettingKey(key)) "settings put $namespace $key ${shellQuote(value)}" else null

internal fun settingsDeleteCommand(namespace: String, key: String): String? =
    if (namespace in SETTINGS_NAMESPACES && isValidSettingKey(key)) "settings delete $namespace $key" else null

internal fun appOpGetCommand(packageName: String, operation: String): String? =
    if (isValidPackageName(packageName) && isValidAppOp(operation)) "cmd appops get --user current $packageName $operation" else null

internal fun appOpSetCommand(packageName: String, operation: String, mode: String): String? =
    if (isValidPackageName(packageName) && isValidAppOp(operation) && mode in APP_OP_MODES)
        "cmd appops set --user current $packageName $operation $mode" else null

internal fun componentEnabledCommand(packageName: String, className: String, enabled: Boolean): String? {
    if (!isValidPackageName(packageName) || !isValidComponentClass(className)) return null
    val normalized = when {
        className.startsWith(".") -> packageName + className
        '.' in className -> className
        else -> "$packageName.$className"
    }
    val command = if (enabled) "enable" else "disable-user"
    return "pm $command --user current $packageName/$normalized"
}

internal fun isValidSettingKey(key: String): Boolean = SETTING_KEY.matches(key)
internal fun isValidPropertyName(key: String): Boolean = PROPERTY_NAME.matches(key)
internal fun isValidAppOp(operation: String): Boolean = APP_OP_NAME.matches(operation)
internal fun isValidComponentClass(value: String): Boolean = COMPONENT_CLASS.matches(value)

internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

internal fun shellStdout(value: ConfigValue): String =
    ((value as? ConfigValue.ObjectValue)?.value?.get("stdout")).stringOrNull().orEmpty()

private val SETTINGS_NAMESPACES = setOf("system", "global", "secure")
private val APP_OP_MODES = setOf("allow", "ignore", "deny", "default", "foreground")
private val SETTING_KEY = Regex("[A-Za-z0-9_.:-]{1,128}")
private val PROPERTY_NAME = Regex("[A-Za-z0-9_.-]{1,128}")
private val APP_OP_NAME = Regex("[A-Za-z0-9_]{1,128}")
private val COMPONENT_CLASS = Regex("\\.?[A-Za-z_][A-Za-z0-9_$]*(?:\\.[A-Za-z_][A-Za-z0-9_$]*)+")
