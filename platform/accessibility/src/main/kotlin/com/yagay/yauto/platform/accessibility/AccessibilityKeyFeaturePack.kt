package com.yagay.yauto.platform.accessibility

import com.yagay.yauto.core.model.ConfigMap
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.AccessRequirement
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureImplementationOption
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.FieldSchema

/**
 * Android-facing hardware-key triggers.
 *
 * Rules intentionally use Android KeyCode only. Lower input-layer identifiers such as scanCode,
 * EV_KEY and MSC_SCAN may still be collected internally for diagnostics/mapping but are never
 * exposed as rule parameters.
 */
class AccessibilityKeyFeaturePack : FeaturePack {
    override val id: String = "accessibility.keys"

    override fun install(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.hardware_key"),
                FeatureKind.EVENT,
                "Hardware key",
                "Run when Android reports a physical key down or up event",
                FeatureCategory.UI_AUTOMATION,
                schemaVersion = 3,
                implementationOptions = hardwareKeyImplementations(),
                fields = listOf(
                    FieldSchema.Number("keyCode", "Android key code", min = 0.0, max = 4096.0),
                    FieldSchema.Choice("action", "Key action", options = listOf("any", "down", "up")),
                    FieldSchema.Toggle("initialOnly", "Ignore repeated key-down events"),
                ),
                keywords = setOf(
                    "hardware key", "button", "volume key", "media key", "keycode",
                    "android", "accessibility", "lsposed", "shortx",
                ),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ctx.event.typeId == "android.event.hardware_key" &&
                matchesHardwareKey(feature.config, ctx.event.payload)
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.hardware_key_gesture"),
                FeatureKind.EVENT,
                "Hardware key gesture",
                "Run on a single, double, triple or long press of an Android hardware key",
                FeatureCategory.UI_AUTOMATION,
                schemaVersion = 3,
                implementationOptions = hardwareKeyImplementations(),
                fields = listOf(
                    FieldSchema.Number("keyCode", "Android key code", min = 0.0, max = 4096.0),
                    FieldSchema.Choice(
                        "gesture",
                        "Key gesture",
                        required = true,
                        options = listOf("single_press", "double_press", "triple_press", "long_press"),
                    ),
                    FieldSchema.Duration("longPressMs", "Long-press threshold"),
                ),
                keywords = setOf(
                    "hardware key", "button", "gesture", "double press", "triple press",
                    "long press", "shortx", "lsposed", "keycode", "android",
                ),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ctx.event.typeId == "android.event.hardware_key_gesture" &&
                matchesHardwareKeyGesture(feature.config, ctx.event.payload)
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.hardware_key_combo"),
                FeatureKind.EVENT,
                "Hardware key combination",
                "Run when two Android hardware keys are held together",
                FeatureCategory.UI_AUTOMATION,
                schemaVersion = 3,
                implementationOptions = hardwareKeyImplementations(),
                fields = listOf(
                    FieldSchema.Number("keyCode1", "First Android key code", min = 0.0, max = 4096.0),
                    FieldSchema.Number("keyCode2", "Second Android key code", min = 0.0, max = 4096.0),
                ),
                keywords = setOf(
                    "hardware key", "button", "combination", "combo", "two keys",
                    "shortx", "lsposed", "keycode", "android",
                ),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ctx.event.typeId == "android.event.hardware_key_combo" &&
                matchesHardwareKeyCombo(feature.config, ctx.event.payload)
        }
    }

    private fun hardwareKeyImplementations() = listOf(
        FeatureImplementationOption("accessibility", setOf(AccessRequirement.ACCESSIBILITY)),
        FeatureImplementationOption("lsposed", setOf(AccessRequirement.LSPOSED), restartRequired = true),
    )
}

internal fun matchesHardwareKey(config: ConfigMap, payload: ConfigMap): Boolean {
    val expected = configuredKeyCode(config, "keyCode") ?: return false
    val actual = configuredKeyCode(payload, "keyCode") ?: return false
    if (expected != actual) return false

    val expectedAction = config.string("action", "any")
    if (expectedAction != "any" && payload.string("action") != expectedAction) return false
    if (config.boolean("initialOnly") && (payload["repeatCount"].numberOrNull()?.toInt() ?: 0) > 0) return false
    return true
}

internal fun matchesHardwareKeyGesture(config: ConfigMap, payload: ConfigMap): Boolean {
    val expected = configuredKeyCode(config, "keyCode") ?: return false
    val actual = configuredKeyCode(payload, "keyCode") ?: return false
    if (expected != actual) return false

    val pressCount = payload["pressCount"].numberOrNull()?.toInt() ?: return false
    val maxHoldMs = payload["maxHoldMs"].numberOrNull() ?: 0.0
    val thresholdMs = (config["longPressMs"].numberOrNull() ?: 500.0).coerceAtLeast(1.0)
    return when (config.string("gesture", "single_press")) {
        "single_press" -> pressCount == 1 && maxHoldMs < thresholdMs
        "double_press" -> pressCount == 2
        "triple_press" -> pressCount >= 3
        "long_press" -> pressCount == 1 && maxHoldMs >= thresholdMs
        else -> false
    }
}

internal fun matchesHardwareKeyCombo(config: ConfigMap, payload: ConfigMap): Boolean {
    val expected1 = configuredKeyCode(config, "keyCode1") ?: return false
    val expected2 = configuredKeyCode(config, "keyCode2") ?: return false
    val actual1 = configuredKeyCode(payload, "keyCode1") ?: return false
    val actual2 = configuredKeyCode(payload, "keyCode2") ?: return false
    return (expected1 == actual1 && expected2 == actual2) ||
        (expected1 == actual2 && expected2 == actual1)
}

private fun configuredKeyCode(values: ConfigMap, key: String): Int? =
    values[key].numberOrNull()?.toInt()?.takeIf { it > 0 }
