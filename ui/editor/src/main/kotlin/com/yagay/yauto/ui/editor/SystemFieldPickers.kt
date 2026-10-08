package com.yagay.yauto.ui.editor

import android.app.AppOpsManager
import android.app.Notification
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.UserManager
import android.provider.CalendarContract
import android.telephony.SubscriptionManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yagay.yauto.core.registry.*
import com.yagay.yauto.ui.design.R as TextR
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class InstalledApp(val label: String, val packageName: String, val system: Boolean)

internal data class SystemPickerOption(
    val value: String,
    val label: String,
    val detail: String? = null,
)

internal fun inferredPickerSource(
    descriptor: FeatureDescriptor,
    field: FieldSchema,
): FieldPickerSource? {
    if (field !is FieldSchema.Text && field !is FieldSchema.Number) return null
    return when (field.key) {
        "package" -> FieldPickerSource.InstalledApp
        "component" -> FieldPickerSource.Component(
            packageFieldKey = descriptor.fields.firstOrNull { it.key == "package" }?.key,
            kinds = inferredComponentKinds(descriptor.id.value),
        )
        "permission" -> descriptor.fields.firstOrNull { it.key == "package" }?.let {
            FieldPickerSource.Permission(it.key)
        }
        "subscriptionId" -> FieldPickerSource.Subscription
        "cameraId" -> FieldPickerSource.Camera
        "userId", "parentUserId" -> FieldPickerSource.AndroidUser
        "timeZone" -> FieldPickerSource.TimeZone
        "languageTag", "languageTags", "locales" -> FieldPickerSource.Locale
        "calendarId" -> FieldPickerSource.Calendar
        "imeId" -> FieldPickerSource.InputMethod
        "keyCode", "keyCode1", "keyCode2" -> FieldPickerSource.KeyCode
        "ssid" -> FieldPickerSource.WifiSsid
        "provider" -> if (descriptor.id.value.contains("location", ignoreCase = true)) {
            FieldPickerSource.LocationProvider
        } else {
            null
        }
        "address" -> if (
            descriptor.id.value.contains("bluetooth", ignoreCase = true) ||
            descriptor.id.value.contains(".ble", ignoreCase = true)
        ) {
            FieldPickerSource.BluetoothDevice
        } else {
            null
        }
        "hour" -> FieldPickerSource.Options(
            (0..23).map { FieldPickerOption(it.toString()) }
        )
        "minute" -> FieldPickerSource.Options(
            (0..59).map { FieldPickerOption(it.toString()) }
        )
        "operation" -> if (descriptor.id.value.contains("appop", ignoreCase = true)) {
            FieldPickerSource.AppOperation
        } else {
            null
        }
        "action" -> if (descriptor.id.value.contains("intent", ignoreCase = true)) {
            FieldPickerSource.IntentAction
        } else {
            null
        }
        "category" -> when {
            descriptor.id.value.contains("notification", ignoreCase = true) ->
                FieldPickerSource.NotificationCategory
            descriptor.id.value.contains("intent", ignoreCase = true) ->
                FieldPickerSource.IntentCategory
            else -> null
        }
        "class", "className", "receiverClass" -> {
            val packageKey = descriptor.fields.firstOrNull { it.key == "package" }?.key
            if (packageKey != null) {
                FieldPickerSource.Component(
                    packageFieldKey = packageKey,
                    kinds = inferredComponentKinds(descriptor.id.value),
                    valueMode = ComponentPickerValueMode.CLASS_NAME,
                )
            } else {
                null
            }
        }
        "mimeType" -> FieldPickerSource.Options(
            listOf(
                "text/plain",
                "text/html",
                "application/json",
                "application/xml",
                "application/pdf",
                "image/*",
                "audio/*",
                "video/*",
                "application/octet-stream",
                "*/*",
            ).map { FieldPickerOption(it) }
        )
        else -> null
    }
}

private fun inferredComponentKinds(descriptorId: String): Set<ComponentPickerKind> = when {
    descriptorId.contains("qs_tile", ignoreCase = true) ->
        setOf(ComponentPickerKind.QUICK_SETTINGS_TILE)
    descriptorId.contains("service", ignoreCase = true) ->
        setOf(ComponentPickerKind.SERVICE)
    descriptorId.contains("activity", ignoreCase = true) ||
        descriptorId.contains("launcher", ignoreCase = true) ->
        setOf(ComponentPickerKind.ACTIVITY)
    else -> ComponentPickerKind.entries.toSet()
}

