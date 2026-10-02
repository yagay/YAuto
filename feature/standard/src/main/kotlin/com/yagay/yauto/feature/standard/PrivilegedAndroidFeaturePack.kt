package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*
import com.yagay.yauto.core.model.userText

class PrivilegedAndroidFeaturePack : FeaturePack {
    override val id = "standard.android.privileged"

    override fun install(registry: FeatureRegistry) {
        privilegedAction(registry, "android.app.clear_data", "Clear app data", "Clear an application's user data through Shizuku or Root", FeatureCategory.APP,
            listOf(FieldSchema.AppPicker("package", "App / package", true)), setOf("clear data", "reset app", "pm clear")) { feature, ctx ->
            val pkg = validatedPackage(feature.config.string("package").resolveVariables(ctx.variables))
            "pm clear ${shellQuote(pkg)}"
        }

        privilegedAction(registry, "android.app.enabled.set", "Enable / disable app", "Enable an app or disable it for the primary user", FeatureCategory.APP,
            listOf(FieldSchema.AppPicker("package", "App / package", true), FieldSchema.Toggle("enabled", "Enabled")), setOf("enable app", "disable app", "freeze", "pm disable")) { feature, ctx ->
            val pkg = validatedPackage(feature.config.string("package").resolveVariables(ctx.variables))
            if (feature.config.boolean("enabled", true)) "pm enable ${shellQuote(pkg)}" else "pm disable-user --user 0 ${shellQuote(pkg)}"
        }

        privilegedAction(registry, "android.component.enabled.set", "Enable / disable component", "Enable or disable an Android activity, service, receiver or provider component", FeatureCategory.APP,
            listOf(FieldSchema.Text("component", "Component (package/class)", true), FieldSchema.Toggle("enabled", "Enabled")), setOf("component", "activity", "service", "receiver", "disable component")) { feature, ctx ->
            val component = feature.config.string("component").resolveVariables(ctx.variables).trim()
            require(COMPONENT.matches(component)) { "Invalid component name" }
            if (feature.config.boolean("enabled", true)) "pm enable ${shellQuote(component)}" else "pm disable ${shellQuote(component)}"
        }

        privilegedAction(registry, "android.permission.set", "Grant / revoke permission", "Grant or revoke a runtime permission through package manager", FeatureCategory.SYSTEM,
            listOf(FieldSchema.AppPicker("package", "App / package", true), FieldSchema.Text("permission", "Permission", true), FieldSchema.Choice("mode", "Mode", true, listOf("grant", "revoke"))), setOf("permission", "grant", "revoke", "pm grant")) { feature, ctx ->
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

        toggleCommand(registry, "android.wifi.set", "Wi-Fi", "Turn Wi-Fi on or off using Android's svc/cmd Wi-Fi service", "svc wifi", setOf("wifi", "wi-fi", "network"))
        toggleCommand(registry, "android.mobile_data.set", "Mobile data", "Turn mobile data connectivity on or off using Android's phone service", "svc data", setOf("mobile data", "cellular", "data"))
        toggleCommand(registry, "android.bluetooth.set", "Bluetooth", "Turn Bluetooth on or off using Android's Bluetooth manager shell command", "svc bluetooth", setOf("bluetooth", "bt"))
        toggleCommand(registry, "android.nfc.set", "NFC", "Turn NFC on or off using Android's NFC service", "svc nfc", setOf("nfc", "near field communication"))

        privilegedAction(registry, "android.airplane_mode.set", "Airplane mode", "Set the global airplane mode flag and broadcast the corresponding Android state change", FeatureCategory.NETWORK,
            listOf(FieldSchema.Toggle("enabled", "Enabled")), setOf("airplane", "flight mode")) { feature, _ ->
            val enabled = feature.config.boolean("enabled", true)
            val flag = if (enabled) "1" else "0"
            "settings put global airplane_mode_on $flag && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $enabled"
        }

        privilegedAction(registry, "android.location.enabled.set", "Location services", "Turn the Android master location switch on or off for the current user", FeatureCategory.DEVICE,
            listOf(FieldSchema.Toggle("enabled", "Enabled")), setOf("location", "gps", "location services")) { feature, _ ->
            "cmd location set-location-enabled ${feature.config.boolean("enabled", true)}"
        }

        privilegedAction(registry, "android.display.auto_rotate.set", "Auto-rotate", "Enable or disable Android automatic screen rotation", FeatureCategory.DISPLAY,
            listOf(FieldSchema.Toggle("enabled", "Enabled")), setOf("rotation", "auto rotate", "orientation")) { feature, _ ->
            "settings put system accelerometer_rotation ${if (feature.config.boolean("enabled", true)) 1 else 0}"
        }

        privilegedAction(registry, "android.display.screen_timeout.set", "Screen timeout", "Set the Android screen-off timeout", FeatureCategory.DISPLAY,
            listOf(FieldSchema.Duration("timeoutMs", "Screen timeout", true)), setOf("screen timeout", "sleep timeout", "display timeout")) { feature, _ ->
            val timeoutMs = feature.config.long("timeoutMs", 30_000L)
            require(timeoutMs in 1_000L..86_400_000L) { "Screen timeout must be between 1 second and 24 hours" }
            "settings put system screen_off_timeout $timeoutMs"
        }

        privilegedAction(registry, "android.display.dark_mode.set", "Dark theme", "Set Android system night mode to light, dark or automatic", FeatureCategory.DISPLAY,
            listOf(FieldSchema.Choice("mode", "Mode", true, listOf("light", "dark", "auto"))), setOf("dark mode", "night mode", "theme")) { feature, _ ->
            val mode = when (feature.config.string("mode", "auto")) {
                "light" -> "no"
                "dark" -> "yes"
                "auto" -> "auto"
                else -> error("Invalid dark theme mode")
            }
            "cmd uimode night $mode"
        }

        privilegedAction(registry, "android.power.battery_saver.set", "Battery saver", "Turn Android low-power mode on or off", FeatureCategory.DEVICE,
            listOf(FieldSchema.Toggle("enabled", "Enabled")), setOf("battery saver", "power saver", "low power")) { feature, _ ->
            "cmd power set-mode ${if (feature.config.boolean("enabled", true)) 1 else 0}"
        }

        privilegedAction(registry, "android.power.stay_awake.set", "Stay awake while charging", "Control which charging sources keep the screen awake", FeatureCategory.DEVICE,
            listOf(FieldSchema.Choice("mode", "Stay awake mode", true, listOf("off", "all", "usb", "ac", "wireless"))), setOf("stay awake", "keep screen on", "charging")) { feature, _ ->
            val argument = when (feature.config.string("mode", "off")) {
                "off" -> "false"
                "all" -> "true"
                "usb" -> "usb"
                "ac" -> "ac"
                "wireless" -> "wireless"
                else -> error("Invalid stay-awake mode")
            }
            "svc power stayon $argument"
        }

        privilegedAction(registry, "android.settings.put", "Write system setting", "Write an Android system/secure/global setting through a privileged shell backend", FeatureCategory.SYSTEM,
            listOf(FieldSchema.Choice("namespace", "Namespace", true, listOf("system", "secure", "global")), FieldSchema.Text("key", "Setting key", true), FieldSchema.Text("value", "Value", true)), setOf("settings put", "secure settings", "global settings")) { feature, ctx ->
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
                return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName))
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
