package com.yagay.yauto.platform.accessibility

import com.yagay.yauto.core.model.ConfigMap
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

/**
 * Hardware-key events use a layered identity instead of assuming Android KeyCode, Android scanCode
 * and Linux EV_KEY are interchangeable.
 */
class AccessibilityKeyFeaturePack : FeaturePack {
    override val id: String = "accessibility.keys"

    override fun install(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.hardware_key"),
                FeatureKind.EVENT,
                "Hardware key",
                "Run when Accessibility, LSPosed or Root raw input reports a hardware key event",
                FeatureCategory.UI_AUTOMATION,
                schemaVersion = 2,
                implementationOptions = hardwareKeyImplementations(),
                fields = keyFields() + listOf(
                    FieldSchema.Toggle("strictDevice", "Require the learned input device"),
                    FieldSchema.Choice("action", "Key action", options = listOf("any", "down", "up")),
                    FieldSchema.Toggle("initialOnly", "Ignore repeated key-down events"),
                ),
                fieldBehaviors = advancedIdentityBehaviors() +
                    mapOf("strictDevice" to FieldBehavior(advanced = true)),
                keywords = setOf(
                    "hardware key", "button", "volume key", "media key", "keycode", "scancode",
                    "ev_key", "msc_scan", "accessibility", "lsposed", "root", "shortx",
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
                "Run on a single, double, triple or long press derived from the hardware-key stream",
                FeatureCategory.UI_AUTOMATION,
                schemaVersion = 2,
                implementationOptions = hardwareKeyImplementations(),
                fields = keyFields() + listOf(
                    FieldSchema.Toggle("strictDevice", "Require the learned input device"),
                    FieldSchema.Choice(
                        "gesture",
                        "Key gesture",
                        required = true,
                        options = listOf("single_press", "double_press", "triple_press", "long_press"),
                    ),
                    FieldSchema.Duration("longPressMs", "Long-press threshold"),
                ),
                fieldBehaviors = advancedIdentityBehaviors() +
                    mapOf("strictDevice" to FieldBehavior(advanced = true)),
                keywords = setOf(
                    "hardware key", "button", "gesture", "double press", "triple press",
                    "long press", "shortx", "lsposed", "root", "keycode", "scancode", "ev_key",
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
                "Run when two physical keys are held together",
                FeatureCategory.UI_AUTOMATION,
                schemaVersion = 2,
                implementationOptions = hardwareKeyImplementations(),
                fields = keyFields("1", "First") + keyFields("2", "Second") +
                    FieldSchema.Toggle("strictDevice", "Require the learned input device"),
                fieldBehaviors = advancedIdentityBehaviors("1") +
                    advancedIdentityBehaviors("2") +
                    mapOf("strictDevice" to FieldBehavior(advanced = true)),
                keywords = setOf(
                    "hardware key", "button", "combination", "combo", "two keys",
                    "shortx", "lsposed", "root", "keycode", "scancode", "ev_key",
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
            FieldSchema.Number("linuxEvKey$suffix", "${prefix}Linux EV_KEY code", min = 0.0, max = 65535.0),
            FieldSchema.Number("mscScan$suffix", "${prefix}Linux MSC_SCAN value", min = 0.0),
            FieldSchema.Text("deviceDescriptor$suffix", "${prefix}Input device descriptor"),
            FieldSchema.Text("deviceName$suffix", "${prefix}Input device name"),
            FieldSchema.Number("vendorId$suffix", "${prefix}Input vendor ID", min = 0.0),
            FieldSchema.Number("productId$suffix", "${prefix}Input product ID", min = 0.0),
        )
    }

    private fun advancedIdentityBehaviors(suffix: String = ""): Map<String, FieldBehavior> =
        mapOf(
            "linuxEvKey$suffix" to FieldBehavior(advanced = true),
            "mscScan$suffix" to FieldBehavior(advanced = true),
            "deviceDescriptor$suffix" to FieldBehavior(advanced = true),
            "deviceName$suffix" to FieldBehavior(advanced = true),
            "vendorId$suffix" to FieldBehavior(advanced = true),
            "productId$suffix" to FieldBehavior(advanced = true),
        )
}

internal fun matchesHardwareKey(config: ConfigMap, payload: ConfigMap): Boolean {
    if (!matchesConfiguredKey(config.keyIdentity(), payload.keyIdentity(), config.boolean("strictDevice"))) {
        return false
    }
    val expectedAction = config.string("action", "any")
    if (expectedAction != "any" && payload.string("action") != expectedAction) return false
    if (config.boolean("initialOnly") && (payload["repeatCount"].numberOrNull()?.toInt() ?: 0) > 0) return false
    return true
}

internal fun matchesHardwareKeyGesture(config: ConfigMap, payload: ConfigMap): Boolean {
    if (!matchesConfiguredKey(config.keyIdentity(), payload.keyIdentity(), config.boolean("strictDevice"))) {
        return false
    }
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
    val strict = config.boolean("strictDevice")
    return (
        matchesConfiguredKey(expected1, actual1, strict) &&
            matchesConfiguredKey(expected2, actual2, strict)
        ) || (
        matchesConfiguredKey(expected1, actual2, strict) &&
            matchesConfiguredKey(expected2, actual1, strict)
        )
}

private fun ConfigMap.keyIdentity(suffix: String = ""): ConfiguredKey = ConfiguredKey(
    keyCode = this["keyCode$suffix"].numberOrNull()?.toInt()?.takeIf { it > 0 },
    scanCode = this["scanCode$suffix"].numberOrNull()?.toInt()?.takeIf { it > 0 },
    linuxEvKey = this["linuxEvKey$suffix"].numberOrNull()?.toInt()?.takeIf { it > 0 },
    mscScan = this["mscScan$suffix"].numberOrNull()?.toLong()?.takeIf { it != 0L },
    deviceDescriptor = string("deviceDescriptor$suffix").trim(),
    deviceName = string("deviceName$suffix").trim(),
    vendorId = this["vendorId$suffix"].numberOrNull()?.toInt()?.takeIf { it > 0 },
    productId = this["productId$suffix"].numberOrNull()?.toInt()?.takeIf { it > 0 },
)

private fun matchesConfiguredKey(
    expected: ConfiguredKey,
    actual: ConfiguredKey,
    strictDevice: Boolean,
): Boolean {
    if (!expected.configured || !actual.configured) return false
    if (strictDevice && !deviceCompatible(expected, actual)) return false

    if (expected.keyCode != null && actual.keyCode != null) return expected.keyCode == actual.keyCode
    if (expected.scanCode != null && actual.scanCode != null) return expected.scanCode == actual.scanCode
    if (expected.linuxEvKey != null && actual.linuxEvKey != null) return expected.linuxEvKey == actual.linuxEvKey
    if (expected.mscScan != null && actual.mscScan != null) return expected.mscScan == actual.mscScan
    return false
}

private fun deviceCompatible(expected: ConfiguredKey, actual: ConfiguredKey): Boolean {
    if (
        expected.deviceDescriptor.isNotBlank() &&
        actual.deviceDescriptor.isNotBlank() &&
        expected.deviceDescriptor != actual.deviceDescriptor
    ) return false
    if (expected.vendorId != null && actual.vendorId != null && expected.vendorId != actual.vendorId) return false
    if (expected.productId != null && actual.productId != null && expected.productId != actual.productId) return false
    return true
}

private data class ConfiguredKey(
    val keyCode: Int?,
    val scanCode: Int?,
    val linuxEvKey: Int?,
    val mscScan: Long?,
    val deviceDescriptor: String,
    val deviceName: String,
    val vendorId: Int?,
    val productId: Int?,
) {
    val configured: Boolean
        get() = keyCode != null || scanCode != null || linuxEvKey != null || mscScan != null
}
