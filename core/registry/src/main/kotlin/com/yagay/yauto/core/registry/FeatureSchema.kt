package com.yagay.yauto.core.registry

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef

/**
 * Optional behaviour attached to a descriptor field.
 *
 * FieldSchema describes the value shape; FieldBehavior describes how the generic editor should
 * present it. Keeping those concerns separate lets existing features remain source-compatible while
 * future features gain defaults, conditional UI and variable support without custom Compose code.
 */
enum class ComponentPickerKind { ACTIVITY, SERVICE, RECEIVER, PROVIDER, QUICK_SETTINGS_TILE }
enum class ComponentPickerValueMode { FLATTENED, CLASS_NAME }

data class FieldPickerOption(
    val value: String,
    val label: String = value,
    val detail: String? = null,
)

data class HardwareKeyPickerCatalog(
    val keyCodes: List<FieldPickerOption> = emptyList(),
    val scanCodes: List<FieldPickerOption> = emptyList(),
)

data class HardwareKeyIdentity(
    val androidKeyCode: Int? = null,
    val androidScanCode: Int? = null,
    val linuxEvKey: Int? = null,
    val mscScan: Long? = null,
    val deviceId: Int? = null,
    val deviceName: String? = null,
    val deviceDescriptor: String? = null,
    val vendorId: Int? = null,
    val productId: Int? = null,
    val sources: Set<String> = emptySet(),
)

data class HardwareKeyCaptureResult(
    val keyCode: Int,
    val scanCode: Int,
    val deviceId: Int,
    val action: Int,
    val linuxEvKey: Int = 0,
    val mscScan: Long = 0L,
    val deviceName: String = "",
    val deviceDescriptor: String = "",
    val vendorId: Int = 0,
    val productId: Int = 0,
    val sources: Set<String> = emptySet(),
) {
    fun identity(): HardwareKeyIdentity = HardwareKeyIdentity(
        androidKeyCode = keyCode.takeIf { it > 0 },
        androidScanCode = scanCode.takeIf { it > 0 },
        linuxEvKey = linuxEvKey.takeIf { it > 0 },
        mscScan = mscScan.takeIf { it != 0L },
        deviceId = deviceId.takeIf { it >= 0 },
        deviceName = deviceName.takeIf(String::isNotBlank),
        deviceDescriptor = deviceDescriptor.takeIf(String::isNotBlank),
        vendorId = vendorId.takeIf { it > 0 },
        productId = productId.takeIf { it > 0 },
        sources = sources,
    )
}

sealed interface FieldPickerSource {
    data object InstalledApp : FieldPickerSource

    data class Component(
        val packageFieldKey: String? = null,
        val kinds: Set<ComponentPickerKind> = ComponentPickerKind.entries.toSet(),
        val valueMode: ComponentPickerValueMode = ComponentPickerValueMode.FLATTENED,
    ) : FieldPickerSource

    data class Permission(val packageFieldKey: String = "package") : FieldPickerSource
    data object Subscription : FieldPickerSource
    data object Camera : FieldPickerSource
    data object AndroidUser : FieldPickerSource
    data object TimeZone : FieldPickerSource
    data object Locale : FieldPickerSource
    data object Calendar : FieldPickerSource
    data object InputMethod : FieldPickerSource
    data object KeyCode : FieldPickerSource
    data object ScanCode : FieldPickerSource
    data object WifiSsid : FieldPickerSource
    data object BluetoothDevice : FieldPickerSource
    data object LocationProvider : FieldPickerSource
    data object AppOperation : FieldPickerSource
    data object IntentAction : FieldPickerSource
    data object IntentCategory : FieldPickerSource
    data object NotificationCategory : FieldPickerSource
    data class Options(val options: List<FieldPickerOption>) : FieldPickerSource
}

data class FieldBehavior(
    val defaultValue: ConfigValue? = null,
    val visibleWhen: FieldRule? = null,
    val enabledWhen: FieldRule? = null,
    val advanced: Boolean = false,
    val supportsVariables: Boolean = false,
    val help: String? = null,
    val picker: FieldPickerSource? = null,
    val allowManualInput: Boolean = true,
)

sealed interface FieldRule {
    val fieldKey: String

    data class Equals(
        override val fieldKey: String,
        val expected: ConfigValue,
    ) : FieldRule

    data class NotEquals(
        override val fieldKey: String,
        val expected: ConfigValue,
    ) : FieldRule

    data class Present(override val fieldKey: String) : FieldRule
    data class Truthy(override val fieldKey: String) : FieldRule
}

fun FeatureDescriptor.fieldBehavior(key: String): FieldBehavior = fieldBehaviors[key] ?: FieldBehavior()

/** Applies descriptor defaults without overwriting values explicitly stored by the user. */
fun FeatureDescriptor.applyDefaults(feature: FeatureRef): FeatureRef {
    if (fieldBehaviors.none { (_, behavior) -> behavior.defaultValue != null }) return feature
    val config = buildMap {
        fields.forEach { field -> fieldBehaviors[field.key]?.defaultValue?.let { put(field.key, it) } }
        putAll(feature.config)
    }
    return if (config == feature.config) feature else feature.copy(config = config)
}

fun FieldRule.matches(values: Map<String, ConfigValue>): Boolean {
    val current = values[fieldKey]
    return when (this) {
        is FieldRule.Equals -> current == expected
        is FieldRule.NotEquals -> current != expected
        is FieldRule.Present -> current != null && current != ConfigValue.NullValue && !current.isBlankLike()
        is FieldRule.Truthy -> current.isTruthy()
    }
}

private fun ConfigValue?.isTruthy(): Boolean = when (this) {
    is ConfigValue.BooleanValue -> value
    is ConfigValue.NumberValue -> value != 0.0
    is ConfigValue.StringValue -> value.equals("true", ignoreCase = true) || value == "1"
    else -> false
}

private fun ConfigValue.isBlankLike(): Boolean = when (this) {
    is ConfigValue.StringValue -> value.isBlank()
    ConfigValue.NullValue -> true
    else -> false
}
