package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

class AndroidSurfaceFeaturePack(
    private val controller: OverlaySurfaceController,
) : FeaturePack {
    override val id = "android.surface"

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("surface.overlay.show"), FeatureKind.ACTION,
                "Show overlay surface", "Show or replace a lightweight interactive overlay panel",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(
                    FieldSchema.Text("surfaceId", "Surface ID", true),
                    FieldSchema.Text("title", "Title"),
                    FieldSchema.Text("text", "Text", multiline = true),
                    FieldSchema.Text("buttonLabel", "Button label"),
                    FieldSchema.Text("buttonAction", "Button action name"),
                    FieldSchema.Choice("gravity", "Position", true, listOf("center", "top", "bottom", "top_left", "top_right", "bottom_left", "bottom_right")),
                    FieldSchema.Duration("autoHideMs", "Auto hide after"),
                ),
                accessRequirements = setOf(AccessRequirement.OVERLAY),
                keywords = setOf("overlay", "surface", "floating", "panel", "button"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val surfaceId = feature.config.string("surfaceId").resolveVariables(ctx.variables).trim()
            val ok = controller.show(
                id = surfaceId,
                title = feature.config.string("title").resolveVariables(ctx.variables),
                text = feature.config.string("text").resolveVariables(ctx.variables),
                buttonLabel = feature.config.string("buttonLabel").resolveVariables(ctx.variables),
                buttonAction = feature.config.string("buttonAction").resolveVariables(ctx.variables),
                gravity = feature.config.string("gravity", "center"),
                autoHideMs = feature.config.long("autoHideMs", 0).coerceAtLeast(0),
            )
            ActionExecutionResult(ok, message = if (ok) null else "Overlay permission is not granted or Surface ID is empty")
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("surface.overlay.hide"), FeatureKind.ACTION,
                "Hide overlay surface", "Hide a Surface by ID",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(FieldSchema.Text("surfaceId", "Surface ID", true)),
                keywords = setOf("overlay", "surface", "hide"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val id = feature.config.string("surfaceId").resolveVariables(ctx.variables).trim()
            ActionExecutionResult(controller.hide(id), message = if (controller.isShown(id)) null else null)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("surface.overlay.hide_all"), FeatureKind.ACTION,
                "Hide all overlay surfaces", "Remove every YAuto overlay Surface",
                FeatureCategory.UI_AUTOMATION,
                keywords = setOf("overlay", "surface", "hide all"), ownerPackId = id,
            )
        ) { _, _ -> controller.hideAll(); ActionExecutionResult(true) }


        registry.registerAction(
            FeatureDescriptor(
                FeatureId("surface.input.show"), FeatureKind.ACTION,
                "Show input dialog", "Show a focusable overlay input dialog and emit the entered value",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(
                    FieldSchema.Text("surfaceId", "Surface ID", true),
                    FieldSchema.Text("title", "Title"),
                    FieldSchema.Text("hint", "Input hint"),
                    FieldSchema.Text("initialValue", "Initial value"),
                    FieldSchema.Text("submitLabel", "Submit label"),
                    FieldSchema.Choice("gravity", "Position", true, listOf("center", "top", "bottom", "left", "right", "top_left", "top_right", "bottom_left", "bottom_right")),
                    FieldSchema.Duration("autoHideMs", "Auto hide after"),
                ),
                accessRequirements = setOf(AccessRequirement.OVERLAY),
                keywords = setOf("dialog", "input", "prompt", "surface", "shortx", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val ok = controller.showInput(
                id = feature.config.string("surfaceId").resolveVariables(ctx.variables).trim(),
                title = feature.config.string("title").resolveVariables(ctx.variables),
                hint = feature.config.string("hint").resolveVariables(ctx.variables),
                initialValue = feature.config.string("initialValue").resolveVariables(ctx.variables),
                submitLabel = feature.config.string("submitLabel").resolveVariables(ctx.variables),
                gravity = feature.config.string("gravity", "center"),
                autoHideMs = feature.config.long("autoHideMs", 0),
            )
            ActionExecutionResult(ok)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("surface.list.show"), FeatureKind.ACTION,
                "Show selection list", "Show an overlay list and emit the selected item and index",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(
                    FieldSchema.Text("surfaceId", "Surface ID", true),
                    FieldSchema.Text("title", "Title"),
                    FieldSchema.Text("items", "Items, one per line", true, multiline = true),
                    FieldSchema.Choice("gravity", "Position", true, listOf("center", "top", "bottom", "left", "right", "top_left", "top_right", "bottom_left", "bottom_right")),
                    FieldSchema.Duration("autoHideMs", "Auto hide after"),
                ),
                accessRequirements = setOf(AccessRequirement.OVERLAY),
                keywords = setOf("selection", "list", "dialog", "surface", "macrodroid", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val items = feature.config.string("items").resolveVariables(ctx.variables).lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            val ok = controller.showList(
                id = feature.config.string("surfaceId").resolveVariables(ctx.variables).trim(),
                title = feature.config.string("title").resolveVariables(ctx.variables),
                items = items,
                gravity = feature.config.string("gravity", "center"),
                autoHideMs = feature.config.long("autoHideMs", 0),
            )
            ActionExecutionResult(ok)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("surface.buttons.show"), FeatureKind.ACTION,
                "Show button panel", "Show a grid of overlay buttons; each line is Label=action",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(
                    FieldSchema.Text("surfaceId", "Surface ID", true),
                    FieldSchema.Text("title", "Title"),
                    FieldSchema.Text("buttons", "Buttons, Label=action per line", true, multiline = true),
                    FieldSchema.Number("columns", "Columns", min = 1.0, max = 6.0),
                    FieldSchema.Choice("gravity", "Position", true, listOf("center", "top", "bottom", "left", "right", "top_left", "top_right", "bottom_left", "bottom_right")),
                    FieldSchema.Duration("autoHideMs", "Auto hide after"),
                ),
                accessRequirements = setOf(AccessRequirement.OVERLAY),
                keywords = setOf("buttons", "grid", "scene", "surface", "tasker", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val items = parseSurfaceItems(feature.config.string("buttons").resolveVariables(ctx.variables))
            val columns = (feature.config["columns"].numberOrNull() ?: 2.0).toInt()
            val ok = controller.showButtonGrid(
                id = feature.config.string("surfaceId").resolveVariables(ctx.variables).trim(),
                title = feature.config.string("title").resolveVariables(ctx.variables),
                items = items,
                columns = columns,
                gravity = feature.config.string("gravity", "center"),
                autoHideMs = feature.config.long("autoHideMs", 0),
            )
            ActionExecutionResult(ok)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("surface.sidebar.show"), FeatureKind.ACTION,
                "Show sidebar", "Show a left or right vertical action sidebar",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(
                    FieldSchema.Text("surfaceId", "Surface ID", true),
                    FieldSchema.Text("title", "Title"),
                    FieldSchema.Text("items", "Items, Label=action per line", true, multiline = true),
                    FieldSchema.Choice("side", "Side", true, listOf("left", "right")),
                    FieldSchema.Duration("autoHideMs", "Auto hide after"),
                ),
                accessRequirements = setOf(AccessRequirement.OVERLAY),
                keywords = setOf("sidebar", "edge", "panel", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val ok = controller.showSidebar(
                id = feature.config.string("surfaceId").resolveVariables(ctx.variables).trim(),
                title = feature.config.string("title").resolveVariables(ctx.variables),
                items = parseSurfaceItems(feature.config.string("items").resolveVariables(ctx.variables)),
                side = feature.config.string("side", "left"),
                autoHideMs = feature.config.long("autoHideMs", 0),
            )
            ActionExecutionResult(ok)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("surface.bubble.show"), FeatureKind.ACTION,
                "Show floating bubble", "Show a compact floating action bubble",
                FeatureCategory.UI_AUTOMATION,
                fields = compactSurfaceFields(),
                accessRequirements = setOf(AccessRequirement.OVERLAY),
                keywords = setOf("bubble", "floating button", "overlay", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ActionExecutionResult(controller.showCompact(
                id = feature.config.string("surfaceId").resolveVariables(ctx.variables).trim(),
                text = feature.config.string("text").resolveVariables(ctx.variables),
                action = feature.config.string("action").resolveVariables(ctx.variables),
                gravity = feature.config.string("gravity", "right"),
                autoHideMs = feature.config.long("autoHideMs", 0),
                chip = false,
            ))
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("surface.chip.show"), FeatureKind.ACTION,
                "Show status chip", "Show a compact status-bar-style overlay chip",
                FeatureCategory.UI_AUTOMATION,
                fields = compactSurfaceFields(),
                accessRequirements = setOf(AccessRequirement.OVERLAY),
                keywords = setOf("chip", "status", "overlay", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ActionExecutionResult(controller.showCompact(
                id = feature.config.string("surfaceId").resolveVariables(ctx.variables).trim(),
                text = feature.config.string("text").resolveVariables(ctx.variables),
                action = feature.config.string("action").resolveVariables(ctx.variables),
                gravity = feature.config.string("gravity", "top"),
                autoHideMs = feature.config.long("autoHideMs", 0),
                chip = true,
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

    private fun compactSurfaceFields(): List<FieldSchema> = listOf(
        FieldSchema.Text("surfaceId", "Surface ID", true),
        FieldSchema.Text("text", "Text", true),
        FieldSchema.Text("action", "Action name"),
        FieldSchema.Choice("gravity", "Position", true, listOf("center", "top", "bottom", "left", "right", "top_left", "top_right", "bottom_left", "bottom_right")),
        FieldSchema.Duration("autoHideMs", "Auto hide after"),
    )

    private fun parseSurfaceItems(raw: String): List<Pair<String, String>> = raw.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) line to line else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
        }
        .take(100)
        .toList()
    }
}
