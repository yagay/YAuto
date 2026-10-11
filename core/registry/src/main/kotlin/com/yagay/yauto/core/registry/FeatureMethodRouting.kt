package com.yagay.yauto.core.registry

/**
 * No-root includes Shizuku (started via wireless debugging), Android and Accessibility.
 * Root-required permits Root and LSPosed only. AUTO is the default for
 * tasks without a saved override and prioritizes the privileged method.
 * A chosen family never crosses into the other.
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
        // Do not eagerly execute both implementations. A successful privileged
        // action is final; the no-root route is a sequential fallback only.
        val privileged = shortx()
        if (succeeded(privileged) || !macroSupported) privileged else macrodroid()
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
    val choices = preferred?.let { listOf(it) } ?: candidates
    var last: T? = null
    for (backend in choices) {
        val result = execute(backend)
        last = result
        if (succeeded(result)) return result
    }
    return last
}
