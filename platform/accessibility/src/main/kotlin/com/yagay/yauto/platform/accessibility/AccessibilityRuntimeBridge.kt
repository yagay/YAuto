package com.yagay.yauto.platform.accessibility

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay

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
    val scanCode: Int = 0,
    val deviceName: String = "",
    val deviceDescriptor: String = "",
    val vendorId: Int = 0,
    val productId: Int = 0,
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
    /** Real target-window activity, not YAuto's own MessageQueue idleness. */
    private data class UiActivity(val packageName: String, val updatedElapsedMs: Long)
    private val lastUiActivity = AtomicReference<UiActivity?>(null)

    @Volatile private var listener: ((previous: AccessibilityWindowSnapshot?, current: AccessibilityWindowSnapshot) -> Unit)? = null
    @Volatile private var keyListener: ((AccessibilityKeySnapshot) -> Unit)? = null
    @Volatile private var uiEventListener: ((AccessibilityUiEventSnapshot) -> Unit)? = null
    @Volatile private var fingerprintGestureListener: ((AccessibilityFingerprintGestureSnapshot) -> Unit)? = null
    @Volatile private var fingerprintGestureAvailable: Boolean = false
    private val nextClick = AtomicReference<CompletableDeferred<AccessibilityUiEventSnapshot>?>(null)
    private val nextKey = AtomicReference<CompletableDeferred<AccessibilityKeySnapshot>?>(null)
    private val subscribedUiRuntimeEvents = AtomicReference<Set<String>>(emptySet())

    /** Keep OEM Accessibility callbacks from crashing the entire service. */
    private inline fun dispatchToRuntime(kind: String, callback: () -> Unit) {
        try {
            callback()
        } catch (error: Exception) {
            // No event text, key descriptors, or private app content in logs.
            runCatching { Log.e("YAutoAccessibility", "Runtime callback failed: " +
                kind + ": " + error.javaClass.simpleName) }
        }
    }

    fun currentWindow(): AccessibilityWindowSnapshot? = current.get()

    /** Updated before allocating rich UI snapshots, even without subscribed UI event rules. */
    internal fun recordUiActivity(packageName: String?) {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isNotEmpty()) lastUiActivity.set(UiActivity(pkg, SystemClock.elapsedRealtime()))
    }

    /**
     * Wait for a real foreground Accessibility window to remain unchanged for
     * quietMs. A missing service/window, foreground switch, or timeout returns
     * false; never treat idle YAuto main Looper as remote app stability.
     */
    suspend fun awaitUiIdle(packageName: String, quietMs: Long, timeoutMs: Long): Boolean {
        if (YAutoAccessibilityService.current == null) return false
        val quiet = quietMs.coerceIn(100L, 10_000L)
        val total = timeoutMs.coerceIn(100L, 60_000L)
        val started = SystemClock.elapsedRealtime()
        val deadline = started + total
        while (true) {
            if (YAutoAccessibilityService.current == null) return false
            val currentPkg = current.get()?.packageName.orEmpty()
            if (currentPkg.isNotEmpty() && currentPkg != "com.yagay.yauto" &&
                (packageName.isBlank() || currentPkg == packageName)) {
                val last = lastUiActivity.get()
                val lastChanged = if (last?.packageName == currentPkg)
                    maxOf(started, last.updatedElapsedMs) else started
                val now = SystemClock.elapsedRealtime()
                if (now - lastChanged >= quiet) return true
            }
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) return false
            delay(minOf(remaining, 100L))
        }
    }



    suspend fun awaitNextClick(timeoutMs: Long): AccessibilityUiEventSnapshot? {
        val deferred = CompletableDeferred<AccessibilityUiEventSnapshot>()
        nextClick.getAndSet(deferred)?.cancel()
        return try {
            withTimeoutOrNull(timeoutMs.coerceIn(1_000L, 300_000L)) { deferred.await() }
        } finally {
            nextClick.compareAndSet(deferred, null)
        }
    }

    suspend fun awaitNextKey(timeoutMs: Long): AccessibilityKeySnapshot? {
        val deferred = CompletableDeferred<AccessibilityKeySnapshot>()
        nextKey.getAndSet(deferred)?.cancel()
        return try {
            withTimeoutOrNull(timeoutMs.coerceIn(1_000L, 60_000L)) { deferred.await() }
        } finally {
            nextKey.compareAndSet(deferred, null)
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

    fun configureUiRuntimeEvents(typeIds: Set<String>) {
        subscribedUiRuntimeEvents.set(typeIds.toSet())
    }

    internal fun shouldDispatchUiEvent(kind: String): Boolean {
        if (kind == "click" && nextClick.get() != null) return true
        val typeId = when (kind) {
            "click" -> "android.event.ui_click"
            "long_click" -> "android.event.ui_long_click"
            "text_changed" -> "android.event.ui_text_changed"
            "focused" -> "android.event.ui_focused"
            "scrolled" -> "android.event.ui_scrolled"
            "content_changed" -> "android.event.screen_content_changed"
            "toast" -> "android.event.toast_shown"
            else -> return false
        }
        return typeId in subscribedUiRuntimeEvents.get()
    }

    internal fun shouldCaptureScreenText(): Boolean =
        "android.event.screen_content_changed" in subscribedUiRuntimeEvents.get()

    private fun isRuntimeUiEventSubscribed(kind: String): Boolean {
        val typeId = when (kind) {
            "click" -> "android.event.ui_click"
            "long_click" -> "android.event.ui_long_click"
            "text_changed" -> "android.event.ui_text_changed"
            "focused" -> "android.event.ui_focused"
            "scrolled" -> "android.event.ui_scrolled"
            "content_changed" -> "android.event.screen_content_changed"
            "toast" -> "android.event.toast_shown"
            else -> return false
        }
        return typeId in subscribedUiRuntimeEvents.get()
    }

    fun isFingerprintGestureAvailable(): Boolean = fingerprintGestureAvailable

    suspend fun captureScreenshot(): Bitmap? = YAutoAccessibilityService.current?.captureScreenshotBitmap()

    internal fun updateFingerprintGestureAvailability(value: Boolean) {
        fingerprintGestureAvailable = value
    }

    internal fun dispatchFingerprintGesture(gesture: String) {
        dispatchToRuntime("fingerprint") {
            fingerprintGestureListener?.invoke(AccessibilityFingerprintGestureSnapshot(gesture))
        }
    }

    /**
     * Content changes frequently identify a child View class rather than the
     * foreground Activity. Never overwrite the last real window class or
     * report a spurious application transition for the same package.
     */
    internal fun updateFromContent(packageName: String?) {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty() || current.get()?.packageName == pkg) return
        update(pkg, null)
    }

    internal fun update(packageName: String?, className: String?) {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return
        val next = AccessibilityWindowSnapshot(pkg, className?.takeIf { it.isNotBlank() })
        val previous = current.getAndSet(next)
        if (previous?.packageName == next.packageName && previous.className == next.className) return
        dispatchToRuntime("window") { listener?.invoke(previous, next) }
    }

    internal fun dispatchKey(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        metaState: Int,
        deviceId: Int,
        scanCode: Int = 0,
        deviceName: String = "",
        deviceDescriptor: String = "",
        vendorId: Int = 0,
        productId: Int = 0,
    ) {
        val snapshot = AccessibilityKeySnapshot(
            keyCode = keyCode,
            action = action,
            repeatCount = repeatCount,
            metaState = metaState,
            deviceId = deviceId,
            scanCode = scanCode,
            deviceName = deviceName,
            deviceDescriptor = deviceDescriptor,
            vendorId = vendorId,
            productId = productId,
        )
        nextKey.getAndSet(null)?.complete(snapshot)
        dispatchToRuntime("key") { keyListener?.invoke(snapshot) }
    }

    internal fun dispatchUiEvent(snapshot: AccessibilityUiEventSnapshot) {
        if (snapshot.event == "click") nextClick.getAndSet(null)?.complete(snapshot)
        if (isRuntimeUiEventSubscribed(snapshot.event)) {
            dispatchToRuntime("ui") { uiEventListener?.invoke(snapshot) }
        }
    }

    internal fun clearIfServiceStops() {
        current.set(null)
        lastUiActivity.set(null)
        fingerprintGestureAvailable = false
        nextClick.getAndSet(null)?.cancel()
        nextKey.getAndSet(null)?.cancel()
    }
}
