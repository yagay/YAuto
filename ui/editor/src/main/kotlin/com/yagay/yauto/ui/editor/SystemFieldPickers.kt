package com.yagay.yauto.ui.editor

import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.UserManager
import android.provider.CalendarContract
import android.telephony.SubscriptionManager
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
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
import kotlinx.coroutines.withContext

private data class InstalledApp(val label: String, val packageName: String, val system: Boolean)

private data class SystemPickerOption(
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
        "keyCode" -> FieldPickerSource.KeyCode
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
) {
    val label = localizedFieldLabelShared(descriptorId, field)
    var showPicker by remember(picker) { mutableStateOf(false) }
    val numeric = field is FieldSchema.Number || field is FieldSchema.Duration
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
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(TextR.string.feature_picker_select_value))
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
    var query by remember { mutableStateOf("") }
    var loading by remember(picker, allValues) { mutableStateOf(true) }
    var options by remember(picker, allValues) { mutableStateOf(emptyList<SystemPickerOption>()) }

    LaunchedEffect(picker, allValues, locale) {
        loading = true
        options = withContext(Dispatchers.IO) {
            loadSystemPickerOptions(context, picker, allValues, locale)
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

@Suppress("DEPRECATION")
private fun loadSystemPickerOptions(
    context: Context,
    picker: FieldPickerSource,
    values: Map<String, String>,
    locale: Locale,
): List<SystemPickerOption> = runCatching {
    val comparator = localizedStringComparator(locale)
    when (picker) {
        FieldPickerSource.InstalledApp -> installedApps(context, locale).map {
            SystemPickerOption(it.packageName, it.label, it.packageName)
        }
        is FieldPickerSource.Permission -> {
            val pkg = values[picker.packageFieldKey].orEmpty().trim()
            if (pkg.isBlank()) {
                emptyList()
            } else {
                val info = context.packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
                info.requestedPermissions.orEmpty()
                    .distinct()
                    .sortedWith(comparator)
                    .map { SystemPickerOption(it, it) }
            }
        }
        is FieldPickerSource.Component -> loadComponentOptions(context, picker, values, comparator)
        FieldPickerSource.Subscription -> {
            val manager = context.getSystemService(SubscriptionManager::class.java)
            manager.activeSubscriptionInfoList.orEmpty()
                .sortedBy { it.simSlotIndex }
                .map { info ->
                    val label = info.displayName?.toString().orEmpty().ifBlank {
                        info.carrierName?.toString().orEmpty().ifBlank { info.subscriptionId.toString() }
                    }
                    SystemPickerOption(
                        value = info.subscriptionId.toString(),
                        label = label,
                        detail = info.subscriptionId.toString(),
                    )
                }
        }
        FieldPickerSource.Camera -> {
            context.getSystemService(CameraManager::class.java).cameraIdList
                .sortedWith(comparator)
                .map { SystemPickerOption(it, it) }
        }
        FieldPickerSource.AndroidUser -> {
            context.getSystemService(UserManager::class.java).userProfiles
                .map { userHandleIdentifier(it).toString() }
                .distinct()
                .sorted()
                .map { SystemPickerOption(it, it) }
        }
        FieldPickerSource.TimeZone -> TimeZone.getAvailableIDs()
            .distinct()
            .sortedWith(comparator)
            .map { SystemPickerOption(it, it) }
        FieldPickerSource.Locale -> Locale.getAvailableLocales()
            .mapNotNull { available ->
                available.toLanguageTag().takeIf { it.isNotBlank() }?.let { tag ->
                    SystemPickerOption(tag, available.getDisplayName(locale).ifBlank { tag }, tag)
                }
            }
            .distinctBy { it.value }
            .sortedWith { left, right -> comparator.compare(left.label, right.label) }
        FieldPickerSource.Calendar -> {
            val projection = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
            )
            val output = mutableListOf<SystemPickerOption>()
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + " COLLATE NOCASE ASC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val accountIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex).toString()
                    val name = cursor.getString(nameIndex).orEmpty().ifBlank { id }
                    output += SystemPickerOption(id, name, cursor.getString(accountIndex))
                }
            }
            output
        }
        FieldPickerSource.InputMethod -> {
            context.getSystemService(InputMethodManager::class.java).inputMethodList
                .map { method ->
                    val label = runCatching {
                        method.loadLabel(context.packageManager).toString()
                    }.getOrDefault(method.id)
                    SystemPickerOption(method.id, label, method.id)
                }
                .distinctBy { it.value }
                .sortedWith { left, right -> comparator.compare(left.label, right.label) }
        }
        FieldPickerSource.KeyCode -> {
            KeyEvent::class.java.fields
                .asSequence()
                .filter { it.name.startsWith("KEYCODE_") && it.type == Int::class.javaPrimitiveType }
                .mapNotNull { field ->
                    runCatching {
                        val value = field.getInt(null).toString()
                        SystemPickerOption(value, field.name.removePrefix("KEYCODE_"), value)
                    }.getOrNull()
                }
                .distinctBy { it.value }
                .sortedWith { left, right -> comparator.compare(left.label, right.label) }
                .toList()
        }
        FieldPickerSource.WifiSsid -> {
            val manager = context.applicationContext.getSystemService(WifiManager::class.java)
            buildList {
                manager.connectionInfo?.ssid
                    ?.trim('"')
                    ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
                    ?.let { add(SystemPickerOption(it, it)) }
                manager.scanResults.orEmpty().forEach { result ->
                    val ssid = result.SSID.orEmpty().trim()
                    if (ssid.isNotBlank()) add(SystemPickerOption(ssid, ssid, result.BSSID))
                }
            }.distinctBy { it.value }
                .sortedWith { left, right -> comparator.compare(left.label, right.label) }
        }
        FieldPickerSource.BluetoothDevice -> {
            val adapter = context.getSystemService(BluetoothManager::class.java).adapter
            adapter?.bondedDevices.orEmpty()
                .map { device ->
                    val address = device.address.orEmpty()
                    val label = device.name.orEmpty().ifBlank { address }
                    SystemPickerOption(address, label, address)
                }
                .distinctBy { it.value }
                .sortedWith { left, right -> comparator.compare(left.label, right.label) }
        }
        FieldPickerSource.LocationProvider -> {
            context.getSystemService(LocationManager::class.java).allProviders.orEmpty()
                .distinct()
                .sortedWith(comparator)
                .map { SystemPickerOption(it, it) }
        }
        is FieldPickerSource.Options -> picker.options.map {
            SystemPickerOption(it.value, it.label, it.value.takeIf { value -> value != it.label })
        }
    }
}.getOrDefault(emptyList())

