package com.yagay.yauto.platform.accessibility

import com.yagay.yauto.core.model.ConfigMap
import com.yagay.yauto.core.model.ConfigValue
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

/** Android-facing hardware-key triggers. Rules use Android KeyCode only. */
class AccessibilityKeyFeaturePack : FeaturePack {
    override val id: String = "accessibility.keys"

    override fun install(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.hardware_key"),
                FeatureKind.EVENT,
                "Hardware key",
                "Run when Android reports a physical key event",
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
                    "android", "accessibility", "lsposed", "shortx", "oem key",
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
                    "long press", "shortx", "lsposed", "keycode", "android", "oem key",
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
                    "shortx", "lsposed", "keycode", "android", "oem key",
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
    if (!matchesConfiguredKey(config.keyIdentity(), payload.keyIdentity())) return false
    val expectedAction = config.string("action", "any")
    if (expectedAction != "any" && payload.string("action") != expectedAction) return false
    if (config.boolean("initialOnly") && (payload["repeatCount"].numberOrNull()?.toInt() ?: 0) > 0) return false
    return true
}

internal fun matchesHardwareKeyGesture(config: ConfigMap, payload: ConfigMap): Boolean {
    if (!matchesConfiguredKey(config.keyIdentity(), payload.keyIdentity())) return false
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
    val expected1 = config.keyIdentity("1")
    val expected2 = config.keyIdentity("2")
    if (!expected1.configured || !expected2.configured) return false
    val actual1 = payload.keyIdentity("1")
    val actual2 = payload.keyIdentity("2")
    return (
        matchesConfiguredKey(expected1, actual1) &&
            matchesConfiguredKey(expected2, actual2)
        ) || (
        matchesConfiguredKey(expected1, actual2) &&
            matchesConfiguredKey(expected2, actual1)
        )
}

private fun ConfigMap.keyIdentity(suffix: String = ""): ConfiguredKey {
    val hidden = (this["hardwareIdentity$suffix"] as? ConfigValue.ObjectValue)?.value.orEmpty()
    val directKeyCode = this["keyCode$suffix"].numberOrNull()?.toInt()?.takeIf { it > 0 }
    val learnedKeyCode = hidden["androidKeyCode"].numberOrNull()?.toInt()?.takeIf { it > 0 }
    val learnedMatchesVisible =
        directKeyCode == null || learnedKeyCode == null || directKeyCode == learnedKeyCode

    return ConfiguredKey(
        keyCode = directKeyCode ?: learnedKeyCode,
        scanCode = hidden["androidScanCode"].numberOrNull()?.toInt()?.takeIf { it > 0 }
            ?: this["scanCode$suffix"].numberOrNull()?.toInt()?.takeIf { it > 0 },
        linuxEvKey = if (learnedMatchesVisible) {
            hidden["linuxEvKey"].numberOrNull()?.toInt()?.takeIf { it > 0 }
                ?: this["linuxEvKey$suffix"].numberOrNull()?.toInt()?.takeIf { it > 0 }
        } else {
            null
        },
        mscScan = if (learnedMatchesVisible) {
            hidden["mscScan"].numberOrNull()?.toLong()?.takeIf { it != 0L }
                ?: this["mscScan$suffix"].numberOrNull()?.toLong()?.takeIf { it != 0L }
        } else {
            null
        },
    )
}

private fun matchesConfiguredKey(expected: ConfiguredKey, actual: ConfiguredKey): Boolean {
    if (!expected.configured || !actual.configured) return false
    if (expected.keyCode != null && actual.keyCode != null) return expected.keyCode == actual.keyCode
    if (expected.scanCode != null && actual.scanCode != null) return expected.scanCode == actual.scanCode
    if (expected.linuxEvKey != null && actual.linuxEvKey != null) return expected.linuxEvKey == actual.linuxEvKey
    if (expected.mscScan != null && actual.mscScan != null) return expected.mscScan == actual.mscScan
    return false
}

private data class ConfiguredKey(
    val keyCode: Int?,
    val scanCode: Int?,
    val linuxEvKey: Int?,
    val mscScan: Long?,
) {
    val configured: Boolean
        get() = keyCode != null || scanCode != null || linuxEvKey != null || mscScan != null
}

