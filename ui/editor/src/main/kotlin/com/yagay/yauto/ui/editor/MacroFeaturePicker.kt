package com.yagay.yauto.ui.editor

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
import com.yagay.yauto.ui.design.MacroItemRow
import com.yagay.yauto.ui.design.MacroPalette
import com.yagay.yauto.ui.design.R as TextR

private data class CatalogCategory(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    val order: Int,
)

private data class InstalledApp(val label: String, val packageName: String, val system: Boolean)

private sealed interface PickerPage {
    data object Categories : PickerPage
    data class Features(val category: CatalogCategory, val special: String? = null) : PickerPage
    data class Configure(val descriptor: FeatureDescriptor, val fromCategory: PickerPage.Features?) : PickerPage
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacroFeaturePickerDialog(
    kind: FeatureKind,
    descriptors: List<FeatureDescriptor>,
    initial: FeatureRef? = null,
    onDismiss: () -> Unit,
    onPick: (FeatureRef) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("yauto_feature_picker", Context.MODE_PRIVATE) }
    val editable = remember(descriptors, kind) {
        descriptors.filter { it.kind == kind && it.category != FeatureCategory.COMPATIBILITY }
    }
    val initialDescriptor = initial?.let { ref -> editable.firstOrNull { it.id.value == ref.typeId } }
    var page by remember(initial?.typeId) {
        mutableStateOf<PickerPage>(
            initialDescriptor?.let { PickerPage.Configure(it, null) } ?: PickerPage.Categories
        )
    }
    var query by remember { mutableStateOf("") }
    var favorites by remember(kind) {
        mutableStateOf(loadIds(prefs.getString(favoriteKey(kind), "")).toSet())
    }
    var recent by remember(kind) {
        mutableStateOf(loadIds(prefs.getString(recentKey(kind), "")))
    }
    val accent = kindAccent(kind)

    fun toggleFavorite(id: String) {
        favorites = if (id in favorites) favorites - id else favorites + id
        prefs.edit().putString(favoriteKey(kind), favorites.joinToString("\n")).apply()
    }

    fun recordRecent(id: String) {
        recent = (listOf(id) + recent.filterNot { it == id }).take(12)
        prefs.edit().putString(recentKey(kind), recent.joinToString("\n")).apply()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = accent,
                        titleContentColor = Color.White,
                    ),
                    title = {
                        Text(
                            when (val current = page) {
                                PickerPage.Categories -> stringResource(
                                    TextR.string.editor_select_kind_format,
                                    kindLabel(kind),
                                )
                                is PickerPage.Features -> categoryTitle(current)
                                is PickerPage.Configure -> localizedFeatureTitle(current.descriptor)
                            }
                        )
                    },
                    navigationIcon = {
                        TextButton(
                            onClick = {
                                page = when (val current = page) {
                                    PickerPage.Categories -> {
                                        onDismiss()
                                        PickerPage.Categories
                                    }
                                    is PickerPage.Features -> PickerPage.Categories
                                    is PickerPage.Configure -> current.fromCategory ?: PickerPage.Categories
                                }
                            }
                        ) {
                            Text(
                                if (page == PickerPage.Categories) {
                                    stringResource(TextR.string.common_close)
                                } else {
                                    "‹"
                                },
                                color = Color.White,
                            )
                        }
                    },
                )
            }
        ) { padding ->
            when (val current = page) {
                PickerPage.Categories -> CategoryPage(
                    modifier = Modifier.padding(padding),
                    kind = kind,
                    descriptors = editable,
                    query = query,
                    favorites = favorites,
                    recent = recent,
                    onQuery = { query = it },
                    onCategory = {
                        page = it
                        query = ""
                    },
                    onFeature = { page = PickerPage.Configure(it, null) },
                    onFavorite = ::toggleFavorite,
                )
                is PickerPage.Features -> FeatureListPage(
                    modifier = Modifier.padding(padding),
                    kind = kind,
                    categoryPage = current,
                    descriptors = editable,
                    query = query,
                    favorites = favorites,
                    recent = recent,
                    onQuery = { query = it },
                    onFeature = { page = PickerPage.Configure(it, current) },
                    onFavorite = ::toggleFavorite,
                )
                is PickerPage.Configure -> FeatureConfigurePage(
                    modifier = Modifier.padding(padding),
                    descriptor = current.descriptor,
                    initial = initial?.takeIf { it.typeId == current.descriptor.id.value },
                    accent = accent,
                ) { feature ->
                    recordRecent(feature.typeId)
                    onPick(feature)
                }
            }
        }
    }
}

