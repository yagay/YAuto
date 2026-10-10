package com.yagay.yauto.ui.editor

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.UserHandle
import android.os.UserManager
import android.provider.CalendarContract
import android.telephony.SubscriptionManager
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.registry.*
import com.yagay.yauto.ui.design.CapabilityBadge
import com.yagay.yauto.ui.design.R as TextR
import com.yagay.yauto.ui.design.localizedList
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Generic configuration screen driven entirely by [FeatureDescriptor]. */
@Composable
internal fun GenericFeatureConfigEditor(
    modifier: Modifier,
    descriptor: FeatureDescriptor,
    initial: FeatureRef?,
    accent: Color,
    leadingContent: (@Composable () -> Unit)? = null,
    onSave: (FeatureRef) -> Unit,
) {
    val locale = currentEditorLocale()
    val initialWithDefaults = remember(descriptor, initial) {
        descriptor.applyDefaults(initial ?: FeatureRef(descriptor.id.value, descriptor.schemaVersion))
    }
    val initialTexts = remember(descriptor, initialWithDefaults, locale) {
        descriptor.fields.associate { field ->
            field.key to if (field.key == FEATURE_METHOD_CONFIG_KEY)
                initialWithDefaults.preferredMethod().id
            else editorConfigValueText(initialWithDefaults.config[field.key], locale)
        }
    }
    var values by remember(descriptor.id.value, initial, locale) { mutableStateOf(initialTexts) }
    var hardwareIdentityConfig by remember(descriptor.id.value, initial) {
        mutableStateOf(
            initial?.config.orEmpty().filterKeys { it.startsWith("hardwareIdentity") }
        )
    }
    var showAdvanced by remember(descriptor.id.value) { mutableStateOf(false) }

    val typedValues = remember(descriptor, values, locale) {
        valuesAsConfig(descriptor.fields, values, locale)
    }
    val semanticallyVisible = remember(descriptor, typedValues) {
        descriptor.fields.filter { field ->
            descriptor.fieldBehavior(field.key).visibleWhen?.matches(typedValues) != false
        }
    }
    val displayedFields = remember(descriptor, semanticallyVisible, showAdvanced) {
        semanticallyVisible.filter { field ->
            showAdvanced || !descriptor.fieldBehavior(field.key).advanced
        }
    }
    val valid = semanticallyVisible.all { field ->
        val enabled = descriptor.fieldBehavior(field.key).enabledWhen?.matches(typedValues) != false
        !enabled || fieldValid(field, values[field.key].orEmpty(), locale)
    }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leadingContent?.let { content ->
            item(key = "leading_content") { content() }
        }

        item { FeatureSummaryCard(descriptor, accent) }

        val backends = descriptor.resolvedImplementationOptions()
        if (backends.isNotEmpty()) {
            item {
                ImplementationGuide(
                    descriptor,
                    if (backends.size == 1) backends.first().backendId.orEmpty()
                    else values[FEATURE_BACKEND_CONFIG_KEY].orEmpty().ifBlank { "auto" },
                    values[FEATURE_METHOD_CONFIG_KEY].orEmpty().ifBlank { "auto" },
                )
            }
        }

        if (descriptor.fields.isEmpty()) {
            item {
                Text(
                    stringResource(TextR.string.feature_picker_no_parameters),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (descriptor.fields.any { descriptor.fieldBehavior(it.key).advanced }) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(TextR.string.category_advanced),
                        Modifier.weight(1f),
                        fontWeight = FontWeight.Medium,
                    )
                    Switch(checked = showAdvanced, onCheckedChange = { showAdvanced = it })
                }
            }
        }

        items(displayedFields, key = { it.key }) { field ->
            val behavior = descriptor.fieldBehavior(field.key)
            val enabled = behavior.enabledWhen?.matches(typedValues) != false
            if (field.key == FEATURE_METHOD_CONFIG_KEY && field is FieldSchema.Choice) {
                MethodChoiceEditor(
                    selected = values[field.key].orEmpty().ifBlank { "auto" },
                    enabled = enabled,
                    onValue = { values = values + (field.key to it) },
                )
            } else if (field.key == FEATURE_BACKEND_CONFIG_KEY && field is FieldSchema.Choice) {
                BackendChoiceEditor(
                    descriptor = descriptor,
                    field = field,
                    value = values[field.key].orEmpty(),
                    enabled = enabled,
                    onValue = { values = values + (field.key to it) },
                )
            } else {
                FieldEditor(
                    descriptor = descriptor,
                    field = field,
                    behavior = behavior,
                    allValues = values,
                    value = values[field.key].orEmpty(),
                    enabled = enabled,
                    onValue = { next ->
                        values = values + (field.key to next)
                        if (field.key.startsWith("keyCode")) {
                            val suffix = field.key.removePrefix("keyCode")
                            hardwareIdentityConfig = hardwareIdentityConfig - "hardwareIdentity$suffix"
                        }
                    },
                    onRelatedValue = { key, value -> values = values + (key to value) },
                    onHardwareIdentity = { suffix, captured ->
                        hardwareIdentityConfig = hardwareIdentityConfig + (
                            "hardwareIdentity$suffix" to captured.toHiddenHardwareIdentity()
                        )
                    },
                )
            }
        }

        item {
            Button(
                onClick = {
                    onSave(
                        FeatureRef(
                            descriptor.id.value,
                            descriptor.schemaVersion,
                            buildEditedFeatureConfig(
                                descriptor,
                                initial,
                                initialWithDefaults,
                                values,
                                locale,
                                hardwareIdentityConfig,
                            ),
                        )
                    )
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (initial == null) {
                        stringResource(TextR.string.editor_add_kind_format, kindLabel(descriptor.kind))
                    } else {
                        stringResource(TextR.string.common_save)
                    }
                )
            }
        }
    }
}

