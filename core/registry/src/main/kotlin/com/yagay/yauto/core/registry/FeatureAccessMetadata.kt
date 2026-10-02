package com.yagay.yauto.core.registry

import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef

const val FEATURE_BACKEND_CONFIG_KEY = "__backend"

fun FeatureRef.preferredBackendId(): String? =
    (config[FEATURE_BACKEND_CONFIG_KEY] as? ConfigValue.StringValue)?.value
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotBlank() && it != "auto" }

fun FeatureDescriptor.resolvedAccessRequirements(): Set<AccessRequirement> = buildSet {
    addAll(accessRequirements)
    capabilities.forEach { capability ->
        when (capability) {
            CapabilityIds.PRIVILEGED_SHELL -> {
                add(AccessRequirement.ROOT)
                add(AccessRequirement.SHIZUKU)
            }
            CapabilityIds.SYSTEM_UI -> {
                add(AccessRequirement.LSPOSED)
                add(AccessRequirement.ROOT)
                add(AccessRequirement.SHIZUKU)
            }
            CapabilityIds.ACCESSIBILITY -> add(AccessRequirement.ACCESSIBILITY)
            CapabilityIds.NOTIFICATION_LISTENER -> add(AccessRequirement.NOTIFICATION_LISTENER)
            CapabilityIds.LSPOSED -> add(AccessRequirement.LSPOSED)
        }
    }
}

fun FeatureDescriptor.resolvedImplementationOptions(): List<FeatureImplementationOption> {
    if (implementationOptions.isNotEmpty()) return implementationOptions
    if (capabilities.size != 1) return emptyList()
    return when (capabilities.single()) {
        CapabilityIds.PRIVILEGED_SHELL -> listOf(
            FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
            FeatureImplementationOption("shizuku", setOf(AccessRequirement.SHIZUKU)),
        )
        CapabilityIds.SYSTEM_UI -> listOf(
            FeatureImplementationOption("lsposed", setOf(AccessRequirement.LSPOSED), restartRequired = true),
            FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
            FeatureImplementationOption("shizuku", setOf(AccessRequirement.SHIZUKU)),
        )
        CapabilityIds.ACCESSIBILITY -> listOf(
            FeatureImplementationOption("accessibility", setOf(AccessRequirement.ACCESSIBILITY)),
        )
        CapabilityIds.LSPOSED -> listOf(
            FeatureImplementationOption("lsposed", setOf(AccessRequirement.LSPOSED), restartRequired = true),
        )
        else -> emptyList()
    }
}

/**
 * Core only injects a stable backend selector. Display labels, access summaries, descriptions and
 * implementation trade-offs are resolved by the Android UI resource layer.
 */
fun FeatureDescriptor.withAccessEditorMetadata(): FeatureDescriptor {
    val requirements = resolvedAccessRequirements()
    val options = resolvedImplementationOptions()
    if (requirements.isEmpty() && options.isEmpty()) return this

    val selectableBackends = options.mapNotNull { it.backendId }.distinct()
    val backendField = if (selectableBackends.size > 1 && fields.none { it.key == FEATURE_BACKEND_CONFIG_KEY }) {
        FieldSchema.Choice(
            key = FEATURE_BACKEND_CONFIG_KEY,
            label = FEATURE_BACKEND_CONFIG_KEY,
            required = false,
            options = listOf("auto") + selectableBackends,
        )
    } else null

    return copy(
        fields = if (backendField == null) fields else listOf(backendField) + fields,
        keywords = keywords + requirements.map { it.id } + options.mapNotNull { it.backendId },
    )
}
