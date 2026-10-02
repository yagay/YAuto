package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.long
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
}