@Composable
private fun CategoryPage(
    modifier: Modifier,
    kind: FeatureKind,
    descriptors: List<FeatureDescriptor>,
    query: String,
    favorites: Set<String>,
    recent: List<String>,
    onQuery: (String) -> Unit,
    onCategory: (PickerPage.Features) -> Unit,
    onFeature: (FeatureDescriptor) -> Unit,
    onFavorite: (String) -> Unit,
) {
    val categories = remember(descriptors) {
        descriptors.map { catalogCategory(it.category) }.distinctBy { it.id }.sortedBy { it.order }
    }
    val search = remember(descriptors, query) {
        if (query.isBlank()) emptyList() else descriptors.filter { descriptorMatches(it, query) }
    }
    val recentCount = recent.count { id -> descriptors.any { it.id.value == id } }
    val favoriteCount = favorites.count { id -> descriptors.any { it.id.value == id } }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(TextR.string.feature_picker_search_format, kindLabel(kind))) },
                singleLine = true,
            )
        }
        if (query.isBlank()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = kindAccent(kind).copy(alpha = .10f))) {
                    Column(Modifier.padding(12.dp)) {
                        Text(kindHelp(kind), fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(TextR.string.feature_picker_usage_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (recentCount > 0) {
                item {
                    CategoryRow(
                        stringResource(TextR.string.feature_picker_recent),
                        stringResource(TextR.string.feature_picker_recent_subtitle_format, recentCount),
                    ) {
                        onCategory(
                            PickerPage.Features(
                                catalogCategory(FeatureCategory.CORE),
                                special = "recent",
                            )
                        )
                    }
                }
            }
            if (favoriteCount > 0) {
                item {
                    CategoryRow(
                        stringResource(TextR.string.feature_picker_favorites),
                        stringResource(TextR.string.feature_picker_favorites_subtitle_format, favoriteCount),
                    ) {
                        onCategory(
                            PickerPage.Features(
                                catalogCategory(FeatureCategory.CORE),
                                special = "favorites",
                            )
                        )
                    }
                }
            }
            items(categories, key = { it.id }) { category ->
                val count = descriptors.count { it.category.name.lowercase() == category.id }
                CategoryRow(
                    stringResource(category.titleRes),
                    stringResource(
                        TextR.string.editor_category_count_format,
                        stringResource(category.subtitleRes),
                        count,
                    ),
                ) { onCategory(PickerPage.Features(category)) }
            }
        } else {
            item {
                Text(
                    stringResource(TextR.string.feature_picker_search_results_format, search.size),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            items(search, key = { it.id.value }) { descriptor ->
                val title = localizedFeatureTitle(descriptor)
                MacroItemRow(
                    title = if (descriptor.id.value in favorites) {
                        stringResource(TextR.string.editor_favorite_prefix, title)
                    } else {
                        title
                    },
                    subtitle = stringResource(
                        TextR.string.editor_feature_search_subtitle_format,
                        stringResource(catalogCategory(descriptor.category).titleRes),
                        localizedFeatureDescription(descriptor),
                    ),
                    accent = kindAccent(kind),
                    onClick = { onFeature(descriptor) },
                    onMenu = { onFavorite(descriptor.id.value) },
                )
            }
        }
    }
}

@Composable
private fun CategoryRow(title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Text("›") },
        modifier = Modifier.clickable(onClick = onClick),
    )
    HorizontalDivider()
}

@Composable
private fun FeatureListPage(
    modifier: Modifier,
    kind: FeatureKind,
    categoryPage: PickerPage.Features,
    descriptors: List<FeatureDescriptor>,
    query: String,
    favorites: Set<String>,
    recent: List<String>,
    onQuery: (String) -> Unit,
    onFeature: (FeatureDescriptor) -> Unit,
    onFavorite: (String) -> Unit,
) {
    val features = remember(descriptors, categoryPage, query, favorites, recent) {
        val base = when (categoryPage.special) {
            "recent" -> recent.mapNotNull { id -> descriptors.firstOrNull { it.id.value == id } }
            "favorites" -> descriptors.filter { it.id.value in favorites }
            else -> descriptors.filter { it.category.name.lowercase() == categoryPage.category.id }
        }
        base.filter { query.isBlank() || descriptorMatches(it, query) }
            .sortedBy { it.title.lowercase() }
    }
    val title = categoryTitle(categoryPage)
    val subtitle = categorySubtitle(categoryPage)

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(TextR.string.feature_picker_search_in_format, title)) },
                singleLine = true,
            )
        }
        item {
            Text(
                subtitle + " · " + stringResource(TextR.string.feature_picker_favorite_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(features, key = { it.id.value }) { descriptor ->
            val localizedTitle = localizedFeatureTitle(descriptor)
            MacroItemRow(
                title = if (descriptor.id.value in favorites) {
                    stringResource(TextR.string.editor_favorite_prefix, localizedTitle)
                } else {
                    localizedTitle
                },
                subtitle = localizedFeatureDescription(descriptor),
                accent = kindAccent(kind),
                onClick = { onFeature(descriptor) },
                onMenu = { onFavorite(descriptor.id.value) },
            )
        }
    }
}

@Composable
private fun FeatureConfigurePage(
    modifier: Modifier,
    descriptor: FeatureDescriptor,
    initial: FeatureRef?,
    accent: Color,
    onSave: (FeatureRef) -> Unit,
) {
    val initialTexts = remember(descriptor.id.value, initial) {
        descriptor.fields.associate { it.key to initial?.config?.get(it.key).editorText() }
    }
    var values by remember(descriptor.id.value, initial) { mutableStateOf(initialTexts) }
    val valid = descriptor.fields.all { field -> fieldValid(field, values[field.key].orEmpty()) }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = .10f))) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(localizedFeatureDescription(descriptor))
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
                                CapabilityBadge(it.value.substringAfterLast('.'))
                            }
                        }
                    }
                }
            }
        }

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
        items(descriptor.fields, key = { it.key }) { field ->
            if (field.key == FEATURE_BACKEND_CONFIG_KEY && field is FieldSchema.Choice) {
                BackendChoiceEditor(
                    descriptor = descriptor,
                    field = field,
                    value = values[field.key].orEmpty(),
                    onValue = { values = values + (field.key to it) },
                )
            } else {
                FieldEditor(
                    descriptorId = descriptor.id.value,
                    field = field,
                    value = values[field.key].orEmpty(),
                    onValue = { values = values + (field.key to it) },
                )
            }
        }
        item {
            Button(
                onClick = {
                    val config = buildMap<String, ConfigValue> {
                        putAll(initial?.config.orEmpty().filterKeys { key -> descriptor.fields.none { it.key == key } })
                        descriptor.fields.forEach { field ->
                            val raw = values[field.key].orEmpty()
                            val old = initial?.config?.get(field.key)
                            if (old != null && raw == initialTexts[field.key]) {
                                put(field.key, old)
                            } else {
                                when (field) {
                                    is FieldSchema.Toggle -> put(
                                        field.key,
                                        ConfigValue.BooleanValue(raw.toBooleanStrictOrNull() ?: false),
                                    )
                                    is FieldSchema.Number, is FieldSchema.Duration -> raw.toDoubleOrNull()?.let {
                                        put(field.key, ConfigValue.NumberValue(it))
                                    }
                                    else -> if (raw.isNotEmpty() || field.required) {
                                        put(field.key, ConfigValue.StringValue(raw))
                                    }
                                }
                            }
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
                    requirements.joinToString(" + ") { accessRequirementLabelNonComposable(it) },
                ),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.bodySmall)
        if (pros.isNotEmpty()) {
            Text(
                stringResource(TextR.string.implementation_pros_format, pros.joinToString(" · ")),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (cons.isNotEmpty()) {
            Text(
                stringResource(TextR.string.implementation_cons_format, cons.joinToString(" · ")),
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
    onValue: (String) -> Unit,
) {
    val selected = value.ifBlank { "auto" }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(TextR.string.implementation_method), fontWeight = FontWeight.Medium)
        field.options.forEach { option ->
            val supported = option == "auto" || descriptor.resolvedImplementationOptions().any { it.backendId == option }
            if (supported) {
                Row(
                    Modifier.fillMaxWidth().clickable { onValue(option) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected == option, onClick = { onValue(option) })
                    Text(implementationTitle(option))
                }
            }
        }
    }
}

@Composable
private fun FieldEditor(
    descriptorId: String,
    field: FieldSchema,
    value: String,
    onValue: (String) -> Unit,
) {
    val label = localizedFieldLabel(descriptorId, field)
    when (field) {
        is FieldSchema.Toggle -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label)
                if (field.required) {
                    Text(stringResource(TextR.string.editor_required), style = MaterialTheme.typography.labelSmall)
                }
            }
            Switch(value.toBooleanStrictOrNull() ?: false, { onValue(it.toString()) })
        }
        is FieldSchema.Choice -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, fontWeight = FontWeight.Medium)
            field.options.forEach { option ->
                Row(
                    Modifier.fillMaxWidth().clickable { onValue(option) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(value == option, { onValue(option) })
                    Text(localizedChoiceOption(descriptorId, field.key, option))
                }
            }
        }
        is FieldSchema.AppPicker -> InstalledAppField(descriptorId, field, value, onValue)
        else -> {
            val numeric = field is FieldSchema.Number || field is FieldSchema.Duration
            OutlinedTextField(
                value = value,
                onValueChange = onValue,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(label + if (field.required) " *" else "") },
                minLines = if (field is FieldSchema.Text && field.multiline) 3 else 1,
                keyboardOptions = if (numeric) {
                    KeyboardOptions(keyboardType = KeyboardType.Number)
                } else {
                    KeyboardOptions.Default
                },
                supportingText = when (field) {
                    is FieldSchema.Variable -> ({ Text(stringResource(TextR.string.feature_picker_variable_hint)) })
                    is FieldSchema.Duration -> ({ Text(stringResource(TextR.string.feature_picker_milliseconds)) })
                    else -> null
                },
            )
        }
    }
}

