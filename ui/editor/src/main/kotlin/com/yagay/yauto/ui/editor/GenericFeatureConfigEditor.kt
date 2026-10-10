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
import android.content.ClipData
import android.content.ClipboardManager
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
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
            field.key to editorConfigValueText(initialWithDefaults.config[field.key], locale)
        }
    }
    var values by remember(descriptor.id.value, initial, locale) { mutableStateOf(initialTexts) }
    var hardwareIdentityConfig by remember(descriptor.id.value, initial) {
        mutableStateOf(
            initial?.config.orEmpty().filterKeys { it.startsWith("hardwareIdentity") }
        )
    }
    val editorContext = LocalContext.current
    var showAdvanced by remember(descriptor.id.value) {
        mutableStateOf(EditorDisplayPreferences.showAdvancedByDefault(editorContext))
    }

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
            field.key != FEATURE_METHOD_CONFIG_KEY && field.key != FEATURE_BACKEND_CONFIG_KEY &&
                (showAdvanced || !descriptor.fieldBehavior(field.key).advanced)
        }
    }
    val valid = semanticallyVisible.filterNot { it.key == FEATURE_METHOD_CONFIG_KEY ||
        it.key == FEATURE_BACKEND_CONFIG_KEY }.all { field ->
        val enabled = descriptor.fieldBehavior(field.key).enabledWhen?.matches(typedValues) != false
        !enabled || fieldValid(field, values[field.key].orEmpty(), locale)
    }

    val tester = LocalFeatureTestGateway.current
    val coroutineScope = rememberCoroutineScope()
    var testing by remember(descriptor.id.value) { mutableStateOf(false) }
    var confirmationRequested by remember(descriptor.id.value) { mutableStateOf(false) }
    var resetRequested by remember(descriptor.id.value) { mutableStateOf(false) }
    var testResult by remember(descriptor.id.value) { mutableStateOf<FeatureTestResult?>(null) }
    var copied by remember(descriptor.id.value) { mutableStateOf(false) }

    fun currentDraft(): FeatureRef = FeatureRef(
        descriptor.id.value,
        descriptor.schemaVersion,
        buildEditedFeatureConfig(
            descriptor, initial, initialWithDefaults, values, locale, hardwareIdentityConfig,
        ),
    )

    fun runTest() {
        if (!valid || testing || tester == null) return
        val draft = currentDraft()
        testing = true
        testResult = null
        coroutineScope.launch {
            try {
                testResult = tester.test(draft, descriptor.kind)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                testResult = FeatureTestResult(
                    success = false,
                    detail = error.message ?: error.javaClass.simpleName,
                    elapsedMs = 0,
                    kind = descriptor.kind,
                )
            } finally {
                testing = false
            }
        }
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

        if (descriptor.resolvedImplementationOptions().isNotEmpty() ||
            descriptor.resolvedAccessRequirements().isNotEmpty()) {
            item { AutoImplementationSummary(descriptor, initial) }
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

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            if (descriptor.kind == FeatureKind.ACTION) {
                                confirmationRequested = true
                            } else runTest()
                        },
                        enabled = valid && !testing && tester != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(when {
                            testing -> TextR.string.feature_test_running
                            descriptor.kind == FeatureKind.ACTION -> TextR.string.feature_test_action
                            descriptor.kind == FeatureKind.CONDITION -> TextR.string.feature_test_constraint
                            descriptor.kind == FeatureKind.STATE -> TextR.string.feature_test_state
                            else -> TextR.string.feature_test_trigger
                        }))
                    }
                    OutlinedButton(
                        onClick = {
                            val manager = editorContext.getSystemService(Context.CLIPBOARD_SERVICE)
                                as? ClipboardManager
                            if (manager != null) {
                                manager.setPrimaryClip(ClipData.newPlainText(
                                    "YAuto feature", Json.encodeToString(
                                        FeatureRef.serializer(), currentDraft(),
                                    ),
                                ))
                                copied = true
                            }
                        },
                        enabled = valid,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(TextR.string.feature_test_copy))
                    }
                    OutlinedButton(
                        onClick = { resetRequested = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(TextR.string.feature_test_reset))
                    }
                }
                if (copied) {
                    Text(stringResource(TextR.string.feature_test_copied),
                        style = MaterialTheme.typography.labelSmall)
                }
                testResult?.let { result ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(if (result.success)
                                    TextR.string.feature_test_success
                                else TextR.string.feature_test_failed),
                                fontWeight = FontWeight.SemiBold,
                                color = if (result.success) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error,
                            )
                            Text(result.detail, style = MaterialTheme.typography.bodySmall)
                            Text(
                                stringResource(TextR.string.feature_test_duration,
                                    result.elapsedMs),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            result.executionId?.let { id ->
                                Text(id, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Button(
                    onClick = { onSave(currentDraft()) },
                    enabled = valid && !testing,
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

    if (confirmationRequested) {
        AlertDialog(
            onDismissRequest = { confirmationRequested = false },
            title = { Text(stringResource(TextR.string.feature_test_action)) },
            text = { Text(stringResource(TextR.string.feature_test_action_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmationRequested = false
                    runTest()
                }) { Text(stringResource(TextR.string.feature_test_run_now)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmationRequested = false }) {
                    Text(stringResource(TextR.string.common_cancel))
                }
            },
        )
    }
    if (resetRequested) {
        AlertDialog(
            onDismissRequest = { resetRequested = false },
            title = { Text(stringResource(TextR.string.feature_test_reset)) },
            text = { Text(stringResource(TextR.string.feature_test_reset_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    // Reset only the draft, not the persisted automation.
                    val defaults = descriptor.applyDefaults(
                        FeatureRef(descriptor.id.value, descriptor.schemaVersion),
                    )
                    values = descriptor.fields.associate { field ->
                        field.key to editorConfigValueText(defaults.config[field.key], locale)
                    }
                    hardwareIdentityConfig = emptyMap()
                    testResult = null
                    copied = false
                    resetRequested = false
                }) { Text(stringResource(TextR.string.feature_test_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { resetRequested = false }) {
                    Text(stringResource(TextR.string.common_cancel))
                }
            },
        )
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
internal fun accessRequirementLabelNonComposable(requirement: AccessRequirement): String =
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
