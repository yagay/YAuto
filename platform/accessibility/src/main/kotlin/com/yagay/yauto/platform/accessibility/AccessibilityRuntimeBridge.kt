package com.yagay.yauto.platform.accessibility

import java.util.concurrent.atomic.AtomicReference

data class AccessibilityWindowSnapshot(
    val packageName: String,
    val className: String? = null,
    val timestampEpochMs: Long = System.currentTimeMillis(),
)

/**
 * Small process-local bridge between the AccessibilityService and the automation runtime.
 * It intentionally carries only package/class identity; rule execution remains outside the service.
 */
object AccessibilityRuntimeBridge {
    private val current = AtomicReference<AccessibilityWindowSnapshot?>(null)
    @Volatile private var listener: ((previous: AccessibilityWindowSnapshot?, current: AccessibilityWindowSnapshot) -> Unit)? = null

    fun currentWindow(): AccessibilityWindowSnapshot? = current.get()

    fun setListener(value: ((previous: AccessibilityWindowSnapshot?, current: AccessibilityWindowSnapshot) -> Unit)?) {
        listener = value
    }

    internal fun update(packageName: String?, className: String?) {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return
        val next = AccessibilityWindowSnapshot(pkg, className?.takeIf { it.isNotBlank() })
        val previous = current.getAndSet(next)
        if (previous?.packageName == next.packageName && previous.className == next.className) return
        listener?.invoke(previous, next)
    }

    internal fun clearIfServiceStops() {
        current.set(null)
    }
}
