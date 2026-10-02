package com.yagay.yauto.platform.accessibility

import java.util.concurrent.atomic.AtomicReference

data class AccessibilityWindowSnapshot(
    val packageName: String,
    val className: String? = null,
    val timestampEpochMs: Long = System.currentTimeMillis(),
)

data class AccessibilityKeySnapshot(
    val keyCode: Int,
    val action: Int,
    val repeatCount: Int,
    val metaState: Int,
    val deviceId: Int,
    val timestampEpochMs: Long = System.currentTimeMillis(),
)

/** Process-local bridge carrying only foreground identity and non-text KeyEvent metadata. */
object AccessibilityRuntimeBridge {
    private val current = AtomicReference<AccessibilityWindowSnapshot?>(null)
    @Volatile private var listener: ((previous: AccessibilityWindowSnapshot?, current: AccessibilityWindowSnapshot) -> Unit)? = null
    @Volatile private var keyListener: ((AccessibilityKeySnapshot) -> Unit)? = null

    fun currentWindow(): AccessibilityWindowSnapshot? = current.get()

    fun setListener(value: ((previous: AccessibilityWindowSnapshot?, current: AccessibilityWindowSnapshot) -> Unit)?) {
        listener = value
    }

    fun setKeyListener(value: ((AccessibilityKeySnapshot) -> Unit)?) {
        keyListener = value
    }

    internal fun update(packageName: String?, className: String?) {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return
        val next = AccessibilityWindowSnapshot(pkg, className?.takeIf { it.isNotBlank() })
        val previous = current.getAndSet(next)
        if (previous?.packageName == next.packageName && previous.className == next.className) return
        listener?.invoke(previous, next)
    }

    internal fun dispatchKey(keyCode: Int, action: Int, repeatCount: Int, metaState: Int, deviceId: Int) {
        keyListener?.invoke(AccessibilityKeySnapshot(keyCode, action, repeatCount, metaState, deviceId))
    }

    internal fun clearIfServiceStops() {
        current.set(null)
    }
}
