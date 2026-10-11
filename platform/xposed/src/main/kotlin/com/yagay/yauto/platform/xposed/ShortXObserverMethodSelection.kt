package com.yagay.yauto.platform.xposed

/**
 * Prefer public AppOps wrappers over matching Impl methods when both exist.
 * Installing both on the same call chain can fire an automation twice.
 * OEMs exposing only an Impl method remain supported.
 *
 * Other ShortX observer families retain their existing exact subscriptions.
 */
internal object ShortXObserverMethodSelection {
    fun select(spec: ShortXObserverHookSpec, availableNames: Set<String>): Set<String> {
        val available = spec.methodNames.intersect(availableNames)
        if (spec.payloadKind != ShortXHookPayloadKind.APP_OPS) return available
        val publicWrappers = available.filterNot { it.endsWith("Impl") }.toSet()
        return publicWrappers.ifEmpty { available }
    }
}