@Composable
private fun InstalledAppField(
    descriptorId: String,
    field: FieldSchema.AppPicker,
    value: String,
    onValue: (String) -> Unit,
) {
    val context = LocalContext.current
    var show by remember { mutableStateOf(false) }
    val label = localizedFieldLabel(descriptorId, field)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label + if (field.required) " *" else "") },
            singleLine = true,
            supportingText = { Text(stringResource(TextR.string.feature_picker_app_input_hint)) },
        )
        OutlinedButton(onClick = { show = true }, modifier = Modifier.fillMaxWidth()) {
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
    val apps = remember { installedApps(context) }
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
            }
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    OutlinedTextField(
                        query,
                        { query = it },
                        Modifier.fillMaxWidth(),
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
private fun installedApps(context: Context): List<InstalledApp> = runCatching {
    val pm = context.packageManager
    pm.getInstalledApplications(PackageManager.GET_META_DATA).map { info ->
        InstalledApp(
            label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName),
            packageName = info.packageName,
            system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
        )
    }.sortedWith(compareBy<InstalledApp> { it.system }.thenBy { it.label.lowercase() })
}.getOrDefault(emptyList())

private fun ConfigValue?.editorText(): String = when (this) {
    null, ConfigValue.NullValue -> ""
    is ConfigValue.StringValue -> value
    is ConfigValue.NumberValue -> value.toString().removeSuffix(".0")
    is ConfigValue.BooleanValue -> value.toString()
    is ConfigValue.ListValue -> value.joinToString(",") { it.editorText() }
    is ConfigValue.ObjectValue -> value.toString()
}

