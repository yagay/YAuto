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


internal fun AndroidSurfaceFeaturePack.registerSurfaceInteractive(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("surface.touch_blocker.show"), FeatureKind.ACTION,
                "Block screen touches", "Show a full-screen transparent overlay that consumes touch input until hidden",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(
                    FieldSchema.Text("surfaceId", "Surface ID", true),
                    FieldSchema.Duration("autoHideMs", "Auto hide after"),
                ),
                accessRequirements = setOf(AccessRequirement.OVERLAY),
                keywords = setOf("block touches", "touch lock", "overlay", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ActionExecutionResult(controller.showTouchBlocker(
                feature.config.string("surfaceId").resolveVariables(ctx.variables).trim(),
                feature.config.long("autoHideMs", 0),
            ))
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("surface.pie.show"), FeatureKind.ACTION,
                "Show pie menu", "Show a radial overlay menu; each line is Label=action",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(
                    FieldSchema.Text("surfaceId", "Surface ID", true),
                    FieldSchema.Text("items", "Pie items, Label=action per line", true, multiline = true),
                    FieldSchema.Choice("gravity", "Position", true, listOf("center", "top", "bottom", "left", "right", "top_left", "top_right", "bottom_left", "bottom_right")),
                    FieldSchema.Duration("autoHideMs", "Auto hide after"),
                ),
                accessRequirements = setOf(AccessRequirement.OVERLAY),
                keywords = setOf("pie", "radial", "menu", "shortx", "scene"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ActionExecutionResult(controller.showPie(
                id = feature.config.string("surfaceId").resolveVariables(ctx.variables).trim(),
                items = parseSurfaceItems(feature.config.string("items").resolveVariables(ctx.variables)),
                gravity = feature.config.string("gravity", "center"),
                autoHideMs = feature.config.long("autoHideMs", 0),
            ))
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("surface.slider.show"), FeatureKind.ACTION,
                "Show slider", "Show an interactive overlay slider and emit value_changed events",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(
                    FieldSchema.Text("surfaceId", "Surface ID", true),
                    FieldSchema.Text("title", "Title"),
                    FieldSchema.Number("min", "Minimum", true),
                    FieldSchema.Number("max", "Maximum", true),
                    FieldSchema.Number("value", "Initial value"),
                    FieldSchema.Choice("gravity", "Position", true, listOf("center", "top", "bottom", "left", "right")),
                    FieldSchema.Duration("autoHideMs", "Auto hide after"),
                ),
                accessRequirements = setOf(AccessRequirement.OVERLAY),
                keywords = setOf("slider", "seekbar", "scene", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ActionExecutionResult(controller.showSlider(
                id = feature.config.string("surfaceId").resolveVariables(ctx.variables).trim(),
                title = feature.config.string("title").resolveVariables(ctx.variables),
                minValue = (feature.config["min"].numberOrNull() ?: 0.0).toInt(),
                maxValue = (feature.config["max"].numberOrNull() ?: 100.0).toInt(),
                value = (feature.config["value"].numberOrNull() ?: 0.0).toInt(),
                gravity = feature.config.string("gravity", "center"),
                autoHideMs = feature.config.long("autoHideMs", 0),
            ))
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("surface.toggle.show"), FeatureKind.ACTION,
                "Show toggle", "Show an interactive overlay switch and emit its boolean value",
                FeatureCategory.UI_AUTOMATION,
                fields = compactSurfaceFields() + FieldSchema.Toggle("checked", "Initially checked"),
                accessRequirements = setOf(AccessRequirement.OVERLAY),
                keywords = setOf("toggle", "switch", "scene", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ActionExecutionResult(controller.showToggle(
                id = feature.config.string("surfaceId").resolveVariables(ctx.variables).trim(),
                text = feature.config.string("text").resolveVariables(ctx.variables),
                checked = feature.config.boolean("checked", false),
                action = feature.config.string("action").resolveVariables(ctx.variables),
                gravity = feature.config.string("gravity", "center"),
                autoHideMs = feature.config.long("autoHideMs", 0),
            ))
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("surface.image.show"), FeatureKind.ACTION,
                "Show image overlay", "Show an image file or content URI as an overlay",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(
                    FieldSchema.Text("surfaceId", "Surface ID", true),
                    FieldSchema.Text("source", "Image path / content URI", true),
                    FieldSchema.Choice("gravity", "Position", true, listOf("center", "top", "bottom", "left", "right")),
                    FieldSchema.Duration("autoHideMs", "Auto hide after"),
                ),
                accessRequirements = setOf(AccessRequirement.OVERLAY),
                keywords = setOf("image", "overlay", "scene", "picture"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ActionExecutionResult(controller.showImage(
                id = feature.config.string("surfaceId").resolveVariables(ctx.variables).trim(),
                source = feature.config.string("source").resolveVariables(ctx.variables),
                gravity = feature.config.string("gravity", "center"),
                autoHideMs = feature.config.long("autoHideMs", 0),
            ))
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("surface.web.show"), FeatureKind.ACTION,
                "Show web overlay", "Show a URL in an interactive overlay WebView",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(
                    FieldSchema.Text("surfaceId", "Surface ID", true),
                    FieldSchema.Text("url", "URL", true),
                    FieldSchema.Toggle("javaScript", "Enable JavaScript"),
                    FieldSchema.Choice("gravity", "Position", true, listOf("center", "top", "bottom", "left", "right")),
                    FieldSchema.Duration("autoHideMs", "Auto hide after"),
                ),
                accessRequirements = setOf(AccessRequirement.OVERLAY),
                keywords = setOf("webview", "web", "scene", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ActionExecutionResult(controller.showWeb(
                id = feature.config.string("surfaceId").resolveVariables(ctx.variables).trim(),
                url = feature.config.string("url").resolveVariables(ctx.variables).trim(),
                gravity = feature.config.string("gravity", "center"),
                autoHideMs = feature.config.long("autoHideMs", 0),
                javaScript = feature.config.boolean("javaScript", false),
            ))
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("surface.draw_board.show"), FeatureKind.ACTION,
                "Show draw board", "Show an interactive drawing surface with clear and PNG save actions",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(
                    FieldSchema.Text("surfaceId", "Surface ID", true),
                    FieldSchema.Text("title", "Title"),
                    FieldSchema.Choice("gravity", "Position", true, listOf("center", "top", "bottom")),
                    FieldSchema.Duration("autoHideMs", "Auto hide after"),
                ),
                accessRequirements = setOf(AccessRequirement.OVERLAY),
                keywords = setOf("draw board", "drawing", "canvas", "shortx", "scene"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ActionExecutionResult(controller.showDrawBoard(
                id = feature.config.string("surfaceId").resolveVariables(ctx.variables).trim(),
                title = feature.config.string("title").resolveVariables(ctx.variables),
                gravity = feature.config.string("gravity", "center"),
                autoHideMs = feature.config.long("autoHideMs", 0),
            ))
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("surface.edge_lighting.show"), FeatureKind.ACTION,
                "Show edge lighting", "Show a non-interactive colored border around the display",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(
                    FieldSchema.Text("surfaceId", "Surface ID", true),
                    FieldSchema.Text("color", "Color (#RRGGBB or #AARRGGBB)"),
                    FieldSchema.Number("thicknessDp", "Border thickness dp", min = 1.0, max = 48.0),
                    FieldSchema.Duration("autoHideMs", "Duration"),
                ),
                accessRequirements = setOf(AccessRequirement.OVERLAY),
                keywords = setOf("edge lighting", "border", "notification light", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val raw = feature.config.string("color", "#448AFF").resolveVariables(ctx.variables).trim()
            val color = runCatching { android.graphics.Color.parseColor(raw) }.getOrDefault(0xFF448AFF.toInt())
            ActionExecutionResult(controller.showEdgeLighting(
                id = feature.config.string("surfaceId").resolveVariables(ctx.variables).trim(),
                color = color,
                thicknessDp = (feature.config["thicknessDp"].numberOrNull() ?: 6.0).toInt(),
                autoHideMs = feature.config.long("autoHideMs", 3_000L),
            ))
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("surface.progress.show"), FeatureKind.ACTION,
                "Show progress overlay", "Show determinate or indeterminate progress on an overlay surface",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(
                    FieldSchema.Text("surfaceId", "Surface ID", true),
                    FieldSchema.Text("title", "Title"),
                    FieldSchema.Text("text", "Text"),
                    FieldSchema.Number("progress", "Progress percent", min = 0.0, max = 100.0),
                    FieldSchema.Toggle("indeterminate", "Indeterminate"),
                    FieldSchema.Choice("gravity", "Position", true, listOf("center", "top", "bottom")),
                    FieldSchema.Duration("autoHideMs", "Auto hide after"),
                ),
                accessRequirements = setOf(AccessRequirement.OVERLAY),
                keywords = setOf("progress", "dialog", "overlay", "scene"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ActionExecutionResult(controller.showProgress(
                id = feature.config.string("surfaceId").resolveVariables(ctx.variables).trim(),
                title = feature.config.string("title").resolveVariables(ctx.variables),
                text = feature.config.string("text").resolveVariables(ctx.variables),
                progress = (feature.config["progress"].numberOrNull() ?: 0.0).toInt(),
                indeterminate = feature.config.boolean("indeterminate", false),
                gravity = feature.config.string("gravity", "center"),
                autoHideMs = feature.config.long("autoHideMs", 0),
            ))
        }


        registry.registerAction(
            FeatureDescriptor(
                FeatureId("surface.edge_gesture.show"), FeatureKind.ACTION,
                "Show edge gesture trigger",
                "Create a transparent edge/corner gesture zone and emit edge_swipe or edge_tap surface events",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(
                    FieldSchema.Text("surfaceId", "Surface ID", true),
                    FieldSchema.Choice("edge", "Edge / corner", true, listOf("left", "right", "top", "bottom", "top_left", "top_right", "bottom_left", "bottom_right")),
                    FieldSchema.Number("thicknessDp", "Touch-zone thickness dp", min = 4.0, max = 96.0),
                    FieldSchema.Number("minDistanceDp", "Minimum swipe distance dp", min = 8.0, max = 400.0),
                    FieldSchema.Duration("autoHideMs", "Auto hide after"),
                ),
                accessRequirements = setOf(AccessRequirement.OVERLAY),
                keywords = setOf("edge", "corner", "swipe", "gesture", "shortx", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ActionExecutionResult(
                controller.showEdgeGesture(
                    id = feature.config.string("surfaceId").resolveVariables(ctx.variables).trim(),
                    edge = feature.config.string("edge", "left"),
                    thicknessDp = (feature.config["thicknessDp"].numberOrNull() ?: 24.0).toInt(),
                    minDistanceDp = (feature.config["minDistanceDp"].numberOrNull() ?: 48.0).toInt(),
                    autoHideMs = feature.config.long("autoHideMs", 0),
                )
            )
        }
}
