package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.registry.*

class AndroidTaskerSceneFeaturePack(
    private val controller: OverlaySurfaceController,
) : FeaturePack {
    override val id: String = "android.tasker_scene"

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("tasker.scene.create"), FeatureKind.ACTION,
                "Prepare Tasker scene",
                "Store an imported Tasker Scene model without showing it yet",
                FeatureCategory.UI_AUTOMATION,
                fields = sceneFields(includeDisplay = false),
                keywords = setOf("tasker", "scene", "create", "surface"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ActionExecutionResult(
                controller.createTaskerScene(
                    feature.config.string("sceneId").resolveVariables(ctx.variables).trim(),
                    feature.config.string("modelJson").resolveVariables(ctx.variables),
                )
            )
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("tasker.scene.show"), FeatureKind.ACTION,
                "Show Tasker scene",
                "Render an imported Tasker Scene with YAuto's unified Surface system",
                FeatureCategory.UI_AUTOMATION,
                fields = sceneFields(includeDisplay = true),
                accessRequirements = setOf(AccessRequirement.OVERLAY),
                keywords = setOf("tasker", "scene", "show", "surface"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ActionExecutionResult(
                controller.showTaskerScene(
                    id = feature.config.string("sceneId").resolveVariables(ctx.variables).trim(),
                    title = feature.config.string("title").resolveVariables(ctx.variables),
                    modelJson = feature.config.string("modelJson").resolveVariables(ctx.variables),
                    gravity = feature.config.string("gravity", "center"),
                    autoHideMs = feature.config.long("autoHideMs", 0L),
                )
            )
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("tasker.scene.hide"), FeatureKind.ACTION,
                "Hide Tasker scene",
                "Hide an imported Tasker Scene while keeping its prepared model",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(FieldSchema.Text("sceneId", "Scene ID", true)),
                keywords = setOf("tasker", "scene", "hide"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            controller.hide(feature.config.string("sceneId").resolveVariables(ctx.variables).trim())
            ActionExecutionResult(true)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("tasker.scene.destroy"), FeatureKind.ACTION,
                "Destroy Tasker scene",
                "Hide an imported Tasker Scene and remove its prepared model",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(FieldSchema.Text("sceneId", "Scene ID", true)),
                keywords = setOf("tasker", "scene", "destroy"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ActionExecutionResult(
                controller.destroyTaskerScene(
                    feature.config.string("sceneId").resolveVariables(ctx.variables).trim()
                )
            )
        }
    }

    private fun sceneFields(includeDisplay: Boolean): List<FieldSchema> = buildList {
        add(FieldSchema.Text("sceneId", "Scene ID", true))
        add(FieldSchema.Text("title", "Title"))
        add(FieldSchema.Text("modelJson", "Imported scene model", true, multiline = true))
        if (includeDisplay) {
            add(
                FieldSchema.Choice(
                    "gravity", "Position", options = listOf(
                        "center", "top", "bottom", "left", "right",
                        "top_left", "top_right", "bottom_left", "bottom_right"
                    )
                )
            )
            add(FieldSchema.Duration("autoHideMs", "Auto hide after"))
        }
    }
}