private fun loadIds(raw: String?): List<String> = raw.orEmpty().lineSequence()
    .map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
private fun favoriteKey(kind: FeatureKind) = "favorites_${kind.name.lowercase()}"
private fun recentKey(kind: FeatureKind) = "recent_${kind.name.lowercase()}"

private fun fieldValid(field: FieldSchema, raw: String): Boolean = when (field) {
    is FieldSchema.Number -> (raw.isBlank() && !field.required) || raw.toDoubleOrNull()?.let { number ->
        number.isFinite() && (field.min?.let { number >= it } ?: true) && (field.max?.let { number <= it } ?: true)
    } == true
    is FieldSchema.Duration -> (raw.isBlank() && !field.required) || raw.toDoubleOrNull()?.let {
        it.isFinite() && it >= 0
    } == true
    is FieldSchema.Choice -> (raw.isBlank() && !field.required) || raw in field.options
    is FieldSchema.Toggle -> true
    else -> !field.required || raw.isNotBlank()
}

private fun descriptorMatches(descriptor: FeatureDescriptor, query: String): Boolean = listOf(
    descriptor.title,
    descriptor.description,
    descriptor.id.value,
    descriptor.keywords.joinToString(" "),
).any { it.contains(query, true) }

private fun kindAccent(kind: FeatureKind): Color = when (kind) {
    FeatureKind.EVENT -> MacroPalette.Trigger
    FeatureKind.STATE -> MacroPalette.State
    FeatureKind.ACTION -> MacroPalette.Action
    FeatureKind.CONDITION -> MacroPalette.Constraint
}

