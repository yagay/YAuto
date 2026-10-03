package com.yagay.yauto.platform.accessibility

import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

class AccessibilityFeaturePack(
    private val fallbackForeground: (() -> AccessibilityWindowSnapshot?)? = null,
) : FeaturePack {
    override val id: String = "accessibility.actions"

    override fun install(registry: FeatureRegistry) {
        action(registry, AccessibilityOperations.CLICK_TEXT, "Click text", "Find visible text in the active window and click the nearest clickable node", listOf(FieldSchema.Text("text", "Text", true), FieldSchema.Toggle("exact", "Exact text match")), setOf("click", "text", "ui", "accessibility"))
        action(registry, AccessibilityOperations.LONG_CLICK_TEXT, "Long-click text", "Find visible text and perform the nearest supported long-click action", listOf(FieldSchema.Text("text", "Text", true), FieldSchema.Toggle("exact", "Exact text match")), setOf("long click", "long press", "text", "ui"))
        action(registry, AccessibilityOperations.CLICK_VIEW_ID, "Click View ID", "Find a view by resource ID and click the nearest clickable node", listOf(FieldSchema.Text("viewId", "View ID", true)), setOf("view id", "resource id", "ui", "accessibility"))
        action(registry, AccessibilityOperations.CLICK_DESCRIPTION, "Click content description", "Find a node by accessibility content description and click it", listOf(FieldSchema.Text("description", "Content description", true), FieldSchema.Toggle("exact", "Exact match")), setOf("content description", "accessibility label", "click", "ui"))
        action(registry, AccessibilityOperations.INPUT_TEXT, "Input text", "Set text on the currently focused editable accessibility node", listOf(FieldSchema.Text("text", "Text", true, multiline = true)), setOf("input", "type", "text", "accessibility"))
        action(registry, AccessibilityOperations.INPUT_TEXT_VIEW_ID, "Set text by View ID", "Set text directly on an editable node found by resource ID", listOf(FieldSchema.Text("viewId", "View ID", true), FieldSchema.Text("text", "Text", true, multiline = true)), setOf("input", "view id", "set text", "ui"))
        action(registry, AccessibilityOperations.GLOBAL_ACTION, "Global UI action", "Perform an Android accessibility global action", listOf(FieldSchema.Choice("action", "Action", true, listOf("back", "home", "recents", "notifications", "quick_settings", "power_dialog", "lock_screen"))), setOf("back", "home", "recents", "quick settings", "accessibility"))
        action(registry, AccessibilityOperations.SCROLL, "Scroll UI", "Scroll the first suitable scrollable node in the active window", listOf(FieldSchema.Choice("direction", "Direction", true, listOf("forward", "backward", "up", "down", "left", "right"))), setOf("scroll", "page", "ui", "swipe"))
        action(registry, AccessibilityOperations.TAP, "Tap coordinates", "Dispatch a tap gesture at screen coordinates", listOf(FieldSchema.Number("x", "X", true, min = 0.0), FieldSchema.Number("y", "Y", true, min = 0.0), FieldSchema.Duration("durationMs", "Press duration")), setOf("tap", "gesture", "coordinate", "accessibility"))
        action(registry, AccessibilityOperations.SWIPE, "Swipe", "Dispatch a swipe gesture between screen coordinates", listOf(FieldSchema.Number("x1", "Start X", true, min = 0.0), FieldSchema.Number("y1", "Start Y", true, min = 0.0), FieldSchema.Number("x2", "End X", true, min = 0.0), FieldSchema.Number("y2", "End Y", true, min = 0.0), FieldSchema.Duration("durationMs", "Duration")), setOf("swipe", "gesture", "accessibility"))

        condition(registry, "accessibility.condition.text_present", AccessibilityOperations.FIND_TEXT, "Text on screen", "Check whether text/content description is currently visible", listOf(FieldSchema.Text("text", "Text", true), FieldSchema.Toggle("exact", "Exact text match")), setOf("text present", "screen text", "ui", "accessibility"))
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

        foregroundState(registry, FeatureKind.STATE, "android.state.app_foreground")
        foregroundState(registry, FeatureKind.CONDITION, "android.condition.app_foreground")
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
            // Historical/runtime test events did not carry a source. Treat blank as the original
            // Accessibility source for backwards compatibility, but never as explicit Usage Stats.
            "accessibility" -> source.isBlank() || source == ACCESSIBILITY_WINDOW_SOURCE
            "usage_stats" -> source == USAGE_STATS_SOURCE
            // Auto/default mode also covers historical RuntimeEvent sources such as "runtime".
            // Only an explicitly selected backend should reject events from another source.
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

    private fun condition(registry: FeatureRegistry, typeId: String, operationId: String, title: String, description: String, fields: List<FieldSchema>, keywords: Set<String>) {
        registry.registerCondition(
            FeatureDescriptor(id = FeatureId(typeId), kind = FeatureKind.CONDITION, title = title, description = description, category = FeatureCategory.UI_AUTOMATION, capabilities = setOf(CapabilityIds.ACCESSIBILITY), fields = fields, keywords = keywords, ownerPackId = id)
        ) { feature, ctx ->
            val result = ctx.capabilities.execute(CapabilityRequest(CapabilityIds.ACCESSIBILITY, operationId, feature.config))
            result.success && (result.value as? ConfigValue.BooleanValue)?.value == true
        }
    }

    private companion object {
        const val ACCESSIBILITY_WINDOW_SOURCE = "accessibility.window"
        const val USAGE_STATS_SOURCE = "android.usage.foreground"
    }
}
