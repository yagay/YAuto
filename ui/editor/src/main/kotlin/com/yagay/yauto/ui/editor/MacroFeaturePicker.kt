package com.yagay.yauto.ui.editor

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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

private data class CatalogCategory(val id: String, val title: String, val subtitle: String, val order: Int)
private data class InstalledApp(val label: String, val packageName: String, val system: Boolean)

private sealed interface PickerPage {
    data object Categories : PickerPage
    data class Features(val category: CatalogCategory) : PickerPage
    data class Configure(val descriptor: FeatureDescriptor, val fromCategory: CatalogCategory?) : PickerPage
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
    val editable = remember(descriptors, kind) { descriptors.filter { it.kind == kind && it.category != FeatureCategory.COMPATIBILITY } }
    val initialDescriptor = initial?.let { ref -> editable.firstOrNull { it.id.value == ref.typeId } }
    var page by remember(initial?.typeId) { mutableStateOf<PickerPage>(initialDescriptor?.let { PickerPage.Configure(it, null) } ?: PickerPage.Categories) }
    var query by remember { mutableStateOf("") }
    var favorites by remember(kind) { mutableStateOf(loadIds(prefs.getString(favoriteKey(kind), ""))) }
    var recent by remember(kind) { mutableStateOf(loadIds(prefs.getString(recentKey(kind), ""))) }
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
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = accent, titleContentColor = Color.White),
                    title = { Text(when (val p = page) {
                        PickerPage.Categories -> "选择${kindLabel(kind)}"
                        is PickerPage.Features -> p.category.title
                        is PickerPage.Configure -> p.descriptor.title
                    }) },
                    navigationIcon = {
                        TextButton(onClick = {
                            page = when (val p = page) {
                                PickerPage.Categories -> { onDismiss(); PickerPage.Categories }
                                is PickerPage.Features -> PickerPage.Categories
                                is PickerPage.Configure -> p.fromCategory?.let { PickerPage.Features(it) } ?: PickerPage.Categories
                            }
                        }) { Text(if (page == PickerPage.Categories) "关闭" else "‹", color = Color.White) }
                    },
                )
            }
        ) { padding ->
            when (val p = page) {
                PickerPage.Categories -> CategoryPage(
                    Modifier.padding(padding), kind, editable, query, favorites, recent,
                    onQuery = { query = it },
                    onCategory = { page = PickerPage.Features(it); query = "" },
                    onFeature = { page = PickerPage.Configure(it, null) },
                    onFavorite = ::toggleFavorite,
                )
                is PickerPage.Features -> FeatureListPage(
                    Modifier.padding(padding), kind, p.category, editable, query, favorites, recent,
                    onQuery = { query = it },
                    onFeature = { page = PickerPage.Configure(it, p.category) },
                    onFavorite = ::toggleFavorite,
                )
                is PickerPage.Configure -> FeatureConfigurePage(
                    Modifier.padding(padding), p.descriptor,
                    initial?.takeIf { it.typeId == p.descriptor.id.value }, accent,
                ) { feature -> recordRecent(feature.typeId); onPick(feature) }
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
    onCategory: (CatalogCategory) -> Unit,
    onFeature: (FeatureDescriptor) -> Unit,
    onFavorite: (String) -> Unit,
) {
    val categories = remember(descriptors, kind) { descriptors.groupBy { catalogCategory(kind, it) }.keys.sortedBy { it.order } }
    val search = remember(descriptors, query) { if (query.isBlank()) emptyList() else descriptors.filter { descriptorMatches(it, query) } }
    val recentCount = recent.count { id -> descriptors.any { it.id.value == id } }
    val favoriteCount = favorites.count { id -> descriptors.any { it.id.value == id } }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        item { OutlinedTextField(query, onQuery, Modifier.fillMaxWidth(), label = { Text("搜索${kindLabel(kind)}") }, singleLine = true) }
        if (query.isBlank()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = kindAccent(kind).copy(alpha = .10f))) {
                    Column(Modifier.padding(12.dp)) {
                        Text(kindHelp(kind), fontWeight = FontWeight.SemiBold)
                        Text("统一使用：分类 → 功能 → 参数。常用功能可收藏，使用后自动进入最近列表。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (recentCount > 0) item { CategoryRow("最近使用", "最近配置过的功能 · $recentCount 项") { onCategory(CatalogCategory("__recent", "最近使用", "最近配置过的功能", -20)) } }
            if (favoriteCount > 0) item { CategoryRow("★ 收藏", "固定常用功能 · $favoriteCount 项") { onCategory(CatalogCategory("__favorites", "收藏", "固定常用功能", -10)) } }
            items(categories, key = { it.id }) { category ->
                val count = descriptors.count { catalogCategory(kind, it).id == category.id }
                CategoryRow(category.title, "${category.subtitle} · $count 项") { onCategory(category) }
            }
        } else {
            item { Text("搜索结果 ${search.size}", style = MaterialTheme.typography.labelLarge) }
            items(search, key = { it.id.value }) { descriptor ->
                MacroItemRow(
                    title = (if (descriptor.id.value in favorites) "★ " else "") + descriptor.title,
                    subtitle = "${catalogCategory(kind, descriptor).title} · ${descriptor.description}",
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
    category: CatalogCategory,
    descriptors: List<FeatureDescriptor>,
    query: String,
    favorites: Set<String>,
    recent: List<String>,
    onQuery: (String) -> Unit,
    onFeature: (FeatureDescriptor) -> Unit,
    onFavorite: (String) -> Unit,
) {
    val features = remember(descriptors, category, query, favorites, recent) {
        val base = when (category.id) {
            "__recent" -> recent.mapNotNull { id -> descriptors.firstOrNull { it.id.value == id } }
            "__favorites" -> descriptors.filter { it.id.value in favorites }.sortedBy { it.title.lowercase() }
            else -> descriptors.filter { catalogCategory(kind, it).id == category.id }.sortedBy { it.title.lowercase() }
        }
        base.filter { query.isBlank() || descriptorMatches(it, query) }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        item { OutlinedTextField(query, onQuery, Modifier.fillMaxWidth(), label = { Text("在 ${category.title} 中搜索") }, singleLine = true) }
        item { Text(category.subtitle + " · 点击 ⋮ 可收藏/取消收藏", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(features, key = { it.id.value }) { descriptor ->
            MacroItemRow(
                title = (if (descriptor.id.value in favorites) "★ " else "") + descriptor.title,
                subtitle = descriptor.description,
                accent = kindAccent(kind),
                onClick = { onFeature(descriptor) },
                onMenu = { onFavorite(descriptor.id.value) },
            )
        }
    }
}

@Composable
private fun FeatureConfigurePage(modifier: Modifier, descriptor: FeatureDescriptor, initial: FeatureRef?, accent: Color, onSave: (FeatureRef) -> Unit) {
    val initialTexts = remember(descriptor.id.value, initial) { descriptor.fields.associate { it.key to initial?.config?.get(it.key).editorText() } }
    var values by remember(descriptor.id.value, initial) { mutableStateOf(initialTexts) }
    val valid = descriptor.fields.all { field -> fieldValid(field, values[field.key].orEmpty()) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = .10f))) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(descriptor.description)
                    Text("功能 ID：${descriptor.id.value}", style = MaterialTheme.typography.labelSmall)
                    if (descriptor.minSdk > 31) Text("最低 Android API ${descriptor.minSdk}", style = MaterialTheme.typography.labelSmall)
                    if (descriptor.capabilities.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        descriptor.capabilities.take(4).forEach { CapabilityBadge(it.value.substringAfterLast('.')) }
                    }
                }
            }
        }
        if (descriptor.fields.isEmpty()) item { Text("此功能没有额外参数。", style = MaterialTheme.typography.bodyMedium) }
        items(descriptor.fields, key = { it.key }) { field -> FieldEditor(field, values[field.key].orEmpty()) { values = values + (field.key to it) } }
        item {
            Button(
                onClick = {
                    val config = buildMap<String, ConfigValue> {
                        putAll(initial?.config.orEmpty().filterKeys { key -> descriptor.fields.none { it.key == key } })
                        descriptor.fields.forEach { field ->
                            val raw = values[field.key].orEmpty(); val old = initial?.config?.get(field.key)
                            if (old != null && raw == initialTexts[field.key]) put(field.key, old)
                            else when (field) {
                                is FieldSchema.Toggle -> put(field.key, ConfigValue.BooleanValue(raw.toBooleanStrictOrNull() ?: false))
                                is FieldSchema.Number, is FieldSchema.Duration -> raw.toDoubleOrNull()?.let { put(field.key, ConfigValue.NumberValue(it)) }
                                else -> if (raw.isNotEmpty() || field.required) put(field.key, ConfigValue.StringValue(raw))
                            }
                        }
                    }
                    onSave(FeatureRef(descriptor.id.value, descriptor.schemaVersion, config))
                }, enabled = valid, modifier = Modifier.fillMaxWidth()
            ) { Text(if (initial == null) "添加${kindLabel(descriptor.kind)}" else "保存") }
        }
    }
}

@Composable
private fun FieldEditor(field: FieldSchema, value: String, onValue: (String) -> Unit) {
    when (field) {
        is FieldSchema.Toggle -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(field.label); if (field.required) Text("必填", style = MaterialTheme.typography.labelSmall) }
            Switch(value.toBooleanStrictOrNull() ?: false, { onValue(it.toString()) })
        }
        is FieldSchema.Choice -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(field.label, fontWeight = FontWeight.Medium)
            field.options.forEach { option -> Row(Modifier.fillMaxWidth().clickable { onValue(option) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(value == option, { onValue(option) }); Text(option)
            } }
        }
        is FieldSchema.AppPicker -> InstalledAppField(field, value, onValue)
        else -> {
            val numeric = field is FieldSchema.Number || field is FieldSchema.Duration
            OutlinedTextField(
                value, onValue, Modifier.fillMaxWidth(), label = { Text(field.label + if (field.required) " *" else "") },
                minLines = if (field is FieldSchema.Text && field.multiline) 3 else 1,
                keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                supportingText = when (field) {
                    is FieldSchema.Variable -> ({ Text("输入变量名，可使用模板变量") })
                    is FieldSchema.Duration -> ({ Text("毫秒") })
                    else -> null
                },
            )
        }
    }
}

