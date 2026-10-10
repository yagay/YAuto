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
        // A new task without a method override uses automatic best-effort routing:
        // public Android / Accessibility -> Shizuku -> Root or LSPosed if needed.
        null, "" -> if (backend in ROOT_BACKENDS) FeatureMethod.ROOT_REQUIRED
            else if (backend in NO_ROOT_BACKENDS) FeatureMethod.NO_ROOT
            else FeatureMethod.AUTO
        else -> FeatureMethod.NO_ROOT
    }
}

/** A stale backend from another family is an error, not permission to escalate. */
fun FeatureRef.methodBackendIsCompatible(): Boolean =
    preferredBackendId()?.let { preferredMethod().allowsBackend(it) } ?: true

/**
 * Generic shell features must obey the selected permission family.
 * Tasks without a saved method retain their historic backend routing.
 */
fun FeatureRef.effectiveMethodBackendId(): String? {
    val selected = preferredBackendId()
    if (config[FEATURE_METHOD_CONFIG_KEY] !is ConfigValue.StringValue) return selected
    if (!methodBackendIsCompatible()) return "__invalid_method_backend__"
    if (selected != null) return selected
    return when (preferredMethod()) {
        FeatureMethod.NO_ROOT -> "shizuku"
        FeatureMethod.ROOT_REQUIRED -> "root"
        FeatureMethod.AUTO -> null
    }
}

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
 * Public feature configuration only contains business parameters. Backend/method
 * choices are an internal routing detail, not extra fields in the feature editor.
 * Old `__method`/`__backend` values are still understood by the execution layer.
 */
fun FeatureDescriptor.withAccessEditorMetadata(): FeatureDescriptor = this

/**
 * Picker visibility only: conservative metadata check. Never infer Root-only status
 * from the feature name or the mere presence of a privileged-capability option,
 * because Shizuku and public Android methods can satisfy the same operation.
 *
 * This does not disable an existing automation or unregister its executor.
 */
private val ROOT_ONLY_ACCESS = setOf(AccessRequirement.ROOT, AccessRequirement.LSPOSED, AccessRequirement.ZYGISK)
private val NON_ROOT_ACCESS = setOf(AccessRequirement.SHIZUKU, AccessRequirement.ACCESSIBILITY)

/** Interpret only verified implementation metadata; unknown mixed methods remain visible. */
fun FeatureImplementationOption.requiresRootOrLsposed(): Boolean = when (backendId) {
    "root", "lsposed", "zygisk" -> true
    "android", "accessibility", "shizuku", "usage_stats" -> false
    else -> requirements.any { it in ROOT_ONLY_ACCESS } && requirements.none { it in NON_ROOT_ACCESS }
}

/** Filter implementation methods, not the logical feature containing alternatives. */
fun FeatureDescriptor.visibleImplementationOptions(showRootOrLsposed: Boolean): List<FeatureImplementationOption> =
    resolvedImplementationOptions().filter { showRootOrLsposed || !it.requiresRootOrLsposed() }

fun FeatureDescriptor.isRootExclusiveFeature(): Boolean {
    val options = resolvedImplementationOptions()
    return if (options.isNotEmpty()) options.all { it.requiresRootOrLsposed() }
        else accessRequirements.any { it in ROOT_ONLY_ACCESS }
}

/** Shared presentation metadata: root is required only when no verified non-root path exists. */
fun FeatureDescriptor.requiresRootToRun(): Boolean = isRootExclusiveFeature()

/** Common permissions, not the union of alternative backends' permissions. */
fun FeatureDescriptor.mandatoryAccessRequirements(): Set<AccessRequirement> {
    val options = resolvedImplementationOptions()
    if (options.isEmpty()) return resolvedAccessRequirements()
    val common = options.map { it.requirements }.reduceOrNull { a, b -> a intersect b }.orEmpty()
    val alternativeRequirements = options.flatMap { it.requirements }.toSet()
    return (accessRequirements - alternativeRequirements) + common
}
