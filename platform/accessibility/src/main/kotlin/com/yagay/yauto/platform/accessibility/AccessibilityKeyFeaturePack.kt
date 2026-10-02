package com.yagay.yauto.platform.accessibility

import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.model.ConfigMap
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

/** Matches non-text hardware KeyEvent metadata forwarded by AccessibilityService. */
class AccessibilityKeyFeaturePack : FeaturePack {
    override val id: String = "accessibility.keys"

    override fun install(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.hardware_key"), FeatureKind.EVENT,
                "Hardware key", "Run when Accessibility reports a hardware key down or up event",
                FeatureCategory.UI_AUTOMATION,
                capabilities = setOf(CapabilityIds.ACCESSIBILITY),
                fields = listOf(
                    FieldSchema.Number("keyCode", "Android key code", min = 0.0, max = 1000.0),
                    FieldSchema.Choice("action", "Key action", options = listOf("any", "down", "up")),
                    FieldSchema.Toggle("initialOnly", "Ignore repeated key-down events"),
                ),
                keywords = setOf("hardware key", "button", "volume key", "media key", "keycode", "accessibility"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ctx.event.typeId == "android.event.hardware_key" && matchesHardwareKey(feature.config, ctx.event.payload)
        }
    }
}

internal fun matchesHardwareKey(config: ConfigMap, payload: ConfigMap): Boolean {
    val expectedKeyCode = config["keyCode"].numberOrNull()?.toInt()
    val actualKeyCode = payload["keyCode"].numberOrNull()?.toInt() ?: return false
    if (expectedKeyCode != null && expectedKeyCode != actualKeyCode) return false
    val expectedAction = config.string("action", "any")
    if (expectedAction != "any" && payload.string("action") != expectedAction) return false
    if (config.boolean("initialOnly") && (payload["repeatCount"].numberOrNull()?.toInt() ?: 0) > 0) return false
    return true
}
