package com.yagay.yauto.platform.accessibility

import com.yagay.yauto.core.capability.CapabilityBackend
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.capability.CapabilityResult
import com.yagay.yauto.core.capability.RuntimeEnvironment
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.listOrEmpty
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AccessibilityBackend : CapabilityBackend {
    override val id: String = "accessibility"
    override val priority: Int = 40

    override suspend fun isAvailable(environment: RuntimeEnvironment): Boolean =
        YAutoAccessibilityService.current != null

    override fun supports(request: CapabilityRequest, environment: RuntimeEnvironment): Boolean =
        request.capability == CapabilityIds.ACCESSIBILITY

    override suspend fun execute(request: CapabilityRequest, environment: RuntimeEnvironment): CapabilityResult =
        withContext(Dispatchers.Main.immediate) {
            val service = YAutoAccessibilityService.current
                ?: return@withContext CapabilityResult(false, message = userText("diagnostics.accessibility.not_connected"))

            when (request.operationId) {
                AccessibilityOperations.FIND_TEXT -> {
                    val found = service.hasText(
                        request.payload.string("text"),
                        request.payload.boolean("exact"),
                    )
                    return@withContext CapabilityResult(success = true, value = ConfigValue.BooleanValue(found))
                }
                AccessibilityOperations.FIND_TEXT_ADVANCED -> {
                    val found = service.matchesText(
                        request.payload.string("text"),
                        request.payload.string("mode", "contains"),
                        request.payload.boolean("ignoreCase", true),
                    )
                    return@withContext CapabilityResult(success = true, value = ConfigValue.BooleanValue(found))
                }
                AccessibilityOperations.FIND_VIEW_ID -> {
                    val found = service.hasViewId(request.payload.string("viewId"))
                    return@withContext CapabilityResult(success = true, value = ConfigValue.BooleanValue(found))
                }
                AccessibilityOperations.KEYBOARD_VISIBLE -> {
                    return@withContext CapabilityResult(
                        success = true,
                        value = ConfigValue.BooleanValue(service.keyboardVisible()),
                    )
                }
                AccessibilityOperations.GET_SCREEN_TEXT -> {
                    val value = service.screenText(
                        includeDescriptions = request.payload.boolean("includeDescriptions", true),
                        unique = request.payload.boolean("unique", true),
                        limit = request.payload.long("limit", 500).toInt(),
                    )
                    return@withContext CapabilityResult(success = true, value = ConfigValue.StringValue(value))
                }
                AccessibilityOperations.GET_VIEW_TEXT -> {
                    val value = service.textByViewId(request.payload.string("viewId"))
                        ?: return@withContext CapabilityResult(success = false, value = ConfigValue.NullValue)
                    return@withContext CapabilityResult(success = true, value = ConfigValue.StringValue(value))
                }
                AccessibilityOperations.GET_VIEW_BOUNDS -> {
                    val bounds = service.viewBounds(request.payload.string("viewId"))
                        ?: return@withContext CapabilityResult(success = false, value = ConfigValue.NullValue)
                    val output = ConfigValue.ObjectValue(
                        mapOf(
                            "left" to ConfigValue.NumberValue(bounds.left.toDouble()),
                            "top" to ConfigValue.NumberValue(bounds.top.toDouble()),
                            "right" to ConfigValue.NumberValue(bounds.right.toDouble()),
                            "bottom" to ConfigValue.NumberValue(bounds.bottom.toDouble()),
                            "width" to ConfigValue.NumberValue(bounds.width().toDouble()),
                            "height" to ConfigValue.NumberValue(bounds.height().toDouble()),
                            "centerX" to ConfigValue.NumberValue(bounds.centerX().toDouble()),
                            "centerY" to ConfigValue.NumberValue(bounds.centerY().toDouble()),
                        )
                    )
                    return@withContext CapabilityResult(success = true, value = output)
                }
                AccessibilityOperations.GESTURE_PATH -> {
                    val points = request.payload["points"].listOrEmpty().mapNotNull { item ->
                        val obj = (item as? ConfigValue.ObjectValue)?.value ?: return@mapNotNull null
                        val x = obj["x"].numberOrNull() ?: return@mapNotNull null
                        val y = obj["y"].numberOrNull() ?: return@mapNotNull null
                        if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0) return@mapNotNull null
                        x.toFloat() to y.toFloat()
                    }
                    if (points.isEmpty()) return@withContext CapabilityResult(success = false, value = ConfigValue.BooleanValue(false))
                    val completed = service.gesturePath(points, request.payload.long("durationMs", 500))
                    return@withContext CapabilityResult(success = completed, value = ConfigValue.BooleanValue(completed))
                }
                AccessibilityOperations.GET_UI_NODES -> {
                    val nodes = service.uiNodes(
                        limit = request.payload.long("limit", 500).toInt(),
                        onlyVisible = request.payload.boolean("onlyVisible", true),
                        clickableOnly = request.payload.boolean("clickableOnly", false),
                    )
                    val output = ConfigValue.ListValue(
                        nodes.map { node ->
                            ConfigValue.ObjectValue(
                                mapOf(
                                    "text" to ConfigValue.StringValue(node.text),
                                    "contentDescription" to ConfigValue.StringValue(node.contentDescription),
                                    "viewId" to ConfigValue.StringValue(node.viewId),
                                    "class" to ConfigValue.StringValue(node.className),
                                    "package" to ConfigValue.StringValue(node.packageName),
                                    "clickable" to ConfigValue.BooleanValue(node.clickable),
                                    "longClickable" to ConfigValue.BooleanValue(node.longClickable),
                                    "editable" to ConfigValue.BooleanValue(node.editable),
                                    "scrollable" to ConfigValue.BooleanValue(node.scrollable),
                                    "enabled" to ConfigValue.BooleanValue(node.enabled),
                                    "visible" to ConfigValue.BooleanValue(node.visible),
                                )
                            )
                        }
                    )
                    return@withContext CapabilityResult(success = true, value = output)
                }
            }

            val completed = when (request.operationId) {
                AccessibilityOperations.CLICK_TEXT -> service.clickText(
                    request.payload.string("text"),
                    request.payload.boolean("exact"),
                )
                AccessibilityOperations.CLICK_TEXT_ADVANCED -> service.clickTextAdvanced(
                    request.payload.string("text"),
                    request.payload.string("mode", "contains"),
                    request.payload.boolean("ignoreCase", true),
                )
                AccessibilityOperations.LONG_CLICK_TEXT -> service.longClickText(
                    request.payload.string("text"),
                    request.payload.boolean("exact"),
                )
                AccessibilityOperations.CLICK_VIEW_ID -> service.clickViewId(request.payload.string("viewId"))
                AccessibilityOperations.LONG_CLICK_VIEW_ID -> service.longClickViewId(request.payload.string("viewId"))
                AccessibilityOperations.FOCUS_VIEW_ID -> service.focusViewId(request.payload.string("viewId"))
                AccessibilityOperations.CLEAR_TEXT_VIEW_ID -> service.clearTextByViewId(request.payload.string("viewId"))
                AccessibilityOperations.SCROLL_VIEW_ID -> service.scrollViewId(
                    request.payload.string("viewId"), request.payload.string("direction", "forward")
                )
                AccessibilityOperations.SELECT_ALL_VIEW_ID -> service.selectAllByViewId(request.payload.string("viewId"))
                AccessibilityOperations.COPY_VIEW_ID -> service.copyByViewId(request.payload.string("viewId"))
                AccessibilityOperations.CUT_VIEW_ID -> service.cutByViewId(request.payload.string("viewId"))
                AccessibilityOperations.PASTE_VIEW_ID -> service.pasteByViewId(request.payload.string("viewId"))
                AccessibilityOperations.CLICK_DESCRIPTION -> service.clickDescription(
                    request.payload.string("description"),
                    request.payload.boolean("exact"),
                )
                AccessibilityOperations.INPUT_TEXT -> service.setFocusedText(request.payload.string("text"))
                AccessibilityOperations.INPUT_TEXT_VIEW_ID -> service.setTextByViewId(
                    request.payload.string("viewId"), request.payload.string("text")
                )
                AccessibilityOperations.GLOBAL_ACTION -> service.globalAction(request.payload.string("action"))
                AccessibilityOperations.SCROLL -> service.scroll(request.payload.string("direction"))
                AccessibilityOperations.TAP -> {
                    val x = request.payload["x"].numberOrNull()
                    val y = request.payload["y"].numberOrNull()
                    if (x == null || y == null || !x.isFinite() || !y.isFinite() || x < 0 || y < 0) false
                    else service.tap(x.toFloat(), y.toFloat(), request.payload.long("durationMs", 40))
                }
                AccessibilityOperations.SWIPE -> {
                    val x1 = request.payload["x1"].numberOrNull()
                    val y1 = request.payload["y1"].numberOrNull()
                    val x2 = request.payload["x2"].numberOrNull()
                    val y2 = request.payload["y2"].numberOrNull()
                    if (listOf(x1, y1, x2, y2).any { it == null || !it!!.isFinite() || it < 0 }) false
                    else service.swipe(
                        x1!!.toFloat(), y1!!.toFloat(), x2!!.toFloat(), y2!!.toFloat(),
                        request.payload.long("durationMs", 300),
                    )
                }
                else -> return@withContext CapabilityResult(false, message = userText("accessibility.unsupported_operation", request.operationId))
            }
            CapabilityResult(
                success = completed,
                value = ConfigValue.BooleanValue(completed),
                message = if (completed) null else "Accessibility operation did not complete successfully",
            )
        }
}