@Composable
private fun kindLabel(kind: FeatureKind): String = stringResource(
    when (kind) {
        FeatureKind.EVENT -> TextR.string.kind_event
        FeatureKind.STATE -> TextR.string.kind_state
        FeatureKind.ACTION -> TextR.string.kind_action
        FeatureKind.CONDITION -> TextR.string.kind_condition
    }
)

@Composable
private fun kindHelp(kind: FeatureKind): String = stringResource(
    when (kind) {
        FeatureKind.EVENT -> TextR.string.kind_event_help
        FeatureKind.STATE -> TextR.string.kind_state_help
        FeatureKind.ACTION -> TextR.string.kind_action_help
        FeatureKind.CONDITION -> TextR.string.kind_condition_help
    }
)

private fun catalogCategory(category: FeatureCategory): CatalogCategory = when (category) {
    FeatureCategory.CORE -> CatalogCategory("core", TextR.string.category_core, TextR.string.category_core_subtitle, 10)
    FeatureCategory.APP -> CatalogCategory("app", TextR.string.category_app, TextR.string.category_app_subtitle, 20)
    FeatureCategory.DEVICE -> CatalogCategory("device", TextR.string.category_device, TextR.string.category_device_subtitle, 30)
    FeatureCategory.NETWORK -> CatalogCategory("network", TextR.string.category_network, TextR.string.category_network_subtitle, 40)
    FeatureCategory.DISPLAY -> CatalogCategory("display", TextR.string.category_display, TextR.string.category_display_subtitle, 50)
    FeatureCategory.AUDIO -> CatalogCategory("audio", TextR.string.category_audio, TextR.string.category_audio_subtitle, 60)
    FeatureCategory.NOTIFICATION -> CatalogCategory("notification", TextR.string.category_notification, TextR.string.category_notification_subtitle, 70)
    FeatureCategory.FILE -> CatalogCategory("file", TextR.string.category_file, TextR.string.category_file_subtitle, 80)
    FeatureCategory.VARIABLE -> CatalogCategory("variable", TextR.string.category_variable, TextR.string.category_variable_subtitle, 90)
    FeatureCategory.FLOW -> CatalogCategory("flow", TextR.string.category_flow, TextR.string.category_flow_subtitle, 100)
    FeatureCategory.UI_AUTOMATION -> CatalogCategory("ui_automation", TextR.string.category_ui_automation, TextR.string.category_ui_automation_subtitle, 110)
    FeatureCategory.SYSTEM -> CatalogCategory("system", TextR.string.category_system, TextR.string.category_system_subtitle, 120)
    FeatureCategory.SCRIPT -> CatalogCategory("script", TextR.string.category_script, TextR.string.category_script_subtitle, 130)
    FeatureCategory.ADVANCED -> CatalogCategory("advanced", TextR.string.category_advanced, TextR.string.category_advanced_subtitle, 140)
    FeatureCategory.COMPATIBILITY -> CatalogCategory("advanced", TextR.string.category_advanced, TextR.string.category_advanced_subtitle, 150)
}

