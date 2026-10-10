package com.yagay.yauto.core.registry

/**
 * No-root includes Shizuku (started via wireless debugging), Android and Accessibility.
 * Root-required permits Root and LSPosed only. AUTO remains exclusively for historical tasks.
 * A chosen family never falls into the other unless a legacy task explicitly requested AUTO.
 */
suspend fun <T> routeFeatureMethod(
    method: FeatureMethod,
    macroSupported: Boolean,
    macrodroid: suspend () -> T,
    shortx: suspend () -> T,
    succeeded: (T) -> Boolean,
): T? = when (method) {
    FeatureMethod.NO_ROOT -> if (macroSupported) macrodroid() else null
    FeatureMethod.ROOT_REQUIRED -> shortx()
    FeatureMethod.AUTO -> {
        if (!macroSupported) shortx()
        else {
            val regular = macrodroid()
            if (succeeded(regular)) regular else shortx()
        }
    }
}

/** Try only the named backends, in order, never across no-root/root permission families. */
suspend fun <T> routeBackendCandidates(
    preferred: String?,
    candidates: List<String>,
    execute: suspend (String) -> T,
    succeeded: (T) -> Boolean,
): T? {
    if (preferred != null && preferred !in candidates) return null
    val choices = preferred?.let(::listOf) ?: candidates
    var last: T? = null
    for (backend in choices) {
        val result = execute(backend)
        last = result
        if (succeeded(result)) return result
    }
    return last
}