@Composable
internal fun PickerBackedField(
    descriptorId: String,
    field: FieldSchema,
    picker: FieldPickerSource,
    allValues: Map<String, String>,
    value: String,
    enabled: Boolean,
    allowManualInput: Boolean,
    onValue: (String) -> Unit,
    onHardwareKeyCaptured: (HardwareKeyCaptureResult) -> Unit = {},
) {
    val label = localizedFieldLabelShared(descriptorId, field)
    val context = LocalContext.current
    val captureHardwareKey = LocalHardwareKeyCapture.current
    val scope = rememberCoroutineScope()
    var showPicker by remember(picker) { mutableStateOf(false) }
    var capturingKey by remember(picker) { mutableStateOf(false) }
    val numeric = field is FieldSchema.Number || field is FieldSchema.Duration
    val hardwareKeyField = picker == FieldPickerSource.KeyCode
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            readOnly = !allowManualInput,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text(
                    if (field.required) stringResource(TextR.string.editor_required_field_format, label)
                    else label
                )
            },
            singleLine = true,
            keyboardOptions = if (numeric) {
                KeyboardOptions(keyboardType = KeyboardType.Decimal)
            } else {
                KeyboardOptions.Default
            },
            supportingText = if (allowManualInput) {
                { Text(stringResource(TextR.string.feature_picker_select_or_enter_hint)) }
            } else {
                null
            },
        )
        OutlinedButton(
            onClick = { showPicker = true },
            enabled = enabled && !capturingKey,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(TextR.string.feature_picker_select_value))
        }
        if (hardwareKeyField) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        capturingKey = true
                        Toast.makeText(
                            context,
                            context.getString(TextR.string.hardware_key_capture_waiting),
                            Toast.LENGTH_SHORT,
                        ).show()
                        val captured = runCatching { captureHardwareKey(10_000L) }.getOrNull()
                        capturingKey = false
                        if (captured == null) {
                            Toast.makeText(
                                context,
                                context.getString(TextR.string.hardware_key_capture_timeout),
                                Toast.LENGTH_LONG,
                            ).show()
                        } else {
                            if (captured.keyCode > 0) {
                                onValue(captured.keyCode.toString())
                            }
                            onHardwareKeyCaptured(captured)
                            Toast.makeText(
                                context,
                                if (captured.keyCode > 0) {
                                    context.getString(
                                        TextR.string.hardware_key_capture_result,
                                        captured.keyCode,
                                    )
                                } else {
                                    context.getString(TextR.string.hardware_key_capture_result_oem)
                                },
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
                enabled = enabled && !capturingKey,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (capturingKey) TextR.string.hardware_key_capture_waiting_button
                        else TextR.string.hardware_key_capture_button
                    )
                )
            }
        }
    }
    if (showPicker) {
        SystemValuePickerDialog(
            picker = picker,
            allValues = allValues,
            current = value,
            onDismiss = { showPicker = false },
            onPick = {
                onValue(it)
                showPicker = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SystemValuePickerDialog(
    picker: FieldPickerSource,
    allValues: Map<String, String>,
    current: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val context = LocalContext.current
    val locale = currentEditorLocale()
    val hardwareKeyCatalogLoader = LocalHardwareKeyCatalogLoader.current
    var query by remember { mutableStateOf("") }
    var loading by remember(picker, allValues) { mutableStateOf(true) }
    var options by remember(picker, allValues) { mutableStateOf(emptyList<SystemPickerOption>()) }

    LaunchedEffect(picker, allValues, locale, hardwareKeyCatalogLoader) {
        loading = true
        val hardwareKeys = if (picker == FieldPickerSource.KeyCode) {
            runCatching { hardwareKeyCatalogLoader() }.getOrDefault(HardwareKeyPickerCatalog())
        } else {
            HardwareKeyPickerCatalog()
        }
        options = withContext(Dispatchers.IO) {
            loadSystemPickerOptions(context, picker, allValues, locale, hardwareKeys)
        }
        loading = false
    }

    val filtered = remember(options, query) {
        options.filter { option ->
            query.isBlank() ||
                option.label.contains(query, ignoreCase = true) ||
                option.value.contains(query, ignoreCase = true) ||
                option.detail.orEmpty().contains(query, ignoreCase = true)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(TextR.string.feature_picker_choose_value)) },
                    navigationIcon = {
                        TextButton(onClick = onDismiss) { Text(stringResource(TextR.string.common_close)) }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(TextR.string.feature_picker_search_value)) },
                        singleLine = true,
                    )
                }
                if (loading) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (filtered.isEmpty()) {
                    item {
                        Text(
                            stringResource(TextR.string.feature_picker_no_values),
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    items(filtered, key = { it.value + "\u0000" + it.label }) { option ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    option.label,
                                    fontWeight = if (option.value == current) FontWeight.Bold else FontWeight.Normal,
                                )
                            },
                            supportingContent = {
                                val detail = option.detail
                                    ?.takeIf { it.isNotBlank() }
                                    ?: option.value.takeIf { it != option.label }
                                if (detail != null) Text(detail)
                            },
                            modifier = Modifier.clickable { onPick(option.value) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
internal fun InstalledAppField(
    descriptorId: String,
    field: FieldSchema.AppPicker,
    value: String,
    enabled: Boolean,
    onValue: (String) -> Unit,
) {
    val context = LocalContext.current
    var show by remember { mutableStateOf(false) }
    val label = localizedFieldLabelShared(descriptorId, field)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
            singleLine = true,
            supportingText = { Text(stringResource(TextR.string.feature_picker_app_input_hint)) },
        )
        OutlinedButton(
            onClick = { show = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(TextR.string.feature_picker_select_installed_app))
        }
    }
    if (show) {
        InstalledAppDialog(
            context = context,
            current = value,
            onDismiss = { show = false },
        ) {
            onValue(it)
            show = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstalledAppDialog(
    context: Context,
    current: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var includeSystem by remember { mutableStateOf(false) }
    val locale = currentEditorLocale()
    val apps = remember(context, locale) { installedApps(context, locale) }
    val filtered = remember(apps, query, includeSystem) {
        apps.filter {
            (includeSystem || !it.system) &&
                (query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true))
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(TextR.string.feature_picker_choose_app)) },
                    navigationIcon = {
                        TextButton(onClick = onDismiss) { Text(stringResource(TextR.string.common_close)) }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(TextR.string.feature_picker_search_app)) },
                        singleLine = true,
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(TextR.string.editor_show_system_apps), Modifier.weight(1f))
                        Switch(includeSystem, { includeSystem = it })
                    }
                }
                items(filtered, key = { it.packageName }) { app ->
                    ListItem(
                        headlineContent = {
                            Text(
                                app.label,
                                fontWeight = if (app.packageName == current) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        supportingContent = { Text(app.packageName) },
                        trailingContent = {
                            if (app.system) {
                                Text(
                                    stringResource(TextR.string.editor_system_app),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        },
                        modifier = Modifier.clickable { onPick(app.packageName) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

