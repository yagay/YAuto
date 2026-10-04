package com.yagay.yauto.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class YAutoAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        current = this
        super.onServiceConnected()
        rootInActiveWindow?.let { root ->
            AccessibilityRuntimeBridge.update(root.packageName?.toString(), root.className?.toString())
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            AccessibilityRuntimeBridge.update(event.packageName?.toString(), event.className?.toString())
        }

        val kind = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "click"
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "long_click"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "text_changed"
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> "focused"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "scrolled"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "content_changed"
            else -> null
        } ?: return

        val source = event.source
        val eventText = event.text.orEmpty().joinToString(" ") { it?.toString().orEmpty() }.trim()
        val sourceText = source?.text?.toString().orEmpty()
        val text = eventText.ifBlank { sourceText }.take(MAX_EVENT_TEXT)
        val description = source?.contentDescription?.toString().orEmpty().take(MAX_EVENT_TEXT)
        val viewId = source?.viewIdResourceName.orEmpty().take(MAX_VIEW_ID)
        val screenText = if (kind == "content_changed") {
            screenText(includeDescriptions = true, unique = true, limit = EVENT_SCREEN_TEXT_NODE_LIMIT)
                .take(MAX_SCREEN_TEXT)
        } else {
            ""
        }
        AccessibilityRuntimeBridge.dispatchUiEvent(
            AccessibilityUiEventSnapshot(
                event = kind,
                packageName = event.packageName?.toString().orEmpty(),
                className = event.className?.toString(),
                text = text,
                contentDescription = description,
                viewId = viewId,
                screenText = screenText,
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

    internal fun setFocusedText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return setNodeText(node, text)
    }

    internal fun setTextByViewId(viewId: String, text: String): Boolean =
        findViewIdNode(viewId)?.let { setNodeText(it, text) } == true

    internal fun hasText(text: String, exact: Boolean): Boolean = findTextNode(text, exact) != null
    internal fun hasViewId(viewId: String): Boolean = findViewIdNode(viewId) != null
    internal fun keyboardVisible(): Boolean = windows.orEmpty().any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }\n    internal fun matchesText(text: String, mode: String, ignoreCase: Boolean): Boolean =
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
        return values.joinToString("\n").take(MAX_SCREEN_TEXT)
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
        val regex = if (mode == "regex") runCatching {
            Regex(text, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
        }.getOrNull() ?: if (mode == "regex") return null else null
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
