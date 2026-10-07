package com.yagay.yauto.platform.accessibility

import com.yagay.yauto.core.model.ConfigMap
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

/** Android-facing hardware-key triggers with hidden OEM raw-input fallback identity. */
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
                schemaVersion = 2,
                implementationOptions = hardwareKeyImplementations(),
                fields = keyFields() + listOf(
                    FieldSchema.Choice("action", "Key action", options = listOf("any", "down", "up")),
                    FieldSchema.Toggle("initialOnly", "Ignore repeated key-down events"),
                ),
                keywords = setOf(
                    "hardware key", "button", "volume key", "media key", "keycode", "scancode",
                    "accessibility", "lsposed", "root", "shortx", "oem key",
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
                "Run on a single, double, triple or long press of a physical Android key",
                FeatureCategory.UI_AUTOMATION,
                schemaVersion = 2,
                implementationOptions = hardwareKeyImplementations(),
                fields = keyFields() + listOf(
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
                    "long press", "shortx", "lsposed", "root", "keycode", "scancode", "oem key",
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
                "Run when two physical Android keys are held together",
                FeatureCategory.UI_AUTOMATION,
                schemaVersion = 2,
                implementationOptions = hardwareKeyImplementations(),
                fields = keyFields("1", "First") + keyFields("2", "Second"),
                keywords = setOf(
                    "hardware key", "button", "combination", "combo", "two keys",
                    "shortx", "lsposed", "root", "keycode", "scancode", "oem key",
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
        FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
    )

    private fun keyFields(suffix: String = "", ordinal: String = ""): List<FieldSchema> {
        val prefix = if (ordinal.isBlank()) "" else "$ordinal "
        return listOf(
            FieldSchema.Number("keyCode$suffix", "${prefix}Android key code", min = 0.0, max = 4096.0),
            FieldSchema.Number("scanCode$suffix", "${prefix}Android scan code", min = 0.0, max = 65535.0),
        )
    }
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

/**
 * User-facing Android codes are kept as normal fields. OEM raw values live in a hidden object and
 * are used only when the current backend cannot provide an Android KeyEvent.
 */
private fun ConfigMap.keyIdentity(suffix: String = ""): ConfiguredKey {
    val hidden = (this["hardwareIdentity$suffix"] as? ConfigValue.ObjectValue)?.value.orEmpty()
    val directKeyCode = this["keyCode$suffix"].numberOrNull()?.toInt()?.takeIf { it > 0 }
    val directScanCode = this["scanCode$suffix"].numberOrNull()?.toInt()?.takeIf { it > 0 }
    val learnedKeyCode = hidden["androidKeyCode"].numberOrNull()?.toInt()?.takeIf { it > 0 }
    val learnedScanCode = hidden["androidScanCode"].numberOrNull()?.toInt()?.takeIf { it > 0 }

    val learnedStillMatchesVisible =
        (directKeyCode == null || learnedKeyCode == null || directKeyCode == learnedKeyCode) &&
            (directScanCode == null || learnedScanCode == null || directScanCode == learnedScanCode)

    return ConfiguredKey(
        keyCode = directKeyCode ?: learnedKeyCode,
        scanCode = directScanCode ?: learnedScanCode,
        linuxEvKey = if (learnedStillMatchesVisible) {
            hidden["linuxEvKey"].numberOrNull()?.toInt()?.takeIf { it > 0 }
                ?: this["linuxEvKey$suffix"].numberOrNull()?.toInt()?.takeIf { it > 0 }
        } else {
            null
        },
        mscScan = if (learnedStillMatchesVisible) {
            hidden["mscScan"].numberOrNull()?.toLong()?.takeIf { it != 0L }
                ?: this["mscScan$suffix"].numberOrNull()?.toLong()?.takeIf { it != 0L }
        } else {
            null
        },
    )
}

private fun matchesConfiguredKey(expected: ConfiguredKey, actual: ConfiguredKey): Boolean {
    if (!expected.configured || !actual.configured) return false

    // Prefer Android's logical key identity whenever both sides have it.
    if (expected.keyCode != null && actual.keyCode != null) return expected.keyCode == actual.keyCode
    if (expected.scanCode != null && actual.scanCode != null) return expected.scanCode == actual.scanCode

    // Raw Linux values are Android OEM fallbacks only; they are never user-facing primary fields.
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
