package com.yagay.yauto.core.registry

import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef

const val FEATURE_BACKEND_CONFIG_KEY = "__backend"
const val FEATURE_METHOD_CONFIG_KEY = "__method"
private val ROOT_BACKENDS = setOf("root", "lsposed")
private val NO_ROOT_BACKENDS = setOf("android", "accessibility", "shizuku", "usage_stats")

/** Permission families are independent of the third-party app used as a reference. */
enum class FeatureMethod(val id: String) {
    /** Legacy saved automatic tasks: can cross permission families. New tasks do not offer this option. */
    AUTO("auto"),
    /** Public Android API, Accessibility and Shizuku started without Root. */
    NO_ROOT("no_root"),
    /** Root and LSPosed only. Never fall back to Shizuku or Accessibility. */
    ROOT_REQUIRED("root_required"),
}

fun FeatureMethod.allowsBackend(backendId: String): Boolean = when (this) {
    FeatureMethod.NO_ROOT -> backendId in NO_ROOT_BACKENDS
    FeatureMethod.ROOT_REQUIRED -> backendId in ROOT_BACKENDS
    FeatureMethod.AUTO -> backendId in NO_ROOT_BACKENDS || backendId in ROOT_BACKENDS
}

/** Keep saved MacroDroid/ShortX choices compatible while defaulting new work to non-root. */
fun FeatureRef.preferredMethod(): FeatureMethod {
    val saved = (config[FEATURE_METHOD_CONFIG_KEY] as? ConfigValue.StringValue)?.value
        ?.trim()?.lowercase()
    val backend = preferredBackendId()
    return when (saved) {
        "macrodroid", FeatureMethod.NO_ROOT.id -> FeatureMethod.NO_ROOT
        "shortx" -> if (backend == "shizuku") FeatureMethod.NO_ROOT else FeatureMethod.ROOT_REQUIRED
        FeatureMethod.ROOT_REQUIRED.id -> FeatureMethod.ROOT_REQUIRED
        FeatureMethod.AUTO.id -> FeatureMethod.AUTO
        null, "" -> if (backend in ROOT_BACKENDS) FeatureMethod.ROOT_REQUIRED else FeatureMethod.NO_ROOT
        else -> FeatureMethod.NO_ROOT
    }
}

/** A stale backend from another family is an error, not permission to escalate. */
fun FeatureRef.methodBackendIsCompatible(): Boolean =
    preferredBackendId()?.let { preferredMethod().allowsBackend(it) } ?: true

fun FeatureDescriptor.hasDualMethodRoutes(): Boolean {
    if (kind != FeatureKind.ACTION) return false
    val backends = resolvedImplementationOptions().mapNotNull { it.backendId }.toSet()
    return backends.any { FeatureMethod.NO_ROOT.allowsBackend(it) } &&
        backends.any { FeatureMethod.ROOT_REQUIRED.allowsBackend(it) }
}

fun FeatureDescriptor.methodBackends(method: FeatureMethod): List<FeatureImplementationOption> =
    resolvedImplementationOptions().filter { option ->
        option.backendId?.let(method::allowsBackend) == true
    }

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

    val dual = hasDualMethodRoutes()
    val selectableBackends = options.mapNotNull { it.backendId }.distinct()
    val methodField = if (dual && fields.none { it.key == FEATURE_METHOD_CONFIG_KEY }) {
        FieldSchema.Choice(
            key = FEATURE_METHOD_CONFIG_KEY,
            label = FEATURE_METHOD_CONFIG_KEY,
            options = listOf(FeatureMethod.NO_ROOT.id, FeatureMethod.ROOT_REQUIRED.id),
        )
    } else null
    val backendField = if (selectableBackends.size > 1 && fields.none { it.key == FEATURE_BACKEND_CONFIG_KEY }) {
        FieldSchema.Choice(
            key = FEATURE_BACKEND_CONFIG_KEY,
            label = FEATURE_BACKEND_CONFIG_KEY,
            options = listOf("auto") + selectableBackends,
        )
    } else null

    return copy(
        fields = listOfNotNull(methodField, backendField) + fields,
        fieldBehaviors = fieldBehaviors,
        keywords = keywords + requirements.map { it.id } + options.mapNotNull { it.backendId } +
            if (dual) setOf("macrodroid", "shortx", "implementation method") else emptySet(),
    )
}
