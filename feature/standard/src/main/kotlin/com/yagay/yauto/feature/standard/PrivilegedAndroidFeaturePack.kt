package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

class PrivilegedAndroidFeaturePack : FeaturePack {
    override val id = "standard.android.privileged"

    override fun install(registry: FeatureRegistry) {
        privilegedAction(registry, "android.app.clear_data", "Clear app data", "Clear an application's user data through Shizuku or Root", FeatureCategory.APP,
            listOf(FieldSchema.AppPicker("package", "App / package", true)), setOf("clear data", "reset app", "pm clear", "清除数据")) { feature, ctx ->
            val pkg = validatedPackage(feature.config.string("package").resolveVariables(ctx.variables))
            "pm clear ${shellQuote(pkg)}"
        }

        privilegedAction(registry, "android.app.enabled.set", "Enable / disable app", "Enable an app or disable it for the primary user", FeatureCategory.APP,
            listOf(FieldSchema.AppPicker("package", "App / package", true), FieldSchema.Toggle("enabled", "Enabled")), setOf("enable app", "disable app", "freeze", "pm disable", "冻结应用")) { feature, ctx ->
            val pkg = validatedPackage(feature.config.string("package").resolveVariables(ctx.variables))
            if (feature.config.boolean("enabled", true)) "pm enable ${shellQuote(pkg)}" else "pm disable-user --user 0 ${shellQuote(pkg)}"
        }

        privilegedAction(registry, "android.component.enabled.set", "Enable / disable component", "Enable or disable an Android activity, service, receiver or provider component", FeatureCategory.APP,
            listOf(FieldSchema.Text("component", "Component (package/class)", true), FieldSchema.Toggle("enabled", "Enabled")), setOf("component", "activity", "service", "receiver", "disable component", "组件")) { feature, ctx ->
            val component = feature.config.string("component").resolveVariables(ctx.variables).trim()
            require(COMPONENT.matches(component)) { "Invalid component name" }
            if (feature.config.boolean("enabled", true)) "pm enable ${shellQuote(component)}" else "pm disable ${shellQuote(component)}"
        }

        privilegedAction(registry, "android.permission.set", "Grant / revoke permission", "Grant or revoke a runtime permission through package manager", FeatureCategory.SYSTEM,
            listOf(FieldSchema.AppPicker("package", "App / package", true), FieldSchema.Text("permission", "Permission", true), FieldSchema.Choice("mode", "Mode", true, listOf("grant", "revoke"))), setOf("permission", "grant", "revoke", "pm grant", "权限")) { feature, ctx ->
            val pkg = validatedPackage(feature.config.string("package").resolveVariables(ctx.variables))
            val permission = feature.config.string("permission").resolveVariables(ctx.variables).trim()
            require(PERMISSION.matches(permission)) { "Invalid permission name" }
            val verb = if (feature.config.string("mode", "grant") == "revoke") "revoke" else "grant"
            "pm $verb ${shellQuote(pkg)} ${shellQuote(permission)}"
        }

        privilegedAction(registry, "android.appops.set", "Set AppOps mode", "Set an AppOps operation mode for an application", FeatureCategory.SYSTEM,
            listOf(FieldSchema.AppPicker("package", "App / package", true), FieldSchema.Text("operation", "AppOps operation", true), FieldSchema.Choice("mode", "Mode", true, listOf("allow", "ignore", "deny", "default", "foreground"))), setOf("appops", "privacy", "permission", "app operation")) { feature, ctx ->
            val pkg = validatedPackage(feature.config.string("package").resolveVariables(ctx.variables))
            val operation = feature.config.string("operation").resolveVariables(ctx.variables).trim()
            require(SAFE_TOKEN.matches(operation)) { "Invalid AppOps operation" }
            val mode = feature.config.string("mode", "default")
            require(mode in setOf("allow", "ignore", "deny", "default", "foreground")) { "Invalid AppOps mode" }
            "appops set ${shellQuote(pkg)} ${shellQuote(operation)} ${shellQuote(mode)}"
        }

        toggleCommand(registry, "android.wifi.set", "Wi-Fi", "Turn Wi-Fi on or off using Android's svc/cmd Wi-Fi service", "svc wifi", setOf("wifi", "wi-fi", "network", "无线网络"))
        toggleCommand(registry, "android.mobile_data.set", "Mobile data", "Turn mobile data connectivity on or off using Android's phone service", "svc data", setOf("mobile data", "cellular", "data", "移动数据"))
        toggleCommand(registry, "android.bluetooth.set", "Bluetooth", "Turn Bluetooth on or off using Android's Bluetooth manager shell command", "svc bluetooth", setOf("bluetooth", "bt", "蓝牙"))

        privilegedAction(registry, "android.airplane_mode.set", "Airplane mode", "Set the global airplane mode flag and broadcast the corresponding Android state change", FeatureCategory.NETWORK,
            listOf(FieldSchema.Toggle("enabled", "Enabled")), setOf("airplane", "flight mode", "飞行模式")) { feature, _ ->
            val enabled = feature.config.boolean("enabled", true)
            val flag = if (enabled) "1" else "0"
            "settings put global airplane_mode_on $flag && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $enabled"
        }

        privilegedAction(registry, "android.settings.put", "Write system setting", "Write an Android system/secure/global setting through a privileged shell backend", FeatureCategory.SYSTEM,
            listOf(FieldSchema.Choice("namespace", "Namespace", true, listOf("system", "secure", "global")), FieldSchema.Text("key", "Setting key", true), FieldSchema.Text("value", "Value", true)), setOf("settings put", "secure settings", "global settings", "系统设置")) { feature, ctx ->
            val namespace = feature.config.string("namespace", "system")
            val key = feature.config.string("key").resolveVariables(ctx.variables).trim()
            val value = feature.config.string("value").resolveVariables(ctx.variables)
            require(namespace in setOf("system", "secure", "global")) { "Invalid settings namespace" }
            require(SAFE_SETTING_KEY.matches(key)) { "Invalid settings key" }
            "settings put $namespace ${shellQuote(key)} ${shellQuote(value)}"
        }

        privilegedAction(registry, "android.settings.delete", "Delete system setting", "Delete an Android system/secure/global setting through a privileged shell backend", FeatureCategory.SYSTEM,
            listOf(FieldSchema.Choice("namespace", "Namespace", true, listOf("system", "secure", "global")), FieldSchema.Text("key", "Setting key", true)), setOf("settings delete", "secure settings", "global settings")) { feature, ctx ->
            val namespace = feature.config.string("namespace", "system")
            val key = feature.config.string("key").resolveVariables(ctx.variables).trim()
            require(namespace in setOf("system", "secure", "global")) { "Invalid settings namespace" }
            require(SAFE_SETTING_KEY.matches(key)) { "Invalid settings key" }
            "settings delete $namespace ${shellQuote(key)}"
        }
    }

