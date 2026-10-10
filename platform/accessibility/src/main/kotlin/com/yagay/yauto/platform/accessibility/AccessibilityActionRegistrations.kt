package com.yagay.yauto.platform.accessibility

import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.capability.CapabilityResult
import com.yagay.yauto.core.capability.SystemOperations
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*


internal fun AccessibilityFeaturePack.registerAccessibilityActions(registry: FeatureRegistry) {
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

        registerDualMethodGlobalAction(registry)

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("accessibility.scroll_to"), FeatureKind.ACTION,
                "Scroll view to location",
                "Scroll the active Accessibility scrollable view toward top, bottom, forward or backward",
                FeatureCategory.UI_AUTOMATION,
                capabilities = setOf(CapabilityIds.ACCESSIBILITY),
                fields = listOf(
                    FieldSchema.Choice(
                        "location", "Location", true,
                        listOf("top", "bottom", "top_force", "bottom_force", "forward", "backward")
                    )
                ),
                keywords = setOf("scroll to", "top", "bottom", "shortx", "accessibility"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            val service = YAutoAccessibilityService.current ?: return@registerAction ActionExecutionResult(false)
            ActionExecutionResult(service.scrollToLocation(feature.config.string("location", "forward")))
        }

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
}
