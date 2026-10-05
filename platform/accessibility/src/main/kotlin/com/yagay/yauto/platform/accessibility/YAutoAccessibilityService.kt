package com.yagay.yauto.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.FingerprintGestureController
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.view.Display
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class YAutoAccessibilityService : AccessibilityService() {
    private val fingerprintCallback = object : FingerprintGestureController.FingerprintGestureCallback() {
        override fun onGestureDetectionAvailabilityChanged(available: Boolean) {
            AccessibilityRuntimeBridge.updateFingerprintGestureAvailability(available)
        }

        override fun onGestureDetected(gesture: Int) {
            val name = when (gesture) {
                FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_UP -> "swipe_up"
                FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_DOWN -> "swipe_down"
                FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_LEFT -> "swipe_left"
                FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_RIGHT -> "swipe_right"
                else -> "unknown"
            }
            AccessibilityRuntimeBridge.dispatchFingerprintGesture(name)
        }
    }

    override fun onServiceConnected() {
        current = this
        super.onServiceConnected()
        runCatching {
            fingerprintGestureController.registerFingerprintGestureCallback(fingerprintCallback, null)
            AccessibilityRuntimeBridge.updateFingerprintGestureAvailability(fingerprintGestureController.isGestureDetectionAvailable)
        }
        rootInActiveWindow?.let { root ->
            AccessibilityRuntimeBridge.update(root.packageName?.toString(), root.className?.toString())
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val eventPackage = event.packageName?.toString().orEmpty()
        val ownPackage = eventPackage == packageName
        if (ownPackage) {
            // Window identity only needs real window transitions. Ignoring YAuto's own content
            // changes also avoids snapshot allocation/atomic churn on every Compose update.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
            ) {
                AccessibilityRuntimeBridge.update(eventPackage, event.className?.toString())
            }
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            AccessibilityRuntimeBridge.update(eventPackage, event.className?.toString())
        }

        val kind = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "click"
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "long_click"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "text_changed"
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> "focused"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "scrolled"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "content_changed"
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "toast"
            else -> null
        } ?: return
        if (!AccessibilityRuntimeBridge.shouldDispatchUiEvent(kind)) return

        val source = event.source
        val eventText = event.text.orEmpty().joinToString(" ") { it?.toString().orEmpty() }.trim()
        val sourceText = source?.text?.toString().orEmpty()
        val text = eventText.ifBlank { sourceText }.take(MAX_EVENT_TEXT)
        val description = source?.contentDescription?.toString().orEmpty().take(MAX_EVENT_TEXT)
        val viewId = source?.viewIdResourceName.orEmpty().take(MAX_VIEW_ID)
        val bounds = Rect().also { rect -> source?.getBoundsInScreen(rect) }
        val screenText = if (kind == "content_changed" && AccessibilityRuntimeBridge.shouldCaptureScreenText()) {
            screenText(includeDescriptions = true, unique = true, limit = EVENT_SCREEN_TEXT_NODE_LIMIT)
                .take(MAX_SCREEN_TEXT)
        } else {
            ""
        }
        AccessibilityRuntimeBridge.dispatchUiEvent(
            AccessibilityUiEventSnapshot(
                event = kind,
                packageName = eventPackage,
                className = event.className?.toString(),
                text = text,
                contentDescription = description,
                viewId = viewId,
                screenText = screenText,
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom,
                centerX = if (source != null) bounds.centerX() else -1,
                centerY = if (source != null) bounds.centerY() else -1,
            )
        )
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        AccessibilityRuntimeBridge.dispatchKey(
            keyCode = event.keyCode,
            action = event.action,
            repeatCount = event.repeatCount,
            metaState = event.metaState,
            deviceId = event.deviceId,
        )
        return false
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        runCatching { fingerprintGestureController.unregisterFingerprintGestureCallback(fingerprintCallback) }
        if (current === this) current = null
        AccessibilityRuntimeBridge.clearIfServiceStops()
        super.onDestroy()
    }

    internal fun clickText(text: String, exact: Boolean): Boolean =
        findTextNode(text, exact)?.let(::clickNearest) == true

    internal fun clickTextAdvanced(text: String, mode: String, ignoreCase: Boolean): Boolean =
        findMatchingNode(text, mode, ignoreCase)?.let(::clickNearest) == true

    internal fun longClickText(text: String, exact: Boolean): Boolean =
        findTextNode(text, exact)?.let { performNearest(it, AccessibilityNodeInfo.ACTION_LONG_CLICK) } == true

    internal fun longClickViewId(viewId: String): Boolean =
        findViewIdNode(viewId)?.let { performNearest(it, AccessibilityNodeInfo.ACTION_LONG_CLICK) } == true

    internal fun focusViewId(viewId: String): Boolean =
        findViewIdNode(viewId)?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) == true

    internal fun clearTextByViewId(viewId: String): Boolean =
        findViewIdNode(viewId)?.let { setNodeText(it, "") } == true

    internal fun selectAllByViewId(viewId: String): Boolean {
        val node = findViewIdNode(viewId) ?: return false
        val length = node.text?.length ?: return false
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, length)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    internal fun copyByViewId(viewId: String): Boolean =
        findViewIdNode(viewId)?.performAction(AccessibilityNodeInfo.ACTION_COPY) == true

    internal fun cutByViewId(viewId: String): Boolean =
        findViewIdNode(viewId)?.performAction(AccessibilityNodeInfo.ACTION_CUT) == true

    internal fun pasteByViewId(viewId: String): Boolean =
        findViewIdNode(viewId)?.performAction(AccessibilityNodeInfo.ACTION_PASTE) == true

    internal fun scrollViewId(viewId: String, direction: String): Boolean {
        val action = when (direction) {
            "forward", "down", "right" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "backward", "up", "left" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> return false
        }
        return findViewIdNode(viewId)?.performAction(action) == true
    }

    internal fun viewBounds(viewId: String): Rect? {
        val node = findViewIdNode(viewId) ?: return null
        return Rect().also(node::getBoundsInScreen)
    }

    internal fun clickViewId(viewId: String): Boolean =
        findViewIdNode(viewId)?.let(::clickNearest) == true

    internal fun clickDescription(description: String, exact: Boolean): Boolean {
        if (description.isBlank()) return false
        val node = walkActiveWindow().firstOrNull { item ->
            val current = item.contentDescription?.toString().orEmpty()
            if (exact) current == description else current.contains(description, ignoreCase = true)
        } ?: return false
        return clickNearest(node)
    }


    internal fun performFocusedContextAction(action: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: return false
        return when (action) {
            "select_all" -> {
                val length = node.text?.length ?: return false
                val args = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, length)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
            }
            "copy" -> node.performAction(AccessibilityNodeInfo.ACTION_COPY)
            "cut" -> node.performAction(AccessibilityNodeInfo.ACTION_CUT)
            "paste" -> node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            "clear" -> setNodeText(node, "")
            else -> false
        }
    }

    internal fun setFocusedText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return setNodeText(node, text)
    }

    internal fun setTextByViewId(viewId: String, text: String): Boolean =
        findViewIdNode(viewId)?.let { setNodeText(it, text) } == true

    internal fun hasText(text: String, exact: Boolean): Boolean = findTextNode(text, exact) != null
    internal fun hasViewId(viewId: String): Boolean = findViewIdNode(viewId) != null
    internal fun keyboardVisible(): Boolean = windows.orEmpty().any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    internal fun matchesText(text: String, mode: String, ignoreCase: Boolean): Boolean =
        findMatchingNode(text, mode, ignoreCase) != null

    internal fun textByViewId(viewId: String): String? {
        val node = findViewIdNode(viewId) ?: return null
        return node.text?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
    }

    internal fun screenText(includeDescriptions: Boolean, unique: Boolean, limit: Int): String {
        val values = ArrayList<String>()
        val seen = LinkedHashSet<String>()
        walkActiveWindow().take(limit.coerceIn(1, MAX_NODE_LIMIT)).forEach { node ->
            val candidates = buildList {
                node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
                if (includeDescriptions) {
                    node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
            candidates.forEach { value ->
                if (!unique || seen.add(value)) values += value
            }
        }
        return values.joinToString("\\n").take(MAX_SCREEN_TEXT)
    }

    internal fun uiNodes(limit: Int, onlyVisible: Boolean, clickableOnly: Boolean): List<AccessibilityNodeSnapshot> =
        walkActiveWindow()
            .filter { !onlyVisible || it.isVisibleToUser }
            .filter { !clickableOnly || it.isClickable || it.isLongClickable }
            .take(limit.coerceIn(1, MAX_NODE_LIMIT))
            .map { node ->
                AccessibilityNodeSnapshot(
                    text = node.text?.toString().orEmpty().take(MAX_EVENT_TEXT),
                    contentDescription = node.contentDescription?.toString().orEmpty().take(MAX_EVENT_TEXT),
                    viewId = node.viewIdResourceName.orEmpty().take(MAX_VIEW_ID),
                    className = node.className?.toString().orEmpty().take(MAX_VIEW_ID),
                    packageName = node.packageName?.toString().orEmpty().take(MAX_VIEW_ID),
                    clickable = node.isClickable,
                    longClickable = node.isLongClickable,
                    editable = node.isEditable,
                    scrollable = node.isScrollable,
                    enabled = node.isEnabled,
                    visible = node.isVisibleToUser,
                )
            }
            .toList()


    internal fun scrollToLocation(location: String): Boolean {
        val action = when (location) {
            "top", "top_force", "backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            "bottom", "bottom_force", "forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else -> return false
        }
        val root = rootInActiveWindow ?: return false
        val node = walk(root).firstOrNull {
            it.isScrollable && it.actionList.any { actionInfo -> actionInfo.id == action }
        } ?: return false
        val iterations = when (location) {
            "top_force", "bottom_force" -> 64
            "top", "bottom" -> 16
            else -> 1
        }
        var changed = false
        repeat(iterations) {
            val ok = node.performAction(action)
            if (!ok) return@repeat
            changed = true
        }
        return changed
    }

    internal fun scroll(direction: String): Boolean {
        val action = when (direction) {
            "forward", "down", "right" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "backward", "up", "left" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> return false
        }
        val root = rootInActiveWindow ?: return false
        return walk(root).firstOrNull { it.isScrollable && it.actionList.any { actionInfo -> actionInfo.id == action } }
            ?.performAction(action) == true
    }

    internal fun globalAction(name: String): Boolean {
        val action = when (name) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power_dialog" -> GLOBAL_ACTION_POWER_DIALOG
            "lock_screen" -> GLOBAL_ACTION_LOCK_SCREEN
            else -> return false
        }
        return performGlobalAction(action)
    }

    internal suspend fun captureScreenshotBitmap(): Bitmap? = suspendCancellableCoroutine { continuation ->
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    val bitmap = runCatching {
                        Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    }.getOrNull()
                    runCatching { buffer.close() }
                    if (continuation.isActive) continuation.resume(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        )
    }

    internal suspend fun tap(x: Float, y: Float, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return gesture(path, durationMs.coerceIn(1, 60_000))
    }

    internal suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return gesture(path, durationMs.coerceIn(1, 60_000))
    }

    internal suspend fun gesturePath(points: List<Pair<Float, Float>>, durationMs: Long): Boolean {
        if (points.isEmpty()) return false
        val path = Path().apply {
            moveTo(points.first().first, points.first().second)
            points.drop(1).forEach { (x, y) -> lineTo(x, y) }
        }
        return gesture(path, durationMs.coerceIn(1, 60_000))
    }

    private fun findTextNode(text: String, exact: Boolean): AccessibilityNodeInfo? {
        if (text.isBlank()) return null
        val root = rootInActiveWindow ?: return null
        return root.findAccessibilityNodeInfosByText(text).orEmpty().firstOrNull { node ->
            if (!exact) {
                node.text?.toString()?.contains(text, ignoreCase = true) == true ||
                    node.contentDescription?.toString()?.contains(text, ignoreCase = true) == true
            } else {
                node.text?.toString() == text || node.contentDescription?.toString() == text
            }
        }
    }

    private fun findMatchingNode(text: String, mode: String, ignoreCase: Boolean): AccessibilityNodeInfo? {
        if (text.isBlank()) return null
        val regex = if (mode == "regex") {
            runCatching {
                Regex(text, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
            }.getOrNull() ?: return null
        } else {
            null
        }
        return walkActiveWindow().firstOrNull { node ->
            val candidates = listOf(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty())
            candidates.any { candidate ->
                when (mode) {
                    "exact" -> candidate.equals(text, ignoreCase = ignoreCase)
                    "regex" -> regex?.containsMatchIn(candidate) == true
                    else -> candidate.contains(text, ignoreCase = ignoreCase)
                }
            }
        }
    }

    private fun findViewIdNode(viewId: String): AccessibilityNodeInfo? {
        if (viewId.isBlank()) return null
        val root = rootInActiveWindow ?: return null
        return runCatching { root.findAccessibilityNodeInfosByViewId(viewId).orEmpty().firstOrNull() }.getOrNull()
    }

    private fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun clickNearest(start: AccessibilityNodeInfo): Boolean =
        performNearest(start, AccessibilityNodeInfo.ACTION_CLICK)

    private fun performNearest(start: AccessibilityNodeInfo, action: Int): Boolean {
        var node: AccessibilityNodeInfo? = start
        var depth = 0
        while (node != null && depth++ < 32) {
            if (node.actionList.any { it.id == action } && node.performAction(action)) return true
            node = node.parent
        }
        return false
    }

    private fun walkActiveWindow(): Sequence<AccessibilityNodeInfo> =
        rootInActiveWindow?.let(::walk) ?: emptySequence()

    private fun walk(root: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var visited = 0
        while (stack.isNotEmpty() && visited++ < MAX_WALK_NODES) {
            val node = stack.removeLast()
            yield(node)
            for (index in node.childCount - 1 downTo 0) node.getChild(index)?.let(stack::add)
        }
    }

    private suspend fun gesture(path: Path, durationMs: Long): Boolean = suspendCancellableCoroutine { continuation ->
        val description = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val accepted = dispatchGesture(
            description,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            },
            null,
        )
        if (!accepted && continuation.isActive) continuation.resume(false)
    }

    companion object {
        @Volatile
        internal var current: YAutoAccessibilityService? = null

        private const val MAX_WALK_NODES = 10_000
        private const val MAX_NODE_LIMIT = 2_000
        private const val EVENT_SCREEN_TEXT_NODE_LIMIT = 500
        private const val MAX_EVENT_TEXT = 2_000
        private const val MAX_SCREEN_TEXT = 32_000
        private const val MAX_VIEW_ID = 1_000
    }
}