    private fun toggleCommand(registry: FeatureRegistry, typeId: String, title: String, description: String, commandPrefix: String, keywords: Set<String>) {
        privilegedAction(registry, typeId, title, description, FeatureCategory.NETWORK,
            listOf(FieldSchema.Toggle("enabled", "Enabled")), keywords) { feature, _ ->
            "$commandPrefix ${if (feature.config.boolean("enabled", true)) "enable" else "disable"}"
        }
    }

    private fun privilegedAction(
        registry: FeatureRegistry,
        typeId: String,
        title: String,
        description: String,
        category: FeatureCategory,
        fields: List<FieldSchema>,
        keywords: Set<String>,
        command: (com.yagay.yauto.core.model.FeatureRef, FeatureExecutionContext) -> String,
    ) {
        registry.registerAction(
            FeatureDescriptor(FeatureId(typeId), FeatureKind.ACTION, title, description, category,
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL), fields = fields, keywords = keywords, ownerPackId = id)
        ) { feature, ctx ->
            val shell = runCatching { command(feature, ctx) }.getOrElse {
                return@registerAction ActionExecutionResult(false, message = it.message ?: it.javaClass.simpleName)
            }
            val result = ctx.executeCapability(featureId = typeId,
                request = CapabilityRequest(
                    CapabilityIds.PRIVILEGED_SHELL,
                    typeId,
                    mapOf("command" to ConfigValue.StringValue(shell)),
                    preferredBackendId = feature.preferredBackendId(),
                ))
            ActionExecutionResult(result.success, result.value, result.message)
        }
    }

    private fun validatedPackage(raw: String): String {
        val value = raw.trim()
        require(PACKAGE_NAME.matches(value)) { "Invalid package name" }
        return value
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        val COMPONENT = Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")
        val PERMISSION = Regex("[A-Za-z0-9_.]+")
        val SAFE_TOKEN = Regex("[A-Za-z0-9_.:-]+")
        val SAFE_SETTING_KEY = Regex("[A-Za-z0-9_.:-]+")
    }
}