@Composable
private fun FeatureSummaryCard(descriptor: FeatureDescriptor, accent: Color) {
    Card(colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = .10f))) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(localizedFeatureDescriptionShared(descriptor))
            Text(
                stringResource(TextR.string.feature_picker_feature_id_format, descriptor.id.value),
                style = MaterialTheme.typography.labelSmall,
            )
            if (descriptor.minSdk > 31) {
                Text(
                    stringResource(TextR.string.feature_picker_min_api_format, descriptor.minSdk),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            AccessRequirementBadges(descriptor)
            if (descriptor.capabilities.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    descriptor.capabilities.take(4).forEach {
                        CapabilityBadge(capabilityLabel(it.value))
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessRequirementBadges(descriptor: FeatureDescriptor) {
    val options = descriptor.resolvedImplementationOptions()
    // Alternatives are NOT cumulative requirements: Root OR Shizuku must not appear
    // as if both need granting. Each option's requirements appear in its own guide.
    val optionOnly = options.flatMap { it.requirements }.toSet()
    val requirements = if (options.size > 1) {
        // Do not turn possible paths inferred from a broad capability into mandatory
        // permissions; the implementation guide is the source of backend requirements.
        descriptor.accessRequirements - optionOnly
    } else {
        descriptor.resolvedAccessRequirements()
    }
    if (requirements.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        requirements.forEach { CapabilityBadge(accessRequirementLabel(it)) }
    }
}

@Composable
private fun ImplementationGuide(
    descriptor: FeatureDescriptor,
    requestedBackendId: String,
    requestedMethod: String,
) {
    val options = descriptor.resolvedImplementationOptions()
    val selected = options.firstOrNull { it.backendId == requestedBackendId }
    val backendId = selected?.backendId ?: "auto"
    val resolver = rememberFeatureTextResolver()
    val optionPermissions = options.flatMap { it.requirements }.toSet()
    val additionalPermissions = descriptor.accessRequirements - optionPermissions

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(TextR.string.implementation_method), fontWeight = FontWeight.SemiBold)
            if (descriptor.hasDualMethodRoutes()) {
                Text(
                    when (requestedMethod) {
                        "macrodroid" -> stringResource(TextR.string.implementation_family_macro_detail)
                        "shortx" -> stringResource(TextR.string.implementation_family_shortx_detail)
                        else -> stringResource(TextR.string.implementation_family_auto_detail)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!descriptor.hasDualMethodRoutes() || requestedMethod == "shortx") {
                Text(implementationTitle(backendId), style = MaterialTheme.typography.titleSmall)
            }
            if (!descriptor.hasDualMethodRoutes() || requestedMethod == "shortx") {
                Text(
                    resolver.backendPermissionExplanation(descriptor, backendId),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (descriptor.hasDualMethodRoutes() && requestedMethod != "shortx") {
                Text(
                    stringResource(TextR.string.implementation_family_accessibility_required),
                    style = MaterialTheme.typography.labelSmall,
                )
            } else if (selected != null) {
                if (selected.requirements.isNotEmpty()) {
                    Text(
                        stringResource(
                            TextR.string.implementation_requirements_format,
                            localizedList(selected.requirements.map { accessRequirementLabelNonComposable(it) }),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (selected.restartRequired) {
                    Text(
                        stringResource(TextR.string.implementation_restart_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // Auto does not itself grant access; show the separate permission
                // routes that the engine may choose, without suggesting they are all required.
                val paths = options.map { option ->
                    val title = implementationTitle(option.backendId.orEmpty())
                    val requirementLabels = option.requirements.map { accessRequirementLabelNonComposable(it) }
                    if (requirementLabels.isEmpty()) title
                    else "$title: ${localizedList(requirementLabels)}"
                }
                Text(
                    stringResource(
                        TextR.string.feature_backend_auto_paths_format,
                        localizedList(paths),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            if (additionalPermissions.isNotEmpty()) {
                Text(
                    stringResource(
                        TextR.string.feature_backend_additional_requirements_format,
                        localizedList(additionalPermissions.map { accessRequirementLabelNonComposable(it) }),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            resolver.backendLimitation(backendId)?.let { limitation ->
                Text(
                    limitation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MethodChoiceEditor(
    selected: String,
    enabled: Boolean,
    onValue: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(TextR.string.implementation_method), fontWeight = FontWeight.Medium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "auto" to TextR.string.implementation_auto_title,
                "macrodroid" to TextR.string.implementation_family_macro_title,
                "shortx" to TextR.string.implementation_family_shortx_title,
            ).forEach { (value, label) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onValue(value) },
                    enabled = enabled,
                    label = { Text(stringResource(label)) },
                )
            }
        }
    }
}

@Composable
private fun BackendChoiceEditor(
    descriptor: FeatureDescriptor,
    field: FieldSchema.Choice,
    value: String,
    enabled: Boolean,
    onValue: (String) -> Unit,
) {
    val selected = value.ifBlank { "auto" }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(
                if (descriptor.hasDualMethodRoutes())
                    TextR.string.implementation_family_shortx_backend
                else TextR.string.implementation_method,
            ),
            fontWeight = FontWeight.Medium,
        )
        field.options.forEach { option ->
            val supported = option == "auto" || descriptor.resolvedImplementationOptions().any { it.backendId == option }
            if (supported) {
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = enabled) { onValue(option) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected == option, onClick = { onValue(option) }, enabled = enabled)
                    Text(implementationTitle(option))
                }
            }
        }
    }
}

@Composable
private fun FieldEditor(
    descriptor: FeatureDescriptor,
    field: FieldSchema,
    behavior: FieldBehavior,
    allValues: Map<String, String>,
    value: String,
    enabled: Boolean,
    onValue: (String) -> Unit,
    onRelatedValue: (String, String) -> Unit,
    onHardwareIdentity: (String, HardwareKeyCaptureResult) -> Unit,
) {
    val descriptorId = descriptor.id.value
    val label = localizedFieldLabelShared(descriptorId, field)
    val variableNames = LocalEditorVariableNames.current
    val variablePicker = if (field is FieldSchema.Variable && variableNames.isNotEmpty()) {
        FieldPickerSource.Options(
            variableNames.sorted().map { FieldPickerOption(it) }
        )
    } else {
        null
    }
    val picker = behavior.picker ?: variablePicker ?: inferredPickerSource(descriptor, field)
    when {
        field is FieldSchema.Toggle -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label)
                if (field.required) {
                    Text(stringResource(TextR.string.editor_required), style = MaterialTheme.typography.labelSmall)
                }
            }
            Switch(
                checked = value.toBooleanStrictOrNull() ?: false,
                onCheckedChange = { onValue(it.toString()) },
                enabled = enabled,
            )
        }
        field is FieldSchema.Choice -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, fontWeight = FontWeight.Medium)
            field.options.forEach { option ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = enabled) { onValue(option) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(value == option, { onValue(option) }, enabled = enabled)
                    Text(localizedChoiceOptionShared(descriptorId, field.key, option))
                }
            }
        }
        field is FieldSchema.AppPicker -> InstalledAppField(descriptorId, field, value, enabled, onValue)
        picker != null -> PickerBackedField(
            descriptorId = descriptorId,
            field = field,
            picker = picker,
            allValues = allValues,
            value = value,
            enabled = enabled,
            allowManualInput = behavior.allowManualInput,
            onValue = onValue,
            onHardwareKeyCaptured = { captured ->
                if (field.key.startsWith("keyCode")) {
                    val suffix = field.key.removePrefix("keyCode")
                    onHardwareIdentity(suffix, captured)
                }
            },
        )
        else -> {
            val numeric = field is FieldSchema.Number || field is FieldSchema.Duration
            OutlinedTextField(
                value = value,
                onValueChange = onValue,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(
                        if (field.required) stringResource(TextR.string.editor_required_field_format, label)
                        else label
                    )
                },
                minLines = if (field is FieldSchema.Text && field.multiline) 3 else 1,
                keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
                supportingText = when {
                    field is FieldSchema.Variable || behavior.supportsVariables ->
                        ({ Text(stringResource(TextR.string.feature_picker_variable_hint)) })
                    field is FieldSchema.Duration ->
                        ({ Text(stringResource(TextR.string.feature_picker_milliseconds)) })
                    else -> null
                },
            )
        }
    }
}


/**
 * Serialize only fields applicable to the currently selected mode. Retain unknown imported
 * config keys for forward compatibility, but do not leak hidden/disabled schema fields when
 * the user changes an operation's settings.
 */
internal fun buildEditedFeatureConfig(
    descriptor: FeatureDescriptor,
    initial: FeatureRef?,
    initialWithDefaults: FeatureRef,
    values: Map<String, String>,
    locale: Locale,
    hardwareIdentityConfig: Map<String, ConfigValue> = emptyMap(),
): Map<String, ConfigValue> {
    val typedValues = valuesAsConfig(descriptor.fields, values, locale)
    val config = initial?.config.orEmpty().toMutableMap()
    descriptor.fields.forEach { field ->
        val prior = config.remove(field.key)
        val behavior = descriptor.fieldBehavior(field.key)
        if (behavior.visibleWhen?.matches(typedValues) == false ||
            behavior.enabledWhen?.matches(typedValues) == false
        ) return@forEach

        val raw = values[field.key].orEmpty()
        if (prior != null && raw == editorConfigValueText(initialWithDefaults.config[field.key], locale)) {
            config[field.key] = prior
        } else {
            parseFieldValue(field, raw, locale)?.let { config[field.key] = it }
        }
    }
    config.keys.filter { it.startsWith("hardwareIdentity") }.forEach(config::remove)
    config.putAll(hardwareIdentityConfig)
    return config
}

private fun valuesAsConfig(
    fields: List<FieldSchema>,
    values: Map<String, String>,
    locale: Locale,
): Map<String, ConfigValue> = buildMap {
    fields.forEach { field ->
        parseFieldValue(field, values[field.key].orEmpty(), locale)?.let { put(field.key, it) }
    }
}

private fun parseFieldValue(field: FieldSchema, raw: String, locale: Locale): ConfigValue? = when (field) {
    is FieldSchema.Toggle -> ConfigValue.BooleanValue(raw.toBooleanStrictOrNull() ?: false)
    is FieldSchema.Number, is FieldSchema.Duration -> parseLocalizedDouble(raw, locale)?.let(ConfigValue::NumberValue)
    else -> if (raw.isNotEmpty() || field.required) ConfigValue.StringValue(raw) else null
}

private fun fieldValid(field: FieldSchema, raw: String, locale: Locale): Boolean = when (field) {
    is FieldSchema.Number -> (raw.isBlank() && !field.required) || parseLocalizedDouble(raw, locale)?.let { number ->
        number.isFinite() && (field.min?.let { number >= it } ?: true) && (field.max?.let { number <= it } ?: true)
    } == true
    is FieldSchema.Duration -> (raw.isBlank() && !field.required) || parseLocalizedDouble(raw, locale)?.let {
        it.isFinite() && it >= 0
    } == true
    is FieldSchema.Choice -> (raw.isBlank() && !field.required) || raw in field.options
    is FieldSchema.Toggle -> true
    else -> !field.required || raw.isNotBlank()
}

@Composable
private fun capabilityLabel(capabilityId: String): String = stringResource(
    when (capabilityId) {
        "privileged.shell" -> TextR.string.capability_privileged_shell
        "android.app.launch" -> TextR.string.capability_app_launch
        "android.toast" -> TextR.string.capability_toast
        "android.accessibility" -> TextR.string.capability_accessibility
        "android.notification_listener" -> TextR.string.capability_notification_listener
        "android.systemui" -> TextR.string.capability_system_ui
        "android.lsposed" -> TextR.string.capability_lsposed
        else -> TextR.string.capability_other
    }
)

@Composable
private fun accessRequirementLabel(requirement: AccessRequirement): String =
    stringResource(accessRequirementResource(requirement))

@Composable
private fun accessRequirementLabelNonComposable(requirement: AccessRequirement): String =
    stringResource(accessRequirementResource(requirement))

@StringRes
private fun accessRequirementResource(requirement: AccessRequirement): Int = when (requirement) {
    AccessRequirement.ROOT -> TextR.string.access_root
    AccessRequirement.SHIZUKU -> TextR.string.access_shizuku
    AccessRequirement.LSPOSED -> TextR.string.access_lsposed
    AccessRequirement.ZYGISK -> TextR.string.access_zygisk
    AccessRequirement.ACCESSIBILITY -> TextR.string.access_accessibility
    AccessRequirement.USAGE_STATS -> TextR.string.access_usage_stats
    AccessRequirement.NOTIFICATION_LISTENER -> TextR.string.access_notification_listener
    AccessRequirement.POST_NOTIFICATIONS -> TextR.string.access_post_notifications
    AccessRequirement.OVERLAY -> TextR.string.access_overlay
    AccessRequirement.WRITE_SETTINGS -> TextR.string.access_write_settings
    AccessRequirement.CAMERA -> TextR.string.access_camera
    AccessRequirement.LOCATION -> TextR.string.access_location
    AccessRequirement.BLUETOOTH_CONNECT -> TextR.string.access_bluetooth
    AccessRequirement.DND_POLICY -> TextR.string.access_dnd_policy
    AccessRequirement.DEVICE_ADMIN -> TextR.string.access_device_admin
    AccessRequirement.CALENDAR -> TextR.string.access_calendar
    AccessRequirement.CONTACTS -> TextR.string.access_contacts
    AccessRequirement.CALL_LOG -> TextR.string.access_call_log
    AccessRequirement.SMS -> TextR.string.access_sms
    AccessRequirement.PHONE -> TextR.string.access_phone
    AccessRequirement.RECORD_AUDIO -> TextR.string.access_record_audio
    AccessRequirement.ACTIVITY_RECOGNITION -> TextR.string.access_activity_recognition
}

@Composable
private fun implementationTitle(backendId: String): String = stringResource(
    when (backendId) {
        "auto" -> TextR.string.implementation_auto_title
        "root" -> TextR.string.implementation_root_title
        "shizuku" -> TextR.string.implementation_shizuku_title
        "lsposed" -> TextR.string.implementation_lsposed_title
        "accessibility" -> TextR.string.implementation_accessibility_title
        "usage_stats" -> TextR.string.implementation_usage_stats_title
        else -> TextR.string.implementation_method
    }
)

private fun HardwareKeyCaptureResult.toHiddenHardwareIdentity(): ConfigValue.ObjectValue =
    ConfigValue.ObjectValue(
        buildMap {
            if (keyCode > 0) put("androidKeyCode", ConfigValue.NumberValue(keyCode.toDouble()))
            if (scanCode > 0) put("androidScanCode", ConfigValue.NumberValue(scanCode.toDouble()))
            if (linuxEvKey > 0) put("linuxEvKey", ConfigValue.NumberValue(linuxEvKey.toDouble()))
            if (mscScan != 0L) put("mscScan", ConfigValue.NumberValue(mscScan.toDouble()))
            if (deviceId >= 0) put("deviceId", ConfigValue.NumberValue(deviceId.toDouble()))
            if (deviceName.isNotBlank()) put("deviceName", ConfigValue.StringValue(deviceName))
            if (deviceDescriptor.isNotBlank()) put("deviceDescriptor", ConfigValue.StringValue(deviceDescriptor))
            if (vendorId > 0) put("vendorId", ConfigValue.NumberValue(vendorId.toDouble()))
            if (productId > 0) put("productId", ConfigValue.NumberValue(productId.toDouble()))
            if (sources.isNotEmpty()) {
                put("sources", ConfigValue.ListValue(sources.sorted().map(ConfigValue::StringValue)))
            }
        }
    )
