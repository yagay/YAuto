package com.yagay.yauto.platform.android

import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.userText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*


internal fun AndroidSurfaceFeaturePack.registerSurfaceEventsAndGestures(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("surface.region_selector.show"), FeatureKind.ACTION,
                "Select screen region",
                "Show a full-screen selection overlay and emit region_selected with left,top,right,bottom coordinates",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(
                    FieldSchema.Text("surfaceId", "Surface ID", true),
                    FieldSchema.Duration("autoHideMs", "Auto hide after"),
                ),
                accessRequirements = setOf(AccessRequirement.OVERLAY),
                keywords = setOf("screen region", "area select", "crop", "screenshot", "shortx", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ActionExecutionResult(
                controller.showRegionSelector(
                    id = feature.config.string("surfaceId").resolveVariables(ctx.variables).trim(),
                    autoHideMs = feature.config.long("autoHideMs", 0),
                )
            )
        }


        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.screenshot.area_select"), FeatureKind.ACTION,
                "Capture selected screen area",
                "Draw a region, close the selector, and save a cropped screenshot using Accessibility",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(
                    FieldSchema.Text("fileName", "Saved PNG file name"),
                    FieldSchema.Duration("timeoutMs", "Selection timeout"),
                    FieldSchema.Variable("resultVariable", "Save screenshot path to variable"),
                ),
                accessRequirements = setOf(AccessRequirement.OVERLAY, AccessRequirement.ACCESSIBILITY),
                capabilities = setOf(CapabilityIds.ACCESSIBILITY),
                keywords = setOf("area screenshot", "screen region", "selected screenshot", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val selection = CompletableDeferred<List<Int>>()
            val id = "yauto-area-" + UUID.randomUUID().toString()
            val maxWait = feature.config.long("timeoutMs", 30_000L).coerceIn(1_000L, 120_000L)
            val shown = controller.showRegionSelector(id, maxWait) { left, top, right, bottom ->
                selection.complete(listOf(left, top, right, bottom))
            }
            if (!shown) return@registerAction ActionExecutionResult(false, message = userText("feature.area_screenshot_overlay_unavailable"))
            try {
                val rect = withTimeoutOrNull(maxWait) { selection.await() }
                    ?: return@registerAction ActionExecutionResult(false, message = userText("feature.area_screenshot_timed_out"))
                // Overlay hide is scheduled on the UI thread; let it detach before capture.
                delay(200L)
                val width = rect[2] - rect[0]
                val height = rect[3] - rect[1]
                if (rect[0] < 0 || rect[1] < 0 || width < 4 || height < 4) {
                    return@registerAction ActionExecutionResult(false, message = userText("feature.area_screenshot_invalid"))
                }
                val capture = ctx.capabilities.execute(
                    CapabilityRequest(
                        capability = CapabilityIds.ACCESSIBILITY,
                        operationId = "accessibility.screenshot.capture",
                        payload = mapOf(
                            "x" to ConfigValue.NumberValue(rect[0].toDouble()),
                            "y" to ConfigValue.NumberValue(rect[1].toDouble()),
                            "width" to ConfigValue.NumberValue(width.toDouble()),
                            "height" to ConfigValue.NumberValue(height.toDouble()),
                            "fileName" to ConfigValue.StringValue(
                                feature.config.string("fileName").resolveVariables(ctx.variables)
                            ),
                        ),
                    )
                )
                if (capture.success) {
                    feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let {
                        ctx.variables.set(it, capture.value)
                    }
                }
                ActionExecutionResult(capture.success, capture.value, capture.message)
            } finally {
                controller.hide(id)
            }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("surface.screen_flash.show"), FeatureKind.ACTION,
                "Screen flash",
                "Show a full-screen non-touchable color flash overlay",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(
                    FieldSchema.Text("surfaceId", "Surface ID", true),
                    FieldSchema.Text("color", "Color (#RRGGBB or #AARRGGBB)"),
                    FieldSchema.Number("alpha", "Opacity 0-255", min = 0.0, max = 255.0),
                    FieldSchema.Duration("durationMs", "Duration"),
                ),
                accessRequirements = setOf(AccessRequirement.OVERLAY),
                keywords = setOf("screen flash", "flash", "overlay", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val color = runCatching {
                android.graphics.Color.parseColor(
                    feature.config.string("color", "#FFFFFF").resolveVariables(ctx.variables).ifBlank { "#FFFFFF" }
                )
            }.getOrDefault(android.graphics.Color.WHITE)
            ActionExecutionResult(
                controller.showScreenFlash(
                    id = feature.config.string("surfaceId").resolveVariables(ctx.variables).trim(),
                    color = color,
                    alpha = (feature.config["alpha"].numberOrNull() ?: 220.0).toInt(),
                    durationMs = feature.config.long("durationMs", 500L),
                )
            )
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("surface.danmu.show"), FeatureKind.ACTION,
                "Show danmu / scrolling text",
                "Show scrolling overlay text across the screen",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(
                    FieldSchema.Text("surfaceId", "Surface ID", true),
                    FieldSchema.Text("text", "Text", true),
                    FieldSchema.Text("color", "Text color"),
                    FieldSchema.Number("textSizeSp", "Text size sp", min = 8.0, max = 72.0),
                    FieldSchema.Duration("durationMs", "Travel duration"),
                    FieldSchema.Choice("gravity", "Vertical position", options = listOf("top", "center", "bottom")),
                ),
                fieldBehaviors = mapOf("text" to FieldBehavior(supportsVariables = true)),
                accessRequirements = setOf(AccessRequirement.OVERLAY),
                keywords = setOf("danmu", "marquee", "scrolling text", "overlay", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val color = runCatching {
                android.graphics.Color.parseColor(
                    feature.config.string("color", "#FFFFFF").resolveVariables(ctx.variables).ifBlank { "#FFFFFF" }
                )
            }.getOrDefault(android.graphics.Color.WHITE)
            ActionExecutionResult(
                controller.showDanmu(
                    id = feature.config.string("surfaceId").resolveVariables(ctx.variables).trim(),
                    text = feature.config.string("text").resolveVariables(ctx.variables),
                    color = color,
                    textSizeSp = (feature.config["textSizeSp"].numberOrNull() ?: 18.0).toFloat(),
                    durationMs = feature.config.long("durationMs", 8_000L),
                    gravity = feature.config.string("gravity", "top"),
                )
            )
        }


        registry.registerAction(
            FeatureDescriptor(
                FeatureId("surface.gesture_recorder.show"), FeatureKind.ACTION,
                "Record gesture",
                "Show a full-screen gesture recorder and emit gesture_recorded with replayable X,Y points",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(
                    FieldSchema.Text("surfaceId", "Surface ID", true),
                    FieldSchema.Number("maxPoints", "Maximum recorded points", min = 16.0, max = 4096.0),
                    FieldSchema.Duration("autoHideMs", "Auto hide after"),
                ),
                accessRequirements = setOf(AccessRequirement.OVERLAY),
                keywords = setOf("gesture recording", "record gesture", "shortx", "touch path"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ActionExecutionResult(
                controller.showGestureRecorder(
                    id = feature.config.string("surfaceId").resolveVariables(ctx.variables).trim(),
                    maxPoints = (feature.config["maxPoints"].numberOrNull() ?: 1024.0).toInt(),
                    autoHideMs = feature.config.long("autoHideMs", 0L),
                )
            )
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("surface.gesture_recording.stop"), FeatureKind.ACTION,
                "Stop gesture recording",
                "Commit the current touch path (if valid) and close a gesture recorder by its Surface ID",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(FieldSchema.Text("surfaceId", "Surface ID", true)),
                keywords = setOf("gesture recording", "stop", "save gesture", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ActionExecutionResult(
                controller.stopGestureRecorder(
                    feature.config.string("surfaceId").resolveVariables(ctx.variables).trim()
                )
            )
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("surface.gesture_recording.get"), FeatureKind.ACTION,
                "Get recorded gesture",
                "Return the latest recorded gesture path for a recorder Surface ID",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(
                    FieldSchema.Text("surfaceId", "Surface ID", true),
                    FieldSchema.Variable("resultVariable", "Store gesture path", true),
                ),
                keywords = setOf("gesture recording", "gesture path", "replay", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val surfaceId = feature.config.string("surfaceId").resolveVariables(ctx.variables).trim()
            val path = controller.recordedGesture(surfaceId) ?: return@registerAction ActionExecutionResult(false)
            val output = com.yagay.yauto.core.model.ConfigValue.StringValue(path)
            val resultName = feature.config.string("resultVariable").trim()
            if (resultName.isBlank()) return@registerAction ActionExecutionResult(false)
            ctx.variables.set(resultName, output)
            ActionExecutionResult(true, output)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("surface.gesture_recording.clear"), FeatureKind.ACTION,
                "Clear recorded gesture", "Clear the latest stored gesture path for a recorder Surface ID",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(FieldSchema.Text("surfaceId", "Surface ID", true)),
                keywords = setOf("gesture recording", "clear", "shortx"), ownerPackId = id,
            )
        ) { feature, ctx ->
            ActionExecutionResult(
                controller.clearRecordedGesture(
                    feature.config.string("surfaceId").resolveVariables(ctx.variables).trim()
                )
            )
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.surface_action"), FeatureKind.EVENT,
                "Surface action", "Run when a YAuto Surface button or lifecycle action fires",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(
                    FieldSchema.Text("surfaceId", "Surface ID"),
                    FieldSchema.Text("action", "Action name"),
                ),
                keywords = setOf("surface", "overlay", "button", "event"), ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.surface_action") return@registerEvent false
            val id = feature.config.string("surfaceId")
            val action = feature.config.string("action")
            (id.isBlank() || ctx.event.payload.string("surfaceId") == id) &&
                (action.isBlank() || ctx.event.payload.string("action") == action)
        }

        for (kind in listOf(FeatureKind.STATE, FeatureKind.CONDITION)) {
            val typeId = if (kind == FeatureKind.STATE) "surface.state.shown" else "surface.condition.shown"
            val descriptor = FeatureDescriptor(
                FeatureId(typeId), kind, "Surface visible", "Check whether an overlay Surface ID is currently shown",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(FieldSchema.Text("surfaceId", "Surface ID", true), FieldSchema.Toggle("value", "Shown")),
                ownerPackId = id,
            )
            val evaluator = ConditionEvaluator { feature, ctx ->
                val surfaceId = feature.config.string("surfaceId").resolveVariables(ctx.variables)
                controller.isShown(surfaceId) == feature.config.boolean("value", true)
            }
            if (kind == FeatureKind.STATE) registry.registerState(descriptor, evaluator) else registry.registerCondition(descriptor, evaluator)
        }
}