private fun userHandleIdentifier(handle: android.os.UserHandle): Int =
    runCatching {
        val method = handle.javaClass.getDeclaredMethod("getIdentifier")
        method.isAccessible = true
        (method.invoke(handle) as Number).toInt()
    }.getOrElse { handle.hashCode() }

@Suppress("DEPRECATION")
private fun loadComponentOptions(
    context: Context,
    picker: FieldPickerSource.Component,
    values: Map<String, String>,
    comparator: Comparator<String>,
): List<SystemPickerOption> {
    val pm = context.packageManager
    val explicitPackage = picker.packageFieldKey?.let { values[it].orEmpty().trim() }.orEmpty()
    val packages = if (explicitPackage.isNotBlank()) {
        listOf(explicitPackage)
    } else {
        pm.getInstalledApplications(PackageManager.GET_META_DATA).map { it.packageName }
    }
    val flags =
        (if (ComponentPickerKind.ACTIVITY in picker.kinds) PackageManager.GET_ACTIVITIES else 0) or
            (if (
                ComponentPickerKind.SERVICE in picker.kinds ||
                ComponentPickerKind.QUICK_SETTINGS_TILE in picker.kinds
            ) PackageManager.GET_SERVICES else 0) or
            (if (ComponentPickerKind.RECEIVER in picker.kinds) PackageManager.GET_RECEIVERS else 0) or
            (if (ComponentPickerKind.PROVIDER in picker.kinds) PackageManager.GET_PROVIDERS else 0)
    val output = mutableListOf<SystemPickerOption>()

    packages.forEach { pkg ->
        val info = runCatching { pm.getPackageInfo(pkg, flags) }.getOrNull() ?: return@forEach
        if (ComponentPickerKind.ACTIVITY in picker.kinds) {
            info.activities.orEmpty().forEach { item ->
                val value = ComponentName(item.packageName, item.name).flattenToString()
                output += SystemPickerOption(value, item.name, item.packageName)
            }
        }
        if (
            ComponentPickerKind.SERVICE in picker.kinds ||
            ComponentPickerKind.QUICK_SETTINGS_TILE in picker.kinds
        ) {
            info.services.orEmpty().forEach { item ->
                if (
                    ComponentPickerKind.QUICK_SETTINGS_TILE in picker.kinds &&
                    ComponentPickerKind.SERVICE !in picker.kinds &&
                    item.permission != "android.permission.BIND_QUICK_SETTINGS_TILE"
                ) return@forEach
                val value = ComponentName(item.packageName, item.name).flattenToString()
                output += SystemPickerOption(value, item.name, item.packageName)
            }
        }
        if (ComponentPickerKind.RECEIVER in picker.kinds) {
            info.receivers.orEmpty().forEach { item ->
                val value = ComponentName(item.packageName, item.name).flattenToString()
                output += SystemPickerOption(value, item.name, item.packageName)
            }
        }
        if (ComponentPickerKind.PROVIDER in picker.kinds) {
            info.providers.orEmpty().forEach { item ->
                val value = ComponentName(item.packageName, item.name).flattenToString()
                output += SystemPickerOption(value, item.name, item.packageName)
            }
        }
    }

    return output
        .distinctBy { it.value }
        .sortedWith { left, right ->
            val label = comparator.compare(left.label, right.label)
            if (label != 0) label else comparator.compare(left.value, right.value)
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

@Suppress("DEPRECATION")
private fun installedApps(context: Context, locale: Locale): List<InstalledApp> = runCatching {
    val pm = context.packageManager
    val comparator = localizedStringComparator(locale)
    pm.getInstalledApplications(PackageManager.GET_META_DATA).map { info ->
        InstalledApp(
            label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName),
            packageName = info.packageName,
            system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
        )
    }.sortedWith { left, right ->
        when {
            left.system != right.system -> left.system.compareTo(right.system)
            else -> comparator.compare(left.label, right.label)
        }
    }
}.getOrDefault(emptyList())
