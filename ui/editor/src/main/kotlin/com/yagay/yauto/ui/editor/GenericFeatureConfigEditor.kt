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

private data class InstalledApp(val label: String, val packageName: String, val system: Boolean)

/** Generic configuration screen driven entirely by [FeatureDescriptor]. */
@Composable
internal fun GenericFeatureConfigEditor(
    modifier: Modifier,
    descriptor: FeatureDescriptor,
    initial: FeatureRef?,
    accent: Color,
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
        item { FeatureSummaryCard(descriptor, accent) }

        if (descriptor.resolvedImplementationOptions().size > 1) {
            item { ImplementationGuide(descriptor) }
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
            if (field.key == FEATURE_BACKEND_CONFIG_KEY && field is FieldSchema.Choice) {
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
                    onValue = { values = values + (field.key to it) },
                )
            }
        }

        item {
            Button(
                onClick = {
                    val config = initial?.config.orEmpty().toMutableMap()
                    semanticallyVisible.forEach { field ->
                        val behavior = descriptor.fieldBehavior(field.key)
                        val enabled = behavior.enabledWhen?.matches(typedValues) != false
                        if (!enabled) return@forEach

                        val raw = values[field.key].orEmpty()
                        val old = initial?.config?.get(field.key)
                        if (old != null && raw == editorConfigValueText(initialWithDefaults.config[field.key], locale)) {
                            config[field.key] = old
                        } else {
                            config.remove(field.key)
                            parseFieldValue(field, raw, locale)?.let { parsed -> config[field.key] = parsed }
                        }
                    }
                    onSave(FeatureRef(descriptor.id.value, descriptor.schemaVersion, config))
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
    val requirements = descriptor.resolvedAccessRequirements()
    if (requirements.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        requirements.forEach { CapabilityBadge(accessRequirementLabel(it)) }
    }
}

@Composable
private fun ImplementationGuide(descriptor: FeatureDescriptor) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(TextR.string.implementation_method), fontWeight = FontWeight.SemiBold)
            ImplementationExplanation("auto", emptySet(), false)
            descriptor.resolvedImplementationOptions().forEach { option ->
                ImplementationExplanation(option.backendId.orEmpty(), option.requirements, option.restartRequired)
            }
        }
    }
}

@Composable
private fun ImplementationExplanation(
    backendId: String,
    requirements: Set<AccessRequirement>,
    restartRequired: Boolean,
) {
    val title = implementationTitle(backendId)
    val summary = implementationSummary(backendId)
    val pros = implementationPros(backendId)
    val cons = implementationCons(backendId)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontWeight = FontWeight.Medium)
        if (requirements.isNotEmpty()) {
            Text(
                stringResource(
                    TextR.string.implementation_requirements_format,
                    localizedList(requirements.map { accessRequirementLabelNonComposable(it) }),
                ),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.bodySmall)
        if (pros.isNotEmpty()) {
            Text(
                stringResource(TextR.string.implementation_pros_format, localizedList(pros)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (cons.isNotEmpty()) {
            Text(
                stringResource(TextR.string.implementation_cons_format, localizedList(cons)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (restartRequired) {
            Text(
                stringResource(TextR.string.implementation_restart_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        Text(stringResource(TextR.string.implementation_method), fontWeight = FontWeight.Medium)
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
) {
    val descriptorId = descriptor.id.value
    val label = localizedFieldLabelShared(descriptorId, field)
    val picker = behavior.picker ?: inferredPickerSource(descriptor, field)
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


private data class SystemPickerOption(
    val value: String,
    val label: String,
    val detail: String? = null,
)

private fun inferredPickerSource(
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
        "languageTags" -> FieldPickerSource.Locale
        "calendarId" -> FieldPickerSource.Calendar
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
private fun PickerBackedField(
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
                .map { it.identifier.toString() }
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
        is FieldPickerSource.Options -> picker.options.map {
            SystemPickerOption(it.value, it.label, it.value.takeIf { value -> value != it.label })
        }
    }
}.getOrDefault(emptyList())

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
private fun InstalledAppField(
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

@Composable
private fun implementationSummary(backendId: String): String = when (backendId) {
    "auto" -> stringResource(TextR.string.implementation_auto_summary)
    "root" -> stringResource(TextR.string.implementation_root_summary)
    "shizuku" -> stringResource(TextR.string.implementation_shizuku_summary)
    "lsposed" -> stringResource(TextR.string.implementation_lsposed_summary)
    "accessibility" -> stringResource(TextR.string.implementation_accessibility_summary)
    "usage_stats" -> stringResource(TextR.string.implementation_usage_stats_summary)
    else -> ""
}

@Composable
private fun implementationPros(backendId: String): List<String> = when (backendId) {
    "root" -> listOf(
        stringResource(TextR.string.implementation_root_pro_1),
        stringResource(TextR.string.implementation_root_pro_2),
        stringResource(TextR.string.implementation_root_pro_3),
    )
    "shizuku" -> listOf(
        stringResource(TextR.string.implementation_shizuku_pro_1),
        stringResource(TextR.string.implementation_shizuku_pro_2),
        stringResource(TextR.string.implementation_shizuku_pro_3),
    )
    "lsposed" -> listOf(
        stringResource(TextR.string.implementation_lsposed_pro_1),
        stringResource(TextR.string.implementation_lsposed_pro_2),
        stringResource(TextR.string.implementation_lsposed_pro_3),
    )
    "accessibility" -> listOf(
        stringResource(TextR.string.implementation_accessibility_pro_1),
        stringResource(TextR.string.implementation_accessibility_pro_2),
        stringResource(TextR.string.implementation_accessibility_pro_3),
    )
    else -> emptyList()
}

@Composable
private fun implementationCons(backendId: String): List<String> = when (backendId) {
    "root" -> listOf(
        stringResource(TextR.string.implementation_root_con_1),
        stringResource(TextR.string.implementation_root_con_2),
        stringResource(TextR.string.implementation_root_con_3),
    )
    "shizuku" -> listOf(
        stringResource(TextR.string.implementation_shizuku_con_1),
        stringResource(TextR.string.implementation_shizuku_con_2),
        stringResource(TextR.string.implementation_shizuku_con_3),
    )
    "lsposed" -> listOf(
        stringResource(TextR.string.implementation_lsposed_con_1),
        stringResource(TextR.string.implementation_lsposed_con_2),
        stringResource(TextR.string.implementation_lsposed_con_3),
    )
    "accessibility" -> listOf(
        stringResource(TextR.string.implementation_accessibility_con_1),
        stringResource(TextR.string.implementation_accessibility_con_2),
        stringResource(TextR.string.implementation_accessibility_con_3),
    )
    else -> emptyList()
}
