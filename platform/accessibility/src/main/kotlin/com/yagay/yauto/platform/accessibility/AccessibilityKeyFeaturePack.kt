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
                "Hardware key", "Run when Accessibility or LSPosed reports a hardware key down or up event",
                FeatureCategory.UI_AUTOMATION,
                implementationOptions = listOf(
                    FeatureImplementationOption("accessibility", setOf(AccessRequirement.ACCESSIBILITY)),
                    FeatureImplementationOption("lsposed", setOf(AccessRequirement.LSPOSED), restartRequired = true),
                ),
                fields = listOf(
                    FieldSchema.Number("keyCode", "Android key code", min = 0.0, max = 1000.0),
                    FieldSchema.Number("scanCode", "Linux scan code", min = 0.0, max = 65535.0),
                    FieldSchema.Choice("action", "Key action", options = listOf("any", "down", "up")),
                    FieldSchema.Toggle("initialOnly", "Ignore repeated key-down events"),
                ),
                keywords = setOf("hardware key", "button", "volume key", "media key", "keycode", "scancode", "accessibility", "lsposed", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ctx.event.typeId == "android.event.hardware_key" && matchesHardwareKey(feature.config, ctx.event.payload)
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.hardware_key_gesture"), FeatureKind.EVENT,
                "Hardware key gesture",
                "Run on a single, double, triple or long press derived from the raw hardware-key stream",
                FeatureCategory.UI_AUTOMATION,
                implementationOptions = listOf(
                    FeatureImplementationOption("accessibility", setOf(AccessRequirement.ACCESSIBILITY)),
                    FeatureImplementationOption("lsposed", setOf(AccessRequirement.LSPOSED), restartRequired = true),
                ),
                fields = listOf(
                    FieldSchema.Number("keyCode", "Android key code", min = 0.0, max = 1000.0),
                    FieldSchema.Number("scanCode", "Linux scan code", min = 0.0, max = 65535.0),
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
                    "long press", "shortx", "lsposed", "keycode", "scancode",
                ),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ctx.event.typeId == "android.event.hardware_key_gesture" &&
                matchesHardwareKeyGesture(feature.config, ctx.event.payload)
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.hardware_key_combo"), FeatureKind.EVENT,
                "Hardware key combination",
                "Run when two physical keys are held together",
                FeatureCategory.UI_AUTOMATION,
                implementationOptions = listOf(
                    FeatureImplementationOption("accessibility", setOf(AccessRequirement.ACCESSIBILITY)),
                    FeatureImplementationOption("lsposed", setOf(AccessRequirement.LSPOSED), restartRequired = true),
                ),
                fields = listOf(
                    FieldSchema.Number("keyCode1", "First Android key code", min = 0.0, max = 1000.0),
                    FieldSchema.Number("scanCode1", "First Linux scan code", min = 0.0, max = 65535.0),
                    FieldSchema.Number("keyCode2", "Second Android key code", min = 0.0, max = 1000.0),
                    FieldSchema.Number("scanCode2", "Second Linux scan code", min = 0.0, max = 65535.0),
                ),
                keywords = setOf(
                    "hardware key", "button", "combination", "combo", "two keys",
                    "shortx", "lsposed", "keycode", "scancode",
                ),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ctx.event.typeId == "android.event.hardware_key_combo" &&
                matchesHardwareKeyCombo(feature.config, ctx.event.payload)
        }
    }
}

internal fun matchesHardwareKey(config: ConfigMap, payload: ConfigMap): Boolean {
    val expectedKeyCode = config["keyCode"].numberOrNull()?.toInt()
    val expectedScanCode = config["scanCode"].numberOrNull()?.toInt()
    val actualKeyCode = payload["keyCode"].numberOrNull()?.toInt()
    val actualScanCode = payload["scanCode"].numberOrNull()?.toInt()
    if (expectedKeyCode != null && expectedKeyCode != actualKeyCode) return false
    if (expectedScanCode != null && expectedScanCode != actualScanCode) return false
    if (expectedKeyCode == null && expectedScanCode == null && actualKeyCode == null) return false
    val expectedAction = config.string("action", "any")
    if (expectedAction != "any" && payload.string("action") != expectedAction) return false
    if (config.boolean("initialOnly") && (payload["repeatCount"].numberOrNull()?.toInt() ?: 0) > 0) return false
    return true
}


internal fun matchesHardwareKeyGesture(config: ConfigMap, payload: ConfigMap): Boolean {
    val expectedKeyCode = config["keyCode"].numberOrNull()?.toInt()
    val expectedScanCode = config["scanCode"].numberOrNull()?.toInt()
    val actualKeyCode = payload["keyCode"].numberOrNull()?.toInt()
    val actualScanCode = payload["scanCode"].numberOrNull()?.toInt()
    if (!matchesConfiguredKey(expectedKeyCode, expectedScanCode, actualKeyCode, actualScanCode)) return false

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
    val expected1 = ConfiguredKey(
        config["keyCode1"].numberOrNull()?.toInt(),
        config["scanCode1"].numberOrNull()?.toInt(),
    )
    val expected2 = ConfiguredKey(
        config["keyCode2"].numberOrNull()?.toInt(),
        config["scanCode2"].numberOrNull()?.toInt(),
    )
    if (!expected1.configured || !expected2.configured) return false

    val actual1 = ConfiguredKey(
        payload["keyCode1"].numberOrNull()?.toInt(),
        payload["scanCode1"].numberOrNull()?.toInt(),
    )
    val actual2 = ConfiguredKey(
        payload["keyCode2"].numberOrNull()?.toInt(),
        payload["scanCode2"].numberOrNull()?.toInt(),
    )
    return (expected1.matches(actual1) && expected2.matches(actual2)) ||
        (expected1.matches(actual2) && expected2.matches(actual1))
}

private fun matchesConfiguredKey(
    expectedKeyCode: Int?,
    expectedScanCode: Int?,
    actualKeyCode: Int?,
    actualScanCode: Int?,
): Boolean {
    val keyConfigured = expectedKeyCode != null
    val scanConfigured = expectedScanCode != null
    if (!keyConfigured && !scanConfigured) return false
    if (keyConfigured && expectedKeyCode != actualKeyCode) return false
    if (scanConfigured && expectedScanCode != actualScanCode) return false
    return true
}

private data class ConfiguredKey(
    val keyCode: Int?,
    val scanCode: Int?,
) {
    val configured: Boolean get() = keyCode != null || scanCode != null

    fun matches(other: ConfiguredKey): Boolean =
        configured &&
            (keyCode == null || keyCode == other.keyCode) &&
            (scanCode == null || scanCode == other.scanCode)
}
