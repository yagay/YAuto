package com.yagay.yauto.feature.standard.privileged

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDefinition
import com.yagay.yauto.core.registry.FieldBehavior
import com.yagay.yauto.core.registry.FieldSchema
import com.yagay.yauto.core.registry.resolveVariables

internal object PrivilegedSettingsFeatures {
    val definitions: List<FeatureDefinition> = listOf(
        privilegedCommandFeature(
            privilegedDescriptor(
                "android.settings.put",
                "Write system setting",
                "Write an Android system/secure/global setting through a privileged shell backend",
                FeatureCategory.SYSTEM,
                fields = listOf(
                    FieldSchema.Choice("namespace", "Namespace", true, listOf("system", "secure", "global")),
                    FieldSchema.Text("key", "Setting key", true),
                    FieldSchema.Text("value", "Value", true),
                ),
                keywords = setOf("settings put", "secure settings", "global settings"),
                behaviors = mapOf(
                    "namespace" to FieldBehavior(defaultValue = ConfigValue.StringValue("system")),
                    "key" to FieldBehavior(supportsVariables = true),
                    "value" to FieldBehavior(supportsVariables = true),
                ),
            )
        ) { feature, context ->
            val namespace = feature.config.string("namespace", "system")
            val key = feature.config.string("key").resolveVariables(context.variables).trim()
            val value = feature.config.string("value").resolveVariables(context.variables)
            require(namespace in setOf("system", "secure", "global")) { "Invalid settings namespace" }
            require(SAFE_SETTING_KEY.matches(key)) { "Invalid settings key" }
            "settings put $namespace ${shellQuote(key)} ${shellQuote(value)}"
        },
        privilegedCommandFeature(
            privilegedDescriptor(
                "android.settings.delete",
                "Delete system setting",
                "Delete an Android system/secure/global setting through a privileged shell backend",
                FeatureCategory.SYSTEM,
                fields = listOf(
                    FieldSchema.Choice("namespace", "Namespace", true, listOf("system", "secure", "global")),
                    FieldSchema.Text("key", "Setting key", true),
                ),
                keywords = setOf("settings delete", "secure settings", "global settings"),
                behaviors = mapOf(
                    "namespace" to FieldBehavior(defaultValue = ConfigValue.StringValue("system")),
                    "key" to FieldBehavior(supportsVariables = true),
                ),
            )
        ) { feature, context ->
            val namespace = feature.config.string("namespace", "system")
            val key = feature.config.string("key").resolveVariables(context.variables).trim()
            require(namespace in setOf("system", "secure", "global")) { "Invalid settings namespace" }
            require(SAFE_SETTING_KEY.matches(key)) { "Invalid settings key" }
            "settings delete $namespace ${shellQuote(key)}"
        },
    )
}
