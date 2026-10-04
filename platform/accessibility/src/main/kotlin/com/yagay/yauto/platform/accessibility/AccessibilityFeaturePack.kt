package com.yagay.yauto.platform.accessibility

import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

class AccessibilityFeaturePack(
    private val fallbackForeground: (() -> AccessibilityWindowSnapshot?)? = null,
) : FeaturePack {
    override val id: String = "accessibility.actions"

    override fun install(registry: FeatureRegistry) {
        action(registry, AccessibilityOperations.CLICK_TEXT, "Click text", "Find visible text in the active window and click the nearest clickable node", listOf(FieldSchema.Text("text", "Text", true), FieldSchema.Toggle("exact", "Exact text match")), setOf("click", "text", "ui", "accessibility"))
        action(registry, AccessibilityOperations.CLICK_TEXT_ADVANCED, "Click text with match mode", "Find visible text using contains, exact or regular-expression matching and click it", listOf(FieldSchema.Text("text", "Text / pattern", true), FieldSchema.Choice("mode", "Match mode", true, listOf("contains", "exact", "regex")), FieldSchema.Toggle("ignoreCase", "Ignore case")), setOf("click", "text", "regex", "screen", "shortx"))
        action(registry, AccessibilityOperations.LONG_CLICK_TEXT, "Long-click text", "Find visible text and perform the nearest supported long-click action", listOf(FieldSchema.Text("text", "Text", true), FieldSchema.Toggle("exact", "Exact text match")), setOf("long click", "long press", "text", "ui"))
        action(registry, AccessibilityOperations.CLICK_VIEW_ID, "Click View ID", "Find a view by resource ID and click the nearest clickable node", listOf(FieldSchema.Text("viewId", "View ID", true)), setOf("view id", "resource id", "ui", "accessibility"))
        action(registry, AccessibilityOperations.LONG_CLICK_VIEW_ID, "Long-click View ID", "Long-click a node found by resource ID", listOf(FieldSchema.Text("viewId", "View ID", true)), setOf("view id", "long press", "shortx", "accessibility"))
        action(registry, AccessibilityOperations.FOCUS_VIEW_ID, "Focus View ID", "Move Accessibility focus to a node found by resource ID", listOf(FieldSchema.Text("viewId", "View ID", true)), setOf("focus", "view id", "accessibility"))
        action(registry, AccessibilityOperations.CLEAR_TEXT_VIEW_ID, "Clear text by View ID", "Clear text from an editable node found by resource ID", listOf(FieldSchema.Text("viewId", "View ID", true)), setOf("clear text", "view id", "input"))
        action(registry, AccessibilityOperations.SCROLL_VIEW_ID, "Scroll View ID", "Scroll a specific view by resource ID", listOf(FieldSchema.Text("viewId", "View ID", true), FieldSchema.Choice("direction", "Direction", true, listOf("forward", "backward", "up", "down", "left", "right"))), setOf("scroll", "view id", "accessibility"))
        action(registry, AccessibilityOperations.SELECT_ALL_VIEW_ID, "Select all text by View ID", "Select the full text range of an editable node", listOf(FieldSchema.Text("viewId", "View ID", true)), setOf("select all", "view id", "text"))
        action(registry, AccessibilityOperations.COPY_VIEW_ID, "Copy by View ID", "Invoke Accessibility copy on a node found by resource ID", listOf(FieldSchema.Text("viewId", "View ID", true)), setOf("copy", "view id", "clipboard"))
        action(registry, AccessibilityOperations.CUT_VIEW_ID, "Cut by View ID", "Invoke Accessibility cut on a node found by resource ID", listOf(FieldSchema.Text("viewId", "View ID", true)), setOf("cut", "view id", "clipboard"))
        action(registry, AccessibilityOperations.PASTE_VIEW_ID, "Paste by View ID", "Invoke Accessibility paste on a node found by resource ID", listOf(FieldSchema.Text("viewId", "View ID", true)), setOf("paste", "view id", "clipboard"))
        action(registry, AccessibilityOperations.CLICK_DESCRIPTION, "Click content description", "Find a node by accessibility content description and click it", listOf(FieldSchema.Text("description", "Content description", true), FieldSchema.Toggle("exact", "Exact match")), setOf("content description", "accessibility label", "click", "ui"))
        action(registry, AccessibilityOperations.INPUT_TEXT, "Input text", "Set text on the currently focused editable accessibility node", listOf(FieldSchema.Text("text", "Text", true, multiline = true)), setOf("input", "type", "text", "accessibility"))
        action(registry, AccessibilityOperations.INPUT_TEXT_VIEW_ID, "Set text by View ID", "Set text directly on an editable node found by resource ID", listOf(FieldSchema.Text("viewId", "View ID", true), FieldSchema.Text("text", "Text", true, multiline = true)), setOf("input", "view id", "set text", "ui"))

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("accessibility.recents.show"), FeatureKind.ACTION,
                "Show recent apps",
                "Open Android's system recent-apps overview using Accessibility",
                FeatureCategory.UI_AUTOMATION,
                capabilities = setOf(CapabilityIds.ACCESSIBILITY),
                keywords = setOf("recents", "recent apps", "overview", "shortx", "tasker"),
                ownerPackId = id,
            )
        ) { _, ctx ->
            val result = ctx.capabilities.execute(
                CapabilityRequest(
                    CapabilityIds.ACCESSIBILITY,
                    AccessibilityOperations.GLOBAL_ACTION,
                    mapOf("action" to ConfigValue.StringValue("recents")),
                )
            )
            ActionExecutionResult(result.success, result.value, result.message)
        }

        action(registry, AccessibilityOperations.GLOBAL_ACTION, "Global UI action", "Perform an Android accessibility global action", listOf(FieldSchema.Choice("action", "Action", true, listOf("back", "home", "recents", "notifications", "quick_settings", "power_dialog", "lock_screen"))), setOf("back", "home", "recents", "quick settings", "accessibility"))
        action(registry, AccessibilityOperations.SCROLL, "Scroll UI", "Scroll the first suitable scrollable node in the active window", listOf(FieldSchema.Choice("direction", "Direction", true, listOf("forward", "backward", "up", "down", "left", "right"))), setOf("scroll", "page", "ui", "swipe"))
        action(registry, AccessibilityOperations.TAP, "Tap coordinates", "Dispatch a tap gesture at screen coordinates", listOf(FieldSchema.Number("x", "X", true, min = 0.0), FieldSchema.Number("y", "Y", true, min = 0.0), FieldSchema.Duration("durationMs", "Press duration")), setOf("tap", "gesture", "coordinate", "accessibility"))
        action(registry, AccessibilityOperations.SWIPE, "Swipe", "Dispatch a swipe gesture between screen coordinates", listOf(FieldSchema.Number("x1", "Start X", true, min = 0.0), FieldSchema.Number("y1", "Start Y", true, min = 0.0), FieldSchema.Number("x2", "End X", true, min = 0.0), FieldSchema.Number("y2", "End Y", true, min = 0.0), FieldSchema.Duration("durationMs", "Duration")), setOf("swipe", "gesture", "accessibility"))
        action(registry, AccessibilityOperations.GESTURE_PATH, "Recorded gesture path", "Replay a multi-point gesture path; enter one X,Y coordinate per line", listOf(FieldSchema.Text("path", "Gesture points", true, multiline = true), FieldSchema.Duration("durationMs", "Duration")), setOf("gesture", "recorded gesture", "path", "shortx", "accessibility"))


        registry.registerAction(
            FeatureDescriptor(
                FeatureId("accessibility.screen_color.find_and_click"), FeatureKind.ACTION,
                "Find screen color and click",
                "Capture the current screen, find the first sampled pixel near a target RGB color, and tap it",
                FeatureCategory.UI_AUTOMATION,
                capabilities = setOf(CapabilityIds.ACCESSIBILITY),
                fields = listOf(
                    FieldSchema.Text("color", "Target color (#RRGGBB)", true),
                    FieldSchema.Number("tolerance", "Per-channel tolerance", min = 0.0, max = 255.0),
                    FieldSchema.Number("step", "Search step pixels", min = 1.0, max = 32.0),
                    FieldSchema.Duration("tapDurationMs", "Tap duration"),
                ),
                keywords = setOf("find color", "click color", "screen pixel", "shortx"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            val service = YAutoAccessibilityService.current ?: return@registerAction ActionExecutionResult(false)
            val target = runCatching { android.graphics.Color.parseColor(feature.config.string("color").trim()) }.getOrNull()
                ?: return@registerAction ActionExecutionResult(false)
            val tolerance = ((feature.config["tolerance"] as? ConfigValue.NumberValue)?.value ?: 16.0).toInt().coerceIn(0, 255)
            val step = ((feature.config["step"] as? ConfigValue.NumberValue)?.value ?: 2.0).toInt().coerceIn(1, 32)
            val bitmap = service.captureScreenshotBitmap() ?: return@registerAction ActionExecutionResult(false)
            var matchX = -1
            var matchY = -1
            try {
                var y = 0
                outer@ while (y < bitmap.height) {
                    var x = 0
                    while (x < bitmap.width) {
                        val color = bitmap.getPixel(x, y)
                        if (
                            kotlin.math.abs(android.graphics.Color.red(color) - android.graphics.Color.red(target)) <= tolerance &&
                            kotlin.math.abs(android.graphics.Color.green(color) - android.graphics.Color.green(target)) <= tolerance &&
                            kotlin.math.abs(android.graphics.Color.blue(color) - android.graphics.Color.blue(target)) <= tolerance
                        ) {
                            matchX = x
                            matchY = y
                            break@outer
                        }
                        x += step
                    }
                    y += step
                }
            } finally {
                bitmap.recycle()
            }
            if (matchX < 0 || matchY < 0) return@registerAction ActionExecutionResult(false)
            val ok = service.tap(
                matchX.toFloat(),
                matchY.toFloat(),
                feature.config.long("tapDurationMs", 40L),
            )
            ActionExecutionResult(
                ok,
                ConfigValue.ObjectValue(
                    mapOf(
                        "x" to ConfigValue.NumberValue(matchX.toDouble()),
                        "y" to ConfigValue.NumberValue(matchY.toDouble()),
                    )
                )
            )
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("accessibility.context_menu.perform"), FeatureKind.ACTION,
                "Perform focused text context action",
                "Perform select-all, copy, cut, paste or clear on the currently focused editable Accessibility node",
                FeatureCategory.UI_AUTOMATION,
                capabilities = setOf(CapabilityIds.ACCESSIBILITY),
                fields = listOf(
                    FieldSchema.Choice("action", "Action", true, listOf("select_all", "copy", "cut", "paste", "clear")),
                ),
                keywords = setOf("context menu", "copy", "paste", "select all", "shortx"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            val service = YAutoAccessibilityService.current ?: return@registerAction ActionExecutionResult(false)
            ActionExecutionResult(service.performFocusedContextAction(feature.config.string("action")))
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("accessibility.capture_next_click"), FeatureKind.ACTION,
                "Capture next click coordinates",
                "Wait for the next Accessibility click and store its screen coordinates, bounds and source metadata",
                FeatureCategory.UI_AUTOMATION,
                capabilities = setOf(CapabilityIds.ACCESSIBILITY),
                fields = listOf(
                    FieldSchema.Duration("timeoutMs", "Timeout"),
                    FieldSchema.Variable("resultVariable", "Store click object", true),
                ),
                keywords = setOf("capture click", "xy", "coordinates", "touch", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val snapshot = AccessibilityRuntimeBridge.awaitNextClick(feature.config.long("timeoutMs", 30_000L))
                ?: return@registerAction ActionExecutionResult(false)
            val value = ConfigValue.ObjectValue(
                mapOf(
                    "package" to ConfigValue.StringValue(snapshot.packageName),
                    "class" to ConfigValue.StringValue(snapshot.className.orEmpty()),
                    "text" to ConfigValue.StringValue(snapshot.text),
                    "viewId" to ConfigValue.StringValue(snapshot.viewId),
                    "x" to ConfigValue.NumberValue(snapshot.centerX.toDouble()),
                    "y" to ConfigValue.NumberValue(snapshot.centerY.toDouble()),
                    "left" to ConfigValue.NumberValue(snapshot.left.toDouble()),
                    "top" to ConfigValue.NumberValue(snapshot.top.toDouble()),
                    "right" to ConfigValue.NumberValue(snapshot.right.toDouble()),
                    "bottom" to ConfigValue.NumberValue(snapshot.bottom.toDouble()),
                )
            )
            feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, value) }
            ActionExecutionResult(true, value)
        }

        resultAction(
            registry,
            AccessibilityOperations.GET_SCREEN_TEXT,
            "Read screen text",
            "Read visible text and content descriptions from the active Accessibility window",
            listOf(
                FieldSchema.Toggle("includeDescriptions", "Include content descriptions"),
                FieldSchema.Toggle("unique", "Remove duplicate text"),
                FieldSchema.Number("limit", "Maximum nodes", min = 1.0, max = 2000.0),
                FieldSchema.Variable("resultVariable", "Store screen text", true),
            ),
            setOf("screen text", "read screen", "accessibility", "shortx", "macrodroid"),
        )
        resultAction(
            registry,
            AccessibilityOperations.GET_VIEW_TEXT,
            "Read text by View ID",
            "Read text or content description from a view resource ID",
            listOf(
                FieldSchema.Text("viewId", "View ID", true),
                FieldSchema.Variable("resultVariable", "Store text", true),
            ),
            setOf("view id", "get text", "screen", "accessibility"),
        )
        resultAction(
            registry,
            AccessibilityOperations.CAPTURE_SCREENSHOT,
            "Capture screenshot / area",
            "Capture the screen through Accessibility and optionally crop a rectangular area to PNG",
            listOf(
                FieldSchema.Text("fileName", "PNG file name"),
                FieldSchema.Number("x", "Left X", min = 0.0),
                FieldSchema.Number("y", "Top Y", min = 0.0),
                FieldSchema.Number("width", "Width", min = 1.0),
                FieldSchema.Number("height", "Height", min = 1.0),
                FieldSchema.Variable("resultVariable", "Store screenshot path", true),
            ),
            setOf("screenshot", "area screenshot", "crop", "shortx", "accessibility"),
        )
        resultAction(
            registry,
            AccessibilityOperations.GET_VIEW_BOUNDS,
            "Get View bounds",
            "Return screen bounds and center coordinates for a view resource ID",
            listOf(
                FieldSchema.Text("viewId", "View ID", true),
                FieldSchema.Variable("resultVariable", "Store bounds object", true),
            ),
            setOf("view bounds", "coordinates", "view id", "accessibility"),
        )
        resultAction(
            registry,
            AccessibilityOperations.GET_UI_NODES,
            "Read UI node tree",
            "Read visible Accessibility nodes and their text, IDs and interaction flags into a list",
            listOf(
                FieldSchema.Number("limit", "Maximum nodes", min = 1.0, max = 2000.0),
                FieldSchema.Toggle("onlyVisible", "Only visible nodes"),
                FieldSchema.Toggle("clickableOnly", "Only clickable nodes"),
                FieldSchema.Variable("resultVariable", "Store node list", true),
            ),
            setOf("ui tree", "nodes", "view id", "screen contents", "accessibility"),
        )


        registry.registerCondition(
            FeatureDescriptor(
                FeatureId("accessibility.condition.screen_color_found"), FeatureKind.CONDITION,
                "Screen color found",
                "Capture the current screen through Accessibility and check whether a target RGB color exists within tolerance",
                FeatureCategory.UI_AUTOMATION,
                capabilities = setOf(CapabilityIds.ACCESSIBILITY),
                fields = listOf(
                    FieldSchema.Text("color", "Target color (#RRGGBB)", true),
                    FieldSchema.Number("tolerance", "Per-channel tolerance", min = 0.0, max = 255.0),
                    FieldSchema.Number("step", "Search step pixels", min = 1.0, max = 32.0),
                ),
                keywords = setOf("screen color", "pixel", "find points", "shortx", "screenshot"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            val service = YAutoAccessibilityService.current ?: return@registerCondition false
            val target = runCatching {
                android.graphics.Color.parseColor(feature.config.string("color").trim())
            }.getOrNull() ?: return@registerCondition false
            val tolerance = ((feature.config["tolerance"] as? ConfigValue.NumberValue)?.value ?: 16.0)
                .toInt().coerceIn(0, 255)
            val step = ((feature.config["step"] as? ConfigValue.NumberValue)?.value ?: 2.0)
                .toInt().coerceIn(1, 32)
            val bitmap = service.captureScreenshotBitmap() ?: return@registerCondition false
            try {
                var found = false
                var y = 0
                while (y < bitmap.height && !found) {
                    var x = 0
                    while (x < bitmap.width) {
                        val color = bitmap.getPixel(x, y)
                        if (
                            kotlin.math.abs(android.graphics.Color.red(color) - android.graphics.Color.red(target)) <= tolerance &&
                            kotlin.math.abs(android.graphics.Color.green(color) - android.graphics.Color.green(target)) <= tolerance &&
                            kotlin.math.abs(android.graphics.Color.blue(color) - android.graphics.Color.blue(target)) <= tolerance
                        ) {
                            found = true
                            break
                        }
                        x += step
                    }
                    y += step
                }
                found
            } finally {
                bitmap.recycle()
            }
        }

        condition(registry, "accessibility.condition.text_present", AccessibilityOperations.FIND_TEXT, "Text on screen", "Check whether text/content description is currently visible", listOf(FieldSchema.Text("text", "Text", true), FieldSchema.Toggle("exact", "Exact text match")), setOf("text present", "screen text", "ui", "accessibility"))
        condition(registry, "accessibility.condition.text_matches", AccessibilityOperations.FIND_TEXT_ADVANCED, "Screen text matches", "Check visible text using contains, exact or regular-expression matching", listOf(FieldSchema.Text("text", "Text / pattern", true), FieldSchema.Choice("mode", "Match mode", true, listOf("contains", "exact", "regex")), FieldSchema.Toggle("ignoreCase", "Ignore case")), setOf("text present", "regex", "screen content", "shortx"))
        condition(registry, "accessibility.condition.view_id_present", AccessibilityOperations.FIND_VIEW_ID, "View ID on screen", "Check whether a resource ID exists in the active window", listOf(FieldSchema.Text("viewId", "View ID", true)), setOf("view id", "resource id", "exists", "ui"))

        foregroundEvent(registry, "android.event.app_foreground", "App became foreground")
        foregroundEvent(registry, "android.event.app_background", "App left foreground")
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.window_changed"), FeatureKind.EVENT, "Foreground window changed",
                "Run whenever Accessibility reports a different foreground package or Activity", FeatureCategory.APP,
                capabilities = setOf(CapabilityIds.ACCESSIBILITY),
                fields = listOf(FieldSchema.AppPicker("package", "App / package"), FieldSchema.Text("classContains", "Activity / class contains")),
                keywords = setOf("foreground", "activity", "window", "app"), ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.window_changed") return@registerEvent false
            matchForeground(feature, ctx.event.payload.string("package"), ctx.event.payload.string("class"))
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.toast_shown"), FeatureKind.EVENT,
                "Toast / transient message shown",
                "Trigger when Accessibility reports a transient notification-state message such as an app Toast",
                FeatureCategory.UI_AUTOMATION,
                capabilities = setOf(CapabilityIds.ACCESSIBILITY),
                fields = listOf(
                    FieldSchema.AppPicker("package", "App / package"),
                    FieldSchema.Choice("mode", "Text match", options = listOf("any", "contains", "exact", "regex")),
                    FieldSchema.Text("text", "Text / pattern"),
                    FieldSchema.Toggle("ignoreCase", "Ignore case"),
                ),
                keywords = setOf("toast", "transient", "message", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.toast_shown") return@registerEvent false
            val pkg = feature.config.string("package")
            if (pkg.isNotBlank() && ctx.event.payload.string("package") != pkg) return@registerEvent false
            val mode = feature.config.string("mode", "any")
            mode == "any" || matchesText(
                ctx.event.payload.string("text"),
                feature.config.string("text"),
                mode,
                feature.config.boolean("ignoreCase", true),
            )
        }

        uiEvent(registry, "android.event.ui_click", "UI element clicked", "click")
        uiEvent(registry, "android.event.ui_long_click", "UI element long-clicked", "long_click")
        uiEvent(registry, "android.event.ui_text_changed", "UI text changed", "text_changed")
        uiEvent(registry, "android.event.ui_focused", "UI element focused", "focused")
        uiEvent(registry, "android.event.ui_scrolled", "UI content scrolled", "scrolled")
        screenContentEvent(registry)
        screenTextAppearedEvent(registry)
        fingerprintGestureEvent(registry)
        fingerprintGestureState(registry, FeatureKind.STATE, "android.state.fingerprint_gesture_available")
        fingerprintGestureState(registry, FeatureKind.CONDITION, "android.condition.fingerprint_gesture_available")

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("accessibility.current_window.get"), FeatureKind.ACTION,
                "Get current app / Activity",
                "Return the current Accessibility foreground package and Activity/class name",
                FeatureCategory.APP,
                capabilities = setOf(CapabilityIds.ACCESSIBILITY),
                fields = listOf(FieldSchema.Variable("resultVariable", "Store window object", true)),
                keywords = setOf("current activity", "current app", "foreground", "shortx", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val current = AccessibilityRuntimeBridge.currentWindow()
                ?: return@registerAction ActionExecutionResult(false)
            val value = ConfigValue.ObjectValue(
                mapOf(
                    "package" to ConfigValue.StringValue(current.packageName),
                    "class" to ConfigValue.StringValue(current.className.orEmpty()),
                    "timestamp" to ConfigValue.NumberValue(current.timestampEpochMs.toDouble()),
                )
            )
            feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, value) }
            ActionExecutionResult(true, value)
        }

        foregroundState(registry, FeatureKind.STATE, "android.state.app_foreground")
        foregroundState(registry, FeatureKind.CONDITION, "android.condition.app_foreground")
        keyboardState(registry, FeatureKind.STATE, "android.state.keyboard_visible")
        keyboardState(registry, FeatureKind.CONDITION, "android.condition.keyboard_visible")
    }

    private fun foregroundEvent(registry: FeatureRegistry, typeId: String, title: String) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId(typeId), FeatureKind.EVENT, title,
                "Match application foreground transitions from Accessibility with Usage Access fallback",
                FeatureCategory.APP,
                fields = listOf(FieldSchema.AppPicker("package", "App / package", true), FieldSchema.Text("classContains", "Activity / class contains")),
                keywords = setOf("foreground", "background", "app", "activity", "usage stats"), ownerPackId = id,
                implementationOptions = foregroundImplementationOptions(),
            )
        ) { feature, ctx ->
            ctx.event.typeId == typeId &&
                foregroundSourceMatches(feature, ctx.event.source) &&
                matchForeground(feature, ctx.event.payload.string("package"), ctx.event.payload.string("class"))
        }
    }

    private fun uiEvent(registry: FeatureRegistry, typeId: String, title: String, eventName: String) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId(typeId),
                FeatureKind.EVENT,
                title,
                "Trigger on Accessibility UI interaction events and optionally filter app, text, content description or View ID",
                FeatureCategory.UI_AUTOMATION,
                capabilities = setOf(CapabilityIds.ACCESSIBILITY),
                fields = listOf(
                    FieldSchema.AppPicker("package", "App / package"),
                    FieldSchema.Choice("textMode", "Text match", options = listOf("any", "contains", "exact", "regex")),
                    FieldSchema.Text("text", "Text / pattern"),
                    FieldSchema.Text("descriptionContains", "Content description contains"),
                    FieldSchema.Text("viewIdContains", "View ID contains"),
                    FieldSchema.Toggle("ignoreCase", "Ignore case"),
                ),
                keywords = setOf("ui click", "accessibility event", "view", eventName, "shortx", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != typeId) return@registerEvent false
            matchUiEvent(feature, ctx.event.payload)
        }
    }

    private fun screenContentEvent(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.screen_content_changed"),
                FeatureKind.EVENT,
                "Screen content matched",
                "Trigger when Accessibility reports changed screen content matching text or a regular expression",
                FeatureCategory.UI_AUTOMATION,
                capabilities = setOf(CapabilityIds.ACCESSIBILITY),
                fields = listOf(
                    FieldSchema.AppPicker("package", "App / package"),
                    FieldSchema.Choice("mode", "Match mode", true, listOf("contains", "regex")),
                    FieldSchema.Text("text", "Text / pattern", true),
                    FieldSchema.Toggle("ignoreCase", "Ignore case"),
                ),
                keywords = setOf("screen content", "text appeared", "regex", "accessibility", "macrodroid", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.screen_content_changed") return@registerEvent false
            val pkg = feature.config.string("package")
            if (pkg.isNotBlank() && ctx.event.payload.string("package") != pkg) return@registerEvent false
            matchesText(
                actual = ctx.event.payload.string("screenText"),
                expected = feature.config.string("text"),
                mode = feature.config.string("mode", "contains"),
                ignoreCase = feature.config.boolean("ignoreCase", true),
            )
        }
    }

    private fun screenTextAppearedEvent(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.screen_text_appeared"), FeatureKind.EVENT,
                "Screen text appeared", "Trigger when changed Accessibility screen content contains matching text",
                FeatureCategory.UI_AUTOMATION,
                capabilities = setOf(CapabilityIds.ACCESSIBILITY),
                fields = listOf(
                    FieldSchema.AppPicker("package", "App / package"),
                    FieldSchema.Choice("mode", "Match mode", true, listOf("contains", "exact", "regex")),
                    FieldSchema.Text("text", "Text / pattern", true),
                    FieldSchema.Toggle("ignoreCase", "Ignore case"),
                ),
                keywords = setOf("screen text", "appeared", "content", "accessibility", "visual"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.screen_content_changed") return@registerEvent false
            val pkg = feature.config.string("package")
            if (pkg.isNotBlank() && ctx.event.payload.string("package") != pkg) return@registerEvent false
            matchesText(
                actual = ctx.event.payload.string("screenText"),
                expected = feature.config.string("text"),
                mode = feature.config.string("mode", "contains"),
                ignoreCase = feature.config.boolean("ignoreCase", true),
            )
        }
    }

    private fun fingerprintGestureEvent(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.fingerprint_gesture"), FeatureKind.EVENT,
                "Fingerprint gesture", "Run when Accessibility detects a fingerprint-sensor swipe gesture",
                FeatureCategory.UI_AUTOMATION,
                capabilities = setOf(CapabilityIds.ACCESSIBILITY),
                fields = listOf(
                    FieldSchema.Choice("gesture", "Gesture", true, listOf("any", "swipe_up", "swipe_down", "swipe_left", "swipe_right")),
                ),
                keywords = setOf("fingerprint", "gesture", "swipe", "accessibility"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.fingerprint_gesture") return@registerEvent false
            val expected = feature.config.string("gesture", "any")
            expected == "any" || ctx.event.payload.string("gesture") == expected
        }
    }

    private fun fingerprintGestureState(registry: FeatureRegistry, kind: FeatureKind, typeId: String) {
        val descriptor = FeatureDescriptor(
            FeatureId(typeId), kind,
            "Fingerprint gestures available", "Check whether Accessibility can currently receive fingerprint gestures",
            FeatureCategory.UI_AUTOMATION,
            capabilities = setOf(CapabilityIds.ACCESSIBILITY),
            fields = listOf(FieldSchema.Toggle("value", "Available")),
            keywords = setOf("fingerprint", "gesture", "available"),
            ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, _ ->
            AccessibilityRuntimeBridge.isFingerprintGestureAvailable() == feature.config.boolean("value", true)
        }
        if (kind == FeatureKind.STATE) registry.registerState(descriptor, evaluator)
        else registry.registerCondition(descriptor, evaluator)
    }

    private fun matchUiEvent(feature: com.yagay.yauto.core.model.FeatureRef, payload: Map<String, ConfigValue>): Boolean {
        val pkg = feature.config.string("package")
        if (pkg.isNotBlank() && payload.string("package") != pkg) return false
        val ignoreCase = feature.config.boolean("ignoreCase", true)
        val mode = feature.config.string("textMode", "any")
        if (mode != "any" && !matchesText(payload.string("text"), feature.config.string("text"), mode, ignoreCase)) return false
        val description = feature.config.string("descriptionContains")
        if (description.isNotBlank() && !payload.string("description").contains(description, ignoreCase)) return false
        val viewId = feature.config.string("viewIdContains")
        if (viewId.isNotBlank() && !payload.string("viewId").contains(viewId, ignoreCase)) return false
        return true
    }

    private fun keyboardState(registry: FeatureRegistry, kind: FeatureKind, typeId: String) {
        val descriptor = FeatureDescriptor(
            FeatureId(typeId),
            kind,
            "Software keyboard visible",
            "Check whether Accessibility currently exposes an input-method window",
            FeatureCategory.UI_AUTOMATION,
            capabilities = setOf(CapabilityIds.ACCESSIBILITY),
            fields = listOf(FieldSchema.Toggle("value", "Visible")),
            keywords = setOf("keyboard", "ime", "input method", "soft keyboard", "macrodroid"),
            ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, ctx ->
            val result = ctx.capabilities.execute(
                CapabilityRequest(CapabilityIds.ACCESSIBILITY, AccessibilityOperations.KEYBOARD_VISIBLE)
            )
            result.success &&
                ((result.value as? ConfigValue.BooleanValue)?.value == feature.config.boolean("value", true))
        }
        if (kind == FeatureKind.STATE) registry.registerState(descriptor, evaluator)
        else registry.registerCondition(descriptor, evaluator)
    }

    private fun foregroundState(registry: FeatureRegistry, kind: FeatureKind, typeId: String) {
        val descriptor = FeatureDescriptor(
            FeatureId(typeId), kind, "App in foreground",
            "Check the current foreground application using Accessibility with Usage Access fallback",
            FeatureCategory.APP,
            fields = listOf(FieldSchema.AppPicker("package", "App / package", true), FieldSchema.Text("classContains", "Activity / class contains")),
            keywords = setOf("foreground", "current app", "activity", "usage stats"), ownerPackId = id,
            implementationOptions = foregroundImplementationOptions(),
        )
        val evaluator = ConditionEvaluator { feature, _ ->
            val current = currentForeground(feature) ?: return@ConditionEvaluator false
            matchForeground(feature, current.packageName, current.className.orEmpty())
        }
        if (kind == FeatureKind.STATE) registry.registerState(descriptor, evaluator) else registry.registerCondition(descriptor, evaluator)
    }

    private fun foregroundImplementationOptions(): List<FeatureImplementationOption> = listOf(
        FeatureImplementationOption("accessibility", setOf(AccessRequirement.ACCESSIBILITY)),
        FeatureImplementationOption("usage_stats", setOf(AccessRequirement.USAGE_STATS)),
    )

    private fun currentForeground(feature: com.yagay.yauto.core.model.FeatureRef): AccessibilityWindowSnapshot? =
        when (feature.preferredBackendId()) {
            "accessibility" -> AccessibilityRuntimeBridge.currentWindow()
            "usage_stats" -> fallbackForeground?.invoke()
            else -> AccessibilityRuntimeBridge.currentWindow() ?: fallbackForeground?.invoke()
        }

    private fun foregroundSourceMatches(feature: com.yagay.yauto.core.model.FeatureRef, source: String): Boolean =
        when (feature.preferredBackendId()) {
            "accessibility" -> source.isBlank() || source == ACCESSIBILITY_WINDOW_SOURCE
            "usage_stats" -> source == USAGE_STATS_SOURCE
            else -> true
        }

    private fun matchForeground(feature: com.yagay.yauto.core.model.FeatureRef, pkg: String, className: String): Boolean {
        val expectedPackage = feature.config.string("package").trim()
        val classContains = feature.config.string("classContains").trim()
        return (expectedPackage.isBlank() || pkg == expectedPackage) &&
            (classContains.isBlank() || className.contains(classContains, ignoreCase = true))
    }

    private fun action(registry: FeatureRegistry, typeId: String, title: String, description: String, fields: List<FieldSchema>, keywords: Set<String>) {
        registry.registerAction(
            FeatureDescriptor(id = FeatureId(typeId), kind = FeatureKind.ACTION, title = title, description = description, category = FeatureCategory.UI_AUTOMATION, capabilities = setOf(CapabilityIds.ACCESSIBILITY), fields = fields, keywords = keywords, ownerPackId = id)
        ) { feature, ctx ->
            val result = ctx.capabilities.execute(CapabilityRequest(CapabilityIds.ACCESSIBILITY, typeId, feature.config))
            ActionExecutionResult(result.success, result.value, result.message)
        }
    }

    private fun resultAction(registry: FeatureRegistry, typeId: String, title: String, description: String, fields: List<FieldSchema>, keywords: Set<String>) {
        registry.registerAction(
            FeatureDescriptor(id = FeatureId(typeId), kind = FeatureKind.ACTION, title = title, description = description, category = FeatureCategory.UI_AUTOMATION, capabilities = setOf(CapabilityIds.ACCESSIBILITY), fields = fields, keywords = keywords, ownerPackId = id)
        ) { feature, ctx ->
            val result = ctx.capabilities.execute(CapabilityRequest(CapabilityIds.ACCESSIBILITY, typeId, feature.config))
            if (result.success) {
                feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, result.value) }
            }
            ActionExecutionResult(result.success, result.value, result.message)
        }
    }

    private fun condition(registry: FeatureRegistry, typeId: String, operationId: String, title: String, description: String, fields: List<FieldSchema>, keywords: Set<String>) {
        registry.registerCondition(
            FeatureDescriptor(id = FeatureId(typeId), kind = FeatureKind.CONDITION, title = title, description = description, category = FeatureCategory.UI_AUTOMATION, capabilities = setOf(CapabilityIds.ACCESSIBILITY), fields = fields, keywords = keywords, ownerPackId = id)
        ) { feature, ctx ->
            val result = ctx.capabilities.execute(CapabilityRequest(CapabilityIds.ACCESSIBILITY, operationId, feature.config))
            result.success && (result.value as? ConfigValue.BooleanValue)?.value == true
        }
    }

    private fun matchesText(actual: String, expected: String, mode: String, ignoreCase: Boolean): Boolean {
        if (expected.isBlank()) return false
        return when (mode) {
            "exact" -> actual.equals(expected, ignoreCase)
            "regex" -> runCatching {
                Regex(expected, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()).containsMatchIn(actual)
            }.getOrDefault(false)
            else -> actual.contains(expected, ignoreCase)
        }
    }

    private companion object {
        const val ACCESSIBILITY_WINDOW_SOURCE = "accessibility.window"
        const val USAGE_STATS_SOURCE = "android.usage.foreground"
    }
}
