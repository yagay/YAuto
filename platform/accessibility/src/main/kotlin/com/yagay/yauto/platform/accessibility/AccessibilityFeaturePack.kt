package com.yagay.yauto.platform.accessibility

import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.registry.*

class AccessibilityFeaturePack : FeaturePack {
    override val id: String = "accessibility.actions"

    override fun install(registry: FeatureRegistry) {
        action(
            registry,
            AccessibilityOperations.CLICK_TEXT,
            "Click text",
            "Find visible text in the active window and click the nearest clickable node",
            listOf(
                FieldSchema.Text("text", "Text", true),
                FieldSchema.Toggle("exact", "Exact text match"),
            ),
            setOf("click", "text", "ui", "accessibility", "点击文本"),
        )
        action(
            registry,
            AccessibilityOperations.CLICK_VIEW_ID,
            "Click View ID",
            "Find a view by resource ID and click the nearest clickable node",
            listOf(FieldSchema.Text("viewId", "View ID", true)),
            setOf("view id", "resource id", "ui", "accessibility"),
        )
        action(
            registry,
            AccessibilityOperations.INPUT_TEXT,
            "Input text",
            "Set text on the currently focused editable accessibility node",
            listOf(FieldSchema.Text("text", "Text", true, multiline = true)),
            setOf("input", "type", "text", "accessibility", "输入"),
        )
        action(
            registry,
            AccessibilityOperations.GLOBAL_ACTION,
            "Global UI action",
            "Perform an Android accessibility global action",
            listOf(FieldSchema.Choice("action", "Action", true, listOf(
                "back", "home", "recents", "notifications", "quick_settings", "power_dialog", "lock_screen"
            ))),
            setOf("back", "home", "recents", "quick settings", "accessibility"),
        )
        action(
            registry,
            AccessibilityOperations.TAP,
            "Tap coordinates",
            "Dispatch a tap gesture at screen coordinates",
            listOf(
                FieldSchema.Number("x", "X", true, min = 0.0),
                FieldSchema.Number("y", "Y", true, min = 0.0),
                FieldSchema.Duration("durationMs", "Press duration"),
            ),
            setOf("tap", "gesture", "coordinate", "accessibility"),
        )
        action(
            registry,
            AccessibilityOperations.SWIPE,
            "Swipe",
            "Dispatch a swipe gesture between screen coordinates",
            listOf(
                FieldSchema.Number("x1", "Start X", true, min = 0.0),
                FieldSchema.Number("y1", "Start Y", true, min = 0.0),
                FieldSchema.Number("x2", "End X", true, min = 0.0),
                FieldSchema.Number("y2", "End Y", true, min = 0.0),
                FieldSchema.Duration("durationMs", "Duration"),
            ),
            setOf("swipe", "gesture", "accessibility"),
        )
    }

    private fun action(
        registry: FeatureRegistry,
        typeId: String,
        title: String,
        description: String,
        fields: List<FieldSchema>,
        keywords: Set<String>,
    ) {
        registry.registerAction(
            FeatureDescriptor(
                id = FeatureId(typeId),
                kind = FeatureKind.ACTION,
                title = title,
                description = description,
                category = FeatureCategory.UI_AUTOMATION,
                capabilities = setOf(CapabilityIds.ACCESSIBILITY),
                fields = fields,
                keywords = keywords,
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val result = ctx.capabilities.execute(
                CapabilityRequest(
                    capability = CapabilityIds.ACCESSIBILITY,
                    operationId = typeId,
                    payload = feature.config,
                )
            )
            ActionExecutionResult(result.success, result.value, result.message)
        }
    }
}