@Composable
private fun InstalledAppField(field: FieldSchema.AppPicker, value: String, onValue: (String) -> Unit) {
    val context = LocalContext.current
    var show by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(value, onValue, Modifier.fillMaxWidth(), label = { Text(field.label + if (field.required) " *" else "") }, singleLine = true,
            supportingText = { Text("可直接输入包名，或从已安装应用中选择") })
        OutlinedButton(onClick = { show = true }, modifier = Modifier.fillMaxWidth()) { Text("选择已安装应用") }
    }
    if (show) InstalledAppDialog(context, value, onDismiss = { show = false }) { onValue(it); show = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstalledAppDialog(context: Context, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var includeSystem by remember { mutableStateOf(false) }
    val apps = remember { installedApps(context) }
    val filtered = remember(apps, query, includeSystem) {
        apps.filter { (includeSystem || !it.system) && (query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true)) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = { TopAppBar(title = { Text("选择应用") }, navigationIcon = { TextButton(onClick = onDismiss) { Text("关闭") } }) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("搜索应用或包名") }, singleLine = true) }
                item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("显示系统应用", Modifier.weight(1f)); Switch(includeSystem, { includeSystem = it }) } }
                items(filtered, key = { it.packageName }) { app ->
                    ListItem(
                        headlineContent = { Text(app.label, fontWeight = if (app.packageName == current) FontWeight.Bold else FontWeight.Normal) },
                        supportingContent = { Text(app.packageName) },
                        trailingContent = { if (app.system) Text("系统", style = MaterialTheme.typography.labelSmall) },
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
            system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
        )
    }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
}.getOrDefault(emptyList())

