package com.yagay.yauto.feature.standard.privileged

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDefinition
import com.yagay.yauto.core.registry.FieldBehavior
import com.yagay.yauto.core.registry.FieldSchema

internal object PrivilegedAppFeatures {
    val definitions: List<FeatureDefinition> = listOf(
        privilegedCommandFeature(
            privilegedDescriptor(
                "android.app.clear_data",
                "Clear app data",
                "Clear an application's user data through Shizuku or Root",
                FeatureCategory.APP,
                fields = listOf(FieldSchema.AppPicker("package", "App / package", true)),
                keywords = setOf("clear data", "reset app", "pm clear"),
                behaviors = mapOf("package" to FieldBehavior(supportsVariables = true)),
            )
        ) { feature, context ->
            val pkg = validatedPackage(feature.config.string("package").resolveVariables(context.variables))
            "pm clear ${shellQuote(pkg)}"
        },
        privilegedCommandFeature(
            privilegedDescriptor(
                "android.app.enabled.set",
                "Enable / disable app",
                "Enable an app or disable it for the primary user",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.AppPicker("package", "App / package", true),
                    FieldSchema.Toggle("enabled", "Enabled"),
                ),
                keywords = setOf("enable app", "disable app", "freeze", "pm disable"),
                behaviors = mapOf(
                    "package" to FieldBehavior(supportsVariables = true),
                    "enabled" to FieldBehavior(defaultValue = ConfigValue.BooleanValue(true)),
                ),
            )
        ) { feature, context ->
            val pkg = validatedPackage(feature.config.string("package").resolveVariables(context.variables))
            if (feature.config.boolean("enabled", true)) {
                "pm enable ${shellQuote(pkg)}"
            } else {
                "pm disable-user --user 0 ${shellQuote(pkg)}"
            }
        },
        privilegedCommandFeature(
            privilegedDescriptor(
                "android.component.enabled.set",
                "Enable / disable component",
                "Enable or disable an Android activity, service, receiver or provider component",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Text("component", "Component (package/class)", true),
                    FieldSchema.Toggle("enabled", "Enabled"),
                ),
                keywords = setOf("component", "activity", "service", "receiver", "disable component"),
                behaviors = mapOf(
                    "component" to FieldBehavior(supportsVariables = true),
                    "enabled" to FieldBehavior(defaultValue = ConfigValue.BooleanValue(true)),
                ),
            )
        ) { feature, context ->
            val component = feature.config.string("component").resolveVariables(context.variables).trim()
            require(COMPONENT_NAME.matches(component)) { "Invalid component name" }
            if (feature.config.boolean("enabled", true)) {
                "pm enable ${shellQuote(component)}"
            } else {
                "pm disable ${shellQuote(component)}"
            }
        },
        privilegedCommandFeature(
            privilegedDescriptor(
                "android.permission.set",
                "Grant / revoke permission",
                "Grant or revoke a runtime permission through package manager",
                FeatureCategory.SYSTEM,
                fields = listOf(
                    FieldSchema.AppPicker("package", "App / package", true),
                    FieldSchema.Text("permission", "Permission", true),
                    FieldSchema.Choice("mode", "Mode", true, listOf("grant", "revoke")),
                ),
                keywords = setOf("permission", "grant", "revoke", "pm grant"),
                behaviors = mapOf(
                    "package" to FieldBehavior(supportsVariables = true),
                    "permission" to FieldBehavior(supportsVariables = true),
                    "mode" to FieldBehavior(defaultValue = ConfigValue.StringValue("grant")),
                ),
            )
        ) { feature, context ->
            val pkg = validatedPackage(feature.config.string("package").resolveVariables(context.variables))
            val permission = feature.config.string("permission").resolveVariables(context.variables).trim()
            require(PERMISSION_NAME.matches(permission)) { "Invalid permission name" }
            val verb = if (feature.config.string("mode", "grant") == "revoke") "revoke" else "grant"
            "pm $verb ${shellQuote(pkg)} ${shellQuote(permission)}"
        },
        privilegedCommandFeature(
            privilegedDescriptor(
                "android.appops.set",
                "Set AppOps mode",
                "Set an AppOps operation mode for an application",
                FeatureCategory.SYSTEM,
                fields = listOf(
                    FieldSchema.AppPicker("package", "App / package", true),
                    FieldSchema.Text("operation", "AppOps operation", true),
                    FieldSchema.Choice(
                        "mode",
                        "Mode",
                        true,
                        listOf("allow", "ignore", "deny", "default", "foreground"),
                    ),
                ),
                keywords = setOf("appops", "privacy", "permission", "app operation"),
                behaviors = mapOf(
                    "package" to FieldBehavior(supportsVariables = true),
                    "operation" to FieldBehavior(supportsVariables = true),
                    "mode" to FieldBehavior(defaultValue = ConfigValue.StringValue("default")),
                ),
            )
        ) { feature, context ->
            val pkg = validatedPackage(feature.config.string("package").resolveVariables(context.variables))
            val operation = feature.config.string("operation").resolveVariables(context.variables).trim()
            require(SAFE_TOKEN.matches(operation)) { "Invalid AppOps operation" }
            val mode = feature.config.string("mode", "default")
            require(mode in setOf("allow", "ignore", "deny", "default", "foreground")) { "Invalid AppOps mode" }
            "appops set ${shellQuote(pkg)} ${shellQuote(operation)} ${shellQuote(mode)}"
        },
    )
}
