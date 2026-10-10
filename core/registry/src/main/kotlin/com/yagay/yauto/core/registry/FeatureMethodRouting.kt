package com.yagay.yauto.core.registry

/**
 * Prefer public Android/accessibility in automatic mode, then fall back to the
 * privileged ShortX route only when the first attempt really failed.
 * Explicit selection is strict: never silently escalate MacroDroid to Root.
 */
suspend fun <T> routeFeatureMethod(
    method: FeatureMethod,
    macroSupported: Boolean,
    macrodroid: suspend () -> T,
    shortx: suspend () -> T,
    succeeded: (T) -> Boolean,
): T? = when (method) {
    FeatureMethod.MACRODROID -> if (macroSupported) macrodroid() else null
    FeatureMethod.SHORTX -> shortx()
    FeatureMethod.AUTO -> {
        if (!macroSupported) shortx()
        else {
            val regular = macrodroid()
            if (succeeded(regular)) regular else shortx()
        }
    }
}