private fun loadIds(raw: String?): List<String> = raw.orEmpty().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
private fun favoriteKey(kind: FeatureKind) = "favorites_${kind.name.lowercase()}"
private fun recentKey(kind: FeatureKind) = "recent_${kind.name.lowercase()}"

private fun fieldValid(field: FieldSchema, raw: String): Boolean = when (field) {
    is FieldSchema.Number -> (raw.isBlank() && !field.required) || raw.toDoubleOrNull()?.let { n -> n.isFinite() && (field.min?.let { n >= it } ?: true) && (field.max?.let { n <= it } ?: true) } == true
    is FieldSchema.Duration -> (raw.isBlank() && !field.required) || raw.toDoubleOrNull()?.let { it.isFinite() && it >= 0 } == true
    is FieldSchema.Choice -> (raw.isBlank() && !field.required) || raw in field.options
    is FieldSchema.Toggle -> true
    else -> !field.required || raw.isNotBlank()
}

private fun descriptorMatches(descriptor: FeatureDescriptor, query: String): Boolean = listOf(descriptor.title, descriptor.description, descriptor.id.value, descriptor.keywords.joinToString(" ")).any { it.contains(query, true) }
private fun kindAccent(kind: FeatureKind): Color = when (kind) { FeatureKind.EVENT -> MacroPalette.Trigger; FeatureKind.STATE -> MacroPalette.State; FeatureKind.ACTION -> MacroPalette.Action; FeatureKind.CONDITION -> MacroPalette.Constraint }
private fun kindLabel(kind: FeatureKind): String = when (kind) { FeatureKind.EVENT -> "触发器"; FeatureKind.STATE -> "状态"; FeatureKind.ACTION -> "动作"; FeatureKind.CONDITION -> "约束" }
private fun kindHelp(kind: FeatureKind): String = when (kind) {
    FeatureKind.EVENT -> "触发器是瞬时事件，任一触发器命中即可启动自动化。"
    FeatureKind.STATE -> "状态持续存在，可用于进入/退出自动化。"
    FeatureKind.ACTION -> "动作按顺序执行，也可以使用分支、循环、并行和流程。"
    FeatureKind.CONDITION -> "约束用于限制自动化或动作何时可以执行。"
}

