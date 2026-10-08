package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

class AndroidLsposedSystemEventFeaturePack : FeaturePack {
    override val id: String = "android.lsposed_system_events"

    override fun install(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.app_process_stopped"), FeatureKind.EVENT,
                "App process stopped",
                "Run when system_server reports an application process being killed or made inactive",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.AppPicker("package", "App / package"),
                    FieldSchema.Text("processContains", "Process name contains"),
                ),
                accessRequirements = setOf(AccessRequirement.LSPOSED),
                keywords = setOf("process stopped", "process died", "kill", "lsposed", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.app_process_stopped") return@registerEvent false
            val pkg = feature.config.string("package").trim()
            val process = feature.config.string("processContains").trim()
            (pkg.isBlank() || ctx.event.payload.string("package") == pkg) &&
                (process.isBlank() || ctx.event.payload.string("processName").contains(process, true))
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.task_removed"), FeatureKind.EVENT,
                "Recent task removed",
                "Run when system_server removes an Android recent-task entry",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.AppPicker("package", "App / package"),
                    FieldSchema.Text("activityContains", "Activity class contains"),
                    FieldSchema.Number("taskId", "Task ID (-1 = any)", min = -1.0),
                ),
                accessRequirements = setOf(AccessRequirement.LSPOSED),
                keywords = setOf("task removed", "recents", "recent task", "lsposed", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.task_removed") return@registerEvent false
            val pkg = feature.config.string("package").trim()
            val activity = feature.config.string("activityContains").trim()
            val taskId = (feature.config["taskId"].numberOrNull() ?: -1.0).toInt()
            (pkg.isBlank() || ctx.event.payload.string("package") == pkg) &&
                (activity.isBlank() || ctx.event.payload.string("activity").contains(activity, true)) &&
                (taskId < 0 || (ctx.event.payload["taskId"].numberOrNull() ?: -1.0).toInt() == taskId)
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.back_navigation_started"), FeatureKind.EVENT,
                "Back navigation started",
                "Run when Android system_server starts predictive/back navigation",
                FeatureCategory.UI_AUTOMATION,
                accessRequirements = setOf(AccessRequirement.LSPOSED),
                keywords = setOf("back navigation", "predictive back", "started", "lsposed", "shortx"),
                ownerPackId = id,
            )
        ) { _, ctx -> ctx.event.typeId == "android.event.back_navigation_started" }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.back_navigation_finished"), FeatureKind.EVENT,
                "Back navigation finished",
                "Run when Android system_server completes or clears predictive/back navigation",
                FeatureCategory.UI_AUTOMATION,
                accessRequirements = setOf(AccessRequirement.LSPOSED),
                keywords = setOf("back navigation", "predictive back", "finished", "lsposed", "shortx"),
                ownerPackId = id,
            )
        ) { _, ctx -> ctx.event.typeId == "android.event.back_navigation_finished" }
    }
}
