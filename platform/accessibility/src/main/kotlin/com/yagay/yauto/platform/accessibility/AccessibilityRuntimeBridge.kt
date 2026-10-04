package com.yagay.yauto.platform.accessibility

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

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

data class AccessibilityUiEventSnapshot(
    val event: String,
    val packageName: String,
    val className: String? = null,
    val text: String = "",
    val contentDescription: String = "",
    val viewId: String = "",
    val screenText: String = "",
    val left: Int = -1,
    val top: Int = -1,
    val right: Int = -1,
    val bottom: Int = -1,
    val centerX: Int = -1,
    val centerY: Int = -1,
    val timestampEpochMs: Long = System.currentTimeMillis(),
)

data class AccessibilityFingerprintGestureSnapshot(
    val gesture: String,
    val timestampEpochMs: Long = System.currentTimeMillis(),
)

data class AccessibilityNodeSnapshot(
    val text: String,
    val contentDescription: String,
    val viewId: String,
    val className: String,
    val packageName: String,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val visible: Boolean,
)

/** Process-local bridge carrying foreground identity, hardware-key metadata and UI event snapshots. */
object AccessibilityRuntimeBridge {
    private val current = AtomicReference<AccessibilityWindowSnapshot?>(null)
    @Volatile private var listener: ((previous: AccessibilityWindowSnapshot?, current: AccessibilityWindowSnapshot) -> Unit)? = null
    @Volatile private var keyListener: ((AccessibilityKeySnapshot) -> Unit)? = null
    @Volatile private var uiEventListener: ((AccessibilityUiEventSnapshot) -> Unit)? = null
    @Volatile private var fingerprintGestureListener: ((AccessibilityFingerprintGestureSnapshot) -> Unit)? = null
    @Volatile private var fingerprintGestureAvailable: Boolean = false
    private val nextClick = AtomicReference<CompletableDeferred<AccessibilityUiEventSnapshot>?>(null)

    fun currentWindow(): AccessibilityWindowSnapshot? = current.get()

    suspend fun awaitNextClick(timeoutMs: Long): AccessibilityUiEventSnapshot? {
        val deferred = CompletableDeferred<AccessibilityUiEventSnapshot>()
        nextClick.getAndSet(deferred)?.cancel()
        return try {
            withTimeoutOrNull(timeoutMs.coerceIn(1_000L, 300_000L)) { deferred.await() }
        } finally {
            nextClick.compareAndSet(deferred, null)
        }
    }

    fun setListener(value: ((previous: AccessibilityWindowSnapshot?, current: AccessibilityWindowSnapshot) -> Unit)?) {
        listener = value
    }

    fun setKeyListener(value: ((AccessibilityKeySnapshot) -> Unit)?) {
        keyListener = value
    }

    fun setUiEventListener(value: ((AccessibilityUiEventSnapshot) -> Unit)?) {
        uiEventListener = value
    }

    fun setFingerprintGestureListener(value: ((AccessibilityFingerprintGestureSnapshot) -> Unit)?) {
        fingerprintGestureListener = value
    }

    fun isFingerprintGestureAvailable(): Boolean = fingerprintGestureAvailable

    suspend fun captureScreenshot(): Bitmap? = YAutoAccessibilityService.current?.captureScreenshotBitmap()

    internal fun updateFingerprintGestureAvailability(value: Boolean) {
        fingerprintGestureAvailable = value
    }

    internal fun dispatchFingerprintGesture(gesture: String) {
        fingerprintGestureListener?.invoke(AccessibilityFingerprintGestureSnapshot(gesture))
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

    internal fun dispatchUiEvent(snapshot: AccessibilityUiEventSnapshot) {
        if (snapshot.event == "click") nextClick.getAndSet(null)?.complete(snapshot)
        uiEventListener?.invoke(snapshot)
    }

    internal fun clearIfServiceStops() {
        current.set(null)
        fingerprintGestureAvailable = false
        nextClick.getAndSet(null)?.cancel()
    }
}