@Composable
private fun categoryTitle(page: PickerPage.Features): String = when (page.special) {
    "recent" -> stringResource(TextR.string.feature_picker_recent)
    "favorites" -> stringResource(TextR.string.feature_picker_favorites)
    else -> stringResource(page.category.titleRes)
}

@Composable
private fun categorySubtitle(page: PickerPage.Features): String = when (page.special) {
    "recent" -> stringResource(TextR.string.feature_picker_recent)
    "favorites" -> stringResource(TextR.string.feature_picker_favorites)
    else -> stringResource(page.category.subtitleRes)
}

@Composable
private fun localizedFeatureTitle(descriptor: FeatureDescriptor): String = localizedResourceOverride(
    key = "feature_${resourceKey(descriptor.id.value)}_title",
    fallback = descriptor.title,
)

@Composable
private fun localizedFeatureDescription(descriptor: FeatureDescriptor): String = localizedResourceOverride(
    key = "feature_${resourceKey(descriptor.id.value)}_description",
    fallback = descriptor.description,
)

@Composable
private fun localizedFieldLabel(descriptorId: String, field: FieldSchema): String = localizedResourceOverride(
    key = "feature_${resourceKey(descriptorId)}_field_${resourceKey(field.key)}",
    fallback = field.label,
)

@Composable
private fun localizedChoiceOption(descriptorId: String, fieldKey: String, option: String): String = localizedResourceOverride(
    key = "feature_${resourceKey(descriptorId)}_field_${resourceKey(fieldKey)}_option_${resourceKey(option)}",
    fallback = option,
)

@Composable
private fun localizedResourceOverride(key: String, fallback: String): String {
    val context = LocalContext.current
    val id = remember(key, context.packageName) {
        context.resources.getIdentifier(key, "string", context.packageName)
    }
    return if (id != 0) stringResource(id) else fallback
}

private fun resourceKey(value: String): String = value.lowercase().map {
    if (it.isLetterOrDigit()) it else '_'
}.joinToString("").replace(Regex("_+"), "_").trim('_')

@Composable
private fun accessRequirementLabel(requirement: AccessRequirement): String = stringResource(accessRequirementResource(requirement))

@Composable
private fun accessRequirementLabelNonComposable(requirement: AccessRequirement): String =
    stringResource(accessRequirementResource(requirement))

@StringRes
private fun accessRequirementResource(requirement: AccessRequirement): Int = when (requirement) {
    AccessRequirement.ROOT -> TextR.string.access_root
    AccessRequirement.SHIZUKU -> TextR.string.access_shizuku
    AccessRequirement.LSPOSED -> TextR.string.access_lsposed
    AccessRequirement.SHAMIKO -> TextR.string.access_shamiko
    AccessRequirement.ZYGISK -> TextR.string.access_zygisk
    AccessRequirement.ACCESSIBILITY -> TextR.string.access_accessibility
    AccessRequirement.NOTIFICATION_LISTENER -> TextR.string.access_notification_listener
    AccessRequirement.POST_NOTIFICATIONS -> TextR.string.access_post_notifications
    AccessRequirement.OVERLAY -> TextR.string.access_overlay
    AccessRequirement.WRITE_SETTINGS -> TextR.string.access_write_settings
    AccessRequirement.CAMERA -> TextR.string.access_camera
    AccessRequirement.LOCATION -> TextR.string.access_location
    AccessRequirement.BLUETOOTH_CONNECT -> TextR.string.access_bluetooth
    AccessRequirement.DND_POLICY -> TextR.string.access_dnd_policy
    AccessRequirement.DEVICE_ADMIN -> TextR.string.access_device_admin
}

@Composable
private fun implementationTitle(backendId: String): String = stringResource(
    when (backendId) {
        "auto" -> TextR.string.implementation_auto_title
        "root" -> TextR.string.implementation_root_title
        "shizuku" -> TextR.string.implementation_shizuku_title
        "lsposed" -> TextR.string.implementation_lsposed_title
        "accessibility" -> TextR.string.implementation_accessibility_title
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
