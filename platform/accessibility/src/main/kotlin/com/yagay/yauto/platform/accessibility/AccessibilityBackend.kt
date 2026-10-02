package com.yagay.yauto.platform.accessibility

import com.yagay.yauto.core.capability.CapabilityBackend
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.capability.CapabilityResult
import com.yagay.yauto.core.capability.RuntimeEnvironment
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.yagay.yauto.core.model.userText

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
                ?: return@withContext CapabilityResult(false, message = userText("diagnostics.accessibility.not_connected", "YAuto Accessibility Service is not connected"))

            when (request.operationId) {
                AccessibilityOperations.FIND_TEXT -> {
                    val found = service.hasText(
                        request.payload.string("text"),
                        request.payload.boolean("exact"),
                    )
                    return@withContext CapabilityResult(success = true, value = ConfigValue.BooleanValue(found))
                }
                AccessibilityOperations.FIND_VIEW_ID -> {
                    val found = service.hasViewId(request.payload.string("viewId"))
                    return@withContext CapabilityResult(success = true, value = ConfigValue.BooleanValue(found))
                }
            }

            val completed = when (request.operationId) {
                AccessibilityOperations.CLICK_TEXT -> service.clickText(
                    request.payload.string("text"),
                    request.payload.boolean("exact"),
                )
                AccessibilityOperations.LONG_CLICK_TEXT -> service.longClickText(
                    request.payload.string("text"),
                    request.payload.boolean("exact"),
                )
                AccessibilityOperations.CLICK_VIEW_ID -> service.clickViewId(request.payload.string("viewId"))
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
                else -> return@withContext CapabilityResult(false, message = userText("accessibility.unsupported_operation", "Unsupported accessibility operation: %s", request.operationId))
            }
            CapabilityResult(
                success = completed,
                value = ConfigValue.BooleanValue(completed),
                message = if (completed) null else "Accessibility operation did not complete successfully",
            )
        }
}