private fun catalogCategory(kind: FeatureKind, descriptor: FeatureDescriptor): CatalogCategory {
    val category = descriptor.category
    return when (kind) {
        FeatureKind.EVENT, FeatureKind.STATE -> when (category) {
            FeatureCategory.APP -> CatalogCategory("applications", "应用", "应用启动、安装、前后台与组件事件", 10)
            FeatureCategory.DEVICE -> CatalogCategory("battery_power", "电池 / 电源", "电池、充电、电源和设备状态", 20)
            FeatureCategory.NETWORK -> CatalogCategory("connectivity", "连接", "Wi‑Fi、移动网络、VPN、蓝牙与网络", 30)
            FeatureCategory.NOTIFICATION -> CatalogCategory("notifications", "通知", "通知出现、消失与内容事件", 40)
            FeatureCategory.DISPLAY -> CatalogCategory("device_events", "屏幕 / 设备事件", "亮灭屏、解锁、显示与系统状态", 50)
            FeatureCategory.AUDIO -> CatalogCategory("media_audio", "媒体 / 音频", "媒体、声音与音频状态", 60)
            FeatureCategory.UI_AUTOMATION -> CatalogCategory("user_input", "用户输入", "按键、手势、屏幕内容与交互", 70)
            FeatureCategory.CORE, FeatureCategory.FLOW, FeatureCategory.VARIABLE -> CatalogCategory("yauto", "YAuto", "手动、流程、变量和 YAuto 内部事件", 80)
            FeatureCategory.SYSTEM -> CatalogCategory("system", "系统事件", "Android 系统广播与系统状态", 90)
            else -> CatalogCategory("advanced", "高级 / 其它", "脚本、兼容层和高级事件", 100)
        }
        FeatureKind.ACTION -> when (category) {
            FeatureCategory.APP -> CatalogCategory("applications", "应用", "启动、关闭、Intent、URI 与应用控制", 10)
            FeatureCategory.DEVICE -> CatalogCategory("device", "设备设置", "振动、电源与设备控制", 20)
            FeatureCategory.NETWORK -> CatalogCategory("connectivity", "连接", "网络、Wi‑Fi、VPN 与连接控制", 30)
            FeatureCategory.DISPLAY -> CatalogCategory("display", "显示", "亮度、屏幕、状态栏和显示控制", 40)
            FeatureCategory.AUDIO -> CatalogCategory("volume_media", "音量 / 媒体", "音量、播放和音频控制", 50)
            FeatureCategory.NOTIFICATION -> CatalogCategory("notifications", "通知", "显示、清除和操作通知", 60)
            FeatureCategory.FILE -> CatalogCategory("files", "文件", "文件、目录与存储操作", 70)
            FeatureCategory.VARIABLE -> CatalogCategory("variables", "变量", "变量、数组、对象与数据处理", 80)
            FeatureCategory.FLOW, FeatureCategory.CORE -> CatalogCategory("flow", "流程 / 控制", "等待、日志、流程与执行控制", 90)
            FeatureCategory.UI_AUTOMATION -> CatalogCategory("ui", "UI 交互", "点击、输入、手势、OCR 与界面自动化", 100)
            FeatureCategory.SCRIPT -> CatalogCategory("script", "脚本 / 网络", "Shell、脚本、HTTP 与高级逻辑", 110)
            FeatureCategory.SYSTEM -> CatalogCategory("system", "系统", "系统服务和特权操作", 120)
            else -> CatalogCategory("advanced", "高级 / 其它", "高级系统能力与扩展", 130)
        }
        FeatureKind.CONDITION -> when (category) {
            FeatureCategory.APP -> CatalogCategory("applications", "应用", "应用安装、运行与前后台状态", 10)
            FeatureCategory.DEVICE -> CatalogCategory("battery_power", "电池 / 电源", "电池、充电与设备状态", 20)
            FeatureCategory.NETWORK -> CatalogCategory("connectivity", "连接", "网络、VPN、蓝牙和连接状态", 30)
            FeatureCategory.DISPLAY -> CatalogCategory("display", "屏幕 / 显示", "亮灭屏、亮度和显示状态", 40)
            FeatureCategory.AUDIO -> CatalogCategory("audio", "音频", "音量、媒体与音频状态", 50)
            FeatureCategory.NOTIFICATION -> CatalogCategory("notifications", "通知", "通知存在与通知状态", 60)
            FeatureCategory.VARIABLE -> CatalogCategory("variables", "变量", "变量存在、比较和表达式", 70)
            FeatureCategory.CORE, FeatureCategory.FLOW -> CatalogCategory("yauto", "YAuto", "流程、运行和内部约束", 80)
            FeatureCategory.SYSTEM -> CatalogCategory("system", "系统状态", "Android 系统设置与系统状态", 90)
            else -> CatalogCategory("advanced", "高级 / 其它", "高级和扩展约束", 100)
        }
    }
}

private fun ConfigValue?.editorText(): String = when (this) {
    null, ConfigValue.NullValue -> ""
    is ConfigValue.StringValue -> value
    is ConfigValue.NumberValue -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    is ConfigValue.BooleanValue -> value.toString()
    is ConfigValue.ListValue -> value.joinToString(",") { it.editorText() }
    is ConfigValue.ObjectValue -> value.entries.joinToString(",") { "${it.key}=${it.value.editorText()}" }
}
