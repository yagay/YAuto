package com.yagay.yauto.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.model.*
import com.yagay.yauto.core.registry.*
import com.yagay.yauto.ui.design.YSectionCard
import java.util.UUID

private enum class EditorSection(val title: String, val kind: FeatureKind) {
    EVENT("触发器", FeatureKind.EVENT),
    STATE("状态", FeatureKind.STATE),
    ENTER("进入动作", FeatureKind.ACTION),
    EVENT_ACTION("事件动作", FeatureKind.ACTION),
    EXIT("退出动作", FeatureKind.ACTION),
    CONDITION("条件", FeatureKind.CONDITION),
}

private data class EditTarget(
    val section: EditorSection,
    val index: Int? = null,
    val existing: FeatureRef? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationEditorScreen(
    descriptors: List<FeatureDescriptor>,
    initial: Automation? = null,
    onSave: (Automation) -> Unit,
    onBack: () -> Unit,
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name ?: "新自动化") }
    var enabled by remember(initial?.id) { mutableStateOf(initial?.enabled ?: true) }
    var description by remember(initial?.id) { mutableStateOf(initial?.description.orEmpty()) }
    var events by remember(initial?.id) { mutableStateOf(initial?.activation?.events.orEmpty()) }
    var states by remember(initial?.id) { mutableStateOf(initial?.activation?.states.orEmpty()) }
    var onEnter by remember(initial?.id) { mutableStateOf(initial?.onEnter.orEmpty()) }
    var onEvent by remember(initial?.id) { mutableStateOf(initial?.onEvent.orEmpty()) }
    var onExit by remember(initial?.id) { mutableStateOf(initial?.onExit.orEmpty()) }
    var variables by remember(initial?.id) { mutableStateOf(initial?.variables.orEmpty()) }

    val originalCondition = initial?.activation?.condition
    val simpleInitialConditions = remember(initial?.id) { extractSimpleConditions(originalCondition) }
    val preservedComplexCondition = remember(initial?.id) {
        if (simpleInitialConditions != null) null else originalCondition
    }
    var conditions by remember(initial?.id) { mutableStateOf(simpleInitialConditions.orEmpty()) }

    var pickerTarget by remember { mutableStateOf<EditTarget?>(null) }
    var configTarget by remember { mutableStateOf<EditTarget?>(null) }
    var configDescriptor by remember { mutableStateOf<FeatureDescriptor?>(null) }
    var variableDialog by remember { mutableStateOf<Pair<String?, ConfigValue?>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial == null) "新建自动化" else "编辑自动化") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    TextButton(
                        enabled = name.isNotBlank(),
                        onClick = {
                            val simple = conditions.map { PredicateNode.Condition(it) }
                            val condition = when {
                                preservedComplexCondition != null && simple.isEmpty() -> preservedComplexCondition
                                preservedComplexCondition != null -> PredicateNode.All(listOf(preservedComplexCondition) + simple)
                                simple.isEmpty() -> null
                                simple.size == 1 -> simple.single()
                                else -> PredicateNode.All(simple)
                            }
                            onSave(
                                Automation(
                                    id = initial?.id ?: AutomationId(UUID.randomUUID().toString()),
                                    name = name.trim(),
                                    enabled = enabled,
                                    workspaceId = initial?.workspaceId,
                                    activation = Activation(events = events, states = states, condition = condition),
                                    onEnter = onEnter,
                                    onEvent = onEvent,
                                    onExit = onExit,
                                    variables = variables,
                                    executionPolicy = initial?.executionPolicy ?: ExecutionPolicy(),
                                    description = description.trim().ifBlank { null },
                                    source = initial?.source,
                                )
                            )
                        }
                    ) { Text("保存") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                YSectionCard("基本信息") {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("名称") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("说明") },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("启用", modifier = Modifier.weight(1f))
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                }
            }

            item {
                FeatureSection(
                    title = "触发器",
                    subtitle = "任一事件命中即可触发；状态规则可同时控制进入/退出。",
                    features = events,
                    descriptors = descriptors,
                    onAdd = { pickerTarget = EditTarget(EditorSection.EVENT) },
                    onEdit = { index, feature -> openFeatureEditor(EditorSection.EVENT, index, feature, descriptors) { t, d -> configTarget = t; configDescriptor = d } },
                    onMove = { from, to -> events = events.moved(from, to) },
                    onDelete = { index -> events = events.removedAt(index) },
                )
            }

            item {
                FeatureSection(
                    title = "状态",
                    subtitle = "全部状态满足时进入；从满足变为不满足时执行退出动作。",
                    features = states,
                    descriptors = descriptors,
                    onAdd = { pickerTarget = EditTarget(EditorSection.STATE) },
                    onEdit = { index, feature -> openFeatureEditor(EditorSection.STATE, index, feature, descriptors) { t, d -> configTarget = t; configDescriptor = d } },
                    onMove = { from, to -> states = states.moved(from, to) },
                    onDelete = { index -> states = states.removedAt(index) },
                )
            }

            item {
                ActionSection(
                    title = "进入动作",
                    subtitle = "状态从不满足变为满足时执行。",
                    nodes = onEnter,
                    descriptors = descriptors,
                    onAdd = { pickerTarget = EditTarget(EditorSection.ENTER) },
                    onEdit = { index, feature -> openFeatureEditor(EditorSection.ENTER, index, feature, descriptors) { t, d -> configTarget = t; configDescriptor = d } },
                    onMove = { from, to -> onEnter = onEnter.moved(from, to) },
                    onDelete = { index -> onEnter = onEnter.removedAt(index) },
                )
            }

            item {
                ActionSection(
                    title = "事件动作",
                    subtitle = "事件命中时执行；普通事件自动化主要使用这里。",
                    nodes = onEvent,
                    descriptors = descriptors,
                    onAdd = { pickerTarget = EditTarget(EditorSection.EVENT_ACTION) },
                    onEdit = { index, feature -> openFeatureEditor(EditorSection.EVENT_ACTION, index, feature, descriptors) { t, d -> configTarget = t; configDescriptor = d } },
                    onMove = { from, to -> onEvent = onEvent.moved(from, to) },
                    onDelete = { index -> onEvent = onEvent.removedAt(index) },
                )
            }

            item {
                ActionSection(
                    title = "退出动作",
                    subtitle = "状态从满足变为不满足时执行。",
                    nodes = onExit,
                    descriptors = descriptors,
                    onAdd = { pickerTarget = EditTarget(EditorSection.EXIT) },
                    onEdit = { index, feature -> openFeatureEditor(EditorSection.EXIT, index, feature, descriptors) { t, d -> configTarget = t; configDescriptor = d } },
                    onMove = { from, to -> onExit = onExit.moved(from, to) },
                    onDelete = { index -> onExit = onExit.removedAt(index) },
                )
            }

            item {
                FeatureSection(
                    title = "条件",
                    subtitle = if (preservedComplexCondition == null) "当前简单条件按 AND 组合。" else "原有复杂条件树会被保留；新增条件会与其按 AND 组合。",
                    features = conditions,
                    descriptors = descriptors,
                    onAdd = { pickerTarget = EditTarget(EditorSection.CONDITION) },
                    onEdit = { index, feature -> openFeatureEditor(EditorSection.CONDITION, index, feature, descriptors) { t, d -> configTarget = t; configDescriptor = d } },
                    onMove = { from, to -> conditions = conditions.moved(from, to) },
                    onDelete = { index -> conditions = conditions.removedAt(index) },
                )
            }

            item {
                VariableSection(
                    variables = variables,
                    onAdd = { variableDialog = "" to null },
                    onEdit = { key, value -> variableDialog = key to value },
                    onDelete = { key -> variables = variables - key },
                )
            }
        }
    }

    pickerTarget?.let { target ->
        FeaturePickerDialog(
            kind = target.section.kind,
            descriptors = descriptors,
            onDismiss = { pickerTarget = null },
            onPick = { descriptor ->
                pickerTarget = null
                val next = target.copy(existing = null)
                if (descriptor.fields.isEmpty()) {
                    val feature = FeatureRef(descriptor.id.value, descriptor.schemaVersion)
                    when (target.section) {
                        EditorSection.EVENT -> events = events + feature
                        EditorSection.STATE -> states = states + feature
                        EditorSection.CONDITION -> conditions = conditions + feature
                        EditorSection.ENTER -> onEnter = onEnter + actionNode(feature)
                        EditorSection.EVENT_ACTION -> onEvent = onEvent + actionNode(feature)
                        EditorSection.EXIT -> onExit = onExit + actionNode(feature)
                    }
                } else {
                    configTarget = next
                    configDescriptor = descriptor
                }
            }
        )
    }

    val descriptor = configDescriptor
    val target = configTarget
    if (descriptor != null && target != null) {
        FeatureConfigDialog(
            descriptor = descriptor,
            initial = target.existing,
            onDismiss = { configTarget = null; configDescriptor = null },
            onSave = { feature ->
                when (target.section) {
                    EditorSection.EVENT -> events = events.upsert(target.index, feature)
                    EditorSection.STATE -> states = states.upsert(target.index, feature)
                    EditorSection.CONDITION -> conditions = conditions.upsert(target.index, feature)
                    EditorSection.ENTER -> onEnter = onEnter.upsertAction(target.index, feature)
                    EditorSection.EVENT_ACTION -> onEvent = onEvent.upsertAction(target.index, feature)
                    EditorSection.EXIT -> onExit = onExit.upsertAction(target.index, feature)
                }
                configTarget = null
                configDescriptor = null
            }
        )
    }

    variableDialog?.let { (oldName, oldValue) ->
        VariableDialog(
            initialName = oldName.orEmpty(),
            initialValue = oldValue.toEditorText(),
            onDismiss = { variableDialog = null },
            onSave = { newName, value ->
                val clean = newName.trim()
                if (clean.isNotBlank()) {
                    variables = variables.toMutableMap().apply {
                        if (!oldName.isNullOrBlank() && oldName != clean) remove(oldName)
                        put(clean, ConfigValue.StringValue(value))
                    }
                }
                variableDialog = null
            }
        )
    }
}

@Composable
private fun FeatureSection(
    title: String,
    subtitle: String,
    features: List<FeatureRef>,
    descriptors: List<FeatureDescriptor>,
    onAdd: () -> Unit,
    onEdit: (Int, FeatureRef) -> Unit,
    onMove: (Int, Int) -> Unit,
    onDelete: (Int) -> Unit,
) {
    YSectionCard(title, subtitle) {
        features.forEachIndexed { index, feature ->
            FeatureRow(
                title = descriptors.firstOrNull { it.id.value == feature.typeId }?.title ?: feature.typeId,
                subtitle = feature.config.summary(),
                editable = descriptors.any { it.id.value == feature.typeId },
                onClick = { onEdit(index, feature) },
                onUp = if (index > 0) ({ onMove(index, index - 1) }) else null,
                onDown = if (index < features.lastIndex) ({ onMove(index, index + 1) }) else null,
                onDelete = { onDelete(index) },
            )
        }
        OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("+ 添加") }
    }
}

@Composable
private fun ActionSection(
    title: String,
    subtitle: String,
    nodes: List<ActionNode>,
    descriptors: List<FeatureDescriptor>,
    onAdd: () -> Unit,
    onEdit: (Int, FeatureRef) -> Unit,
    onMove: (Int, Int) -> Unit,
    onDelete: (Int) -> Unit,
) {
    YSectionCard(title, subtitle) {
        nodes.forEachIndexed { index, node ->
            val feature = (node as? ActionNode.Action)?.feature
            val label = feature?.let { descriptors.firstOrNull { d -> d.id.value == it.typeId }?.title ?: it.typeId }
                ?: node.nodeLabel()
            FeatureRow(
                title = label,
                subtitle = feature?.config?.summary().orEmpty(),
                editable = feature != null && descriptors.any { it.id.value == feature.typeId },
                onClick = { if (feature != null) onEdit(index, feature) },
                onUp = if (index > 0) ({ onMove(index, index - 1) }) else null,
                onDown = if (index < nodes.lastIndex) ({ onMove(index, index + 1) }) else null,
                onDelete = { onDelete(index) },
            )
        }
        OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("+ 添加动作") }
    }
}

@Composable
private fun FeatureRow(
    title: String,
    subtitle: String,
    editable: Boolean,
    onClick: () -> Unit,
    onUp: (() -> Unit)?,
    onDown: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    Surface(
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().then(if (editable) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onUp?.invoke() }, enabled = onUp != null) { Text("↑") }
                TextButton(onClick = { onDown?.invoke() }, enabled = onDown != null) { Text("↓") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

@Composable
private fun VariableSection(
    variables: Map<String, ConfigValue>,
    onAdd: () -> Unit,
    onEdit: (String, ConfigValue) -> Unit,
    onDelete: (String) -> Unit,
) {
    YSectionCard("局部变量", "在此自动化执行期间使用，可通过 ${'$'}{name} 或导入的 Tasker %name 引用。") {
        variables.forEach { (key, value) ->
            Surface(
                tonalElevation = 1.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().clickable { onEdit(key, value) },
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(key)
                        Text(value.toEditorText(), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onDelete(key) }) { Text("删除") }
                }
            }
        }
        OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("+ 添加变量") }
    }
}

@Composable
private fun FeaturePickerDialog(
    kind: FeatureKind,
    descriptors: List<FeatureDescriptor>,
    onDismiss: () -> Unit,
    onPick: (FeatureDescriptor) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val available = remember(descriptors, kind, query) {
        descriptors.filter {
            it.kind == kind && it.category != FeatureCategory.COMPATIBILITY &&
                (query.isBlank() || listOf(it.title, it.description, it.id.value, it.keywords.joinToString(" ")).any { text -> text.contains(query, true) })
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择${kind.label()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索") },
                    singleLine = true,
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(available, key = { it.id.value }) { descriptor ->
                        ListItem(
                            headlineContent = { Text(descriptor.title) },
                            supportingContent = { Text("${descriptor.category.name} · ${descriptor.description}") },
                            modifier = Modifier.clickable { onPick(descriptor) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun FeatureConfigDialog(
    descriptor: FeatureDescriptor,
    initial: FeatureRef?,
    onDismiss: () -> Unit,
    onSave: (FeatureRef) -> Unit,
) {
    val initialTexts = remember(descriptor.id.value, initial) {
        descriptor.fields.associate { field -> field.key to initial?.config?.get(field.key).toEditorText() }
    }
    var values by remember(descriptor.id.value, initial) { mutableStateOf(initialTexts) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(descriptor.title) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { Text(descriptor.description, style = MaterialTheme.typography.bodySmall) }
                items(descriptor.fields, key = { it.key }) { field ->
                    when (field) {
                        is FieldSchema.Toggle -> {
                            val checked = values[field.key]?.toBooleanStrictOrNull() ?: false
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(field.label, modifier = Modifier.weight(1f))
                                Switch(checked = checked, onCheckedChange = { values = values + (field.key to it.toString()) })
                            }
                        }
                        is FieldSchema.Choice -> {
                            Column {
                                Text(field.label, style = MaterialTheme.typography.labelLarge)
                                field.options.forEach { option ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().clickable { values = values + (field.key to option) },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(selected = values[field.key] == option, onClick = { values = values + (field.key to option) })
                                        Text(option)
                                    }
                                }
                            }
                        }
                        else -> {
                            val numeric = field is FieldSchema.Number || field is FieldSchema.Duration
                            OutlinedTextField(
                                value = values[field.key].orEmpty(),
                                onValueChange = { values = values + (field.key to it) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(field.label) },
                                minLines = if (field is FieldSchema.Text && field.multiline) 3 else 1,
                                keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val config = buildMap<String, ConfigValue> {
                    descriptor.fields.forEach { field ->
                        val raw = values[field.key].orEmpty()
                        when (field) {
                            is FieldSchema.Toggle -> put(field.key, ConfigValue.BooleanValue(raw.toBooleanStrictOrNull() ?: false))
                            is FieldSchema.Number, is FieldSchema.Duration -> raw.toDoubleOrNull()?.let { put(field.key, ConfigValue.NumberValue(it)) }
                            else -> if (raw.isNotEmpty() || field.required) put(field.key, ConfigValue.StringValue(raw))
                        }
                    }
                }
                onSave(FeatureRef(descriptor.id.value, descriptor.schemaVersion, config))
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun VariableDialog(
    initialName: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var value by remember(initialName, initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialName.isBlank()) "添加变量" else "编辑变量") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("变量名") }, singleLine = true)
                OutlinedTextField(value, { value = it }, label = { Text("值") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, value) }, enabled = name.isNotBlank()) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun openFeatureEditor(
    section: EditorSection,
    index: Int,
    feature: FeatureRef,
    descriptors: List<FeatureDescriptor>,
    open: (EditTarget, FeatureDescriptor) -> Unit,
) {
    descriptors.firstOrNull { it.id.value == feature.typeId }?.let { descriptor ->
        open(EditTarget(section, index, feature), descriptor)
    }
}

private fun actionNode(feature: FeatureRef): ActionNode.Action =
    ActionNode.Action(NodeId(UUID.randomUUID().toString()), feature)

private fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices || from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

private fun <T> List<T>.removedAt(index: Int): List<T> =
    if (index !in indices) this else toMutableList().apply { removeAt(index) }

private fun List<FeatureRef>.upsert(index: Int?, value: FeatureRef): List<FeatureRef> =
    if (index == null || index !in indices) this + value else toMutableList().apply { this[index] = value }

private fun List<ActionNode>.upsertAction(index: Int?, feature: FeatureRef): List<ActionNode> =
    if (index == null || index !in indices) this + actionNode(feature)
    else toMutableList().apply {
        val old = this[index] as? ActionNode.Action
        this[index] = if (old != null) old.copy(feature = feature) else actionNode(feature)
    }

private fun extractSimpleConditions(node: PredicateNode?): List<FeatureRef>? = when (node) {
    null -> emptyList()
    is PredicateNode.Condition -> listOf(node.feature)
    is PredicateNode.All -> node.children.mapNotNull { (it as? PredicateNode.Condition)?.feature }.takeIf { it.size == node.children.size }
    else -> null
}

private fun FeatureKind.label(): String = when (this) {
    FeatureKind.EVENT -> "触发器"
    FeatureKind.STATE -> "状态"
    FeatureKind.CONDITION -> "条件"
    FeatureKind.ACTION -> "动作"
}

private fun ActionNode.nodeLabel(): String = when (this) {
    is ActionNode.Action -> feature.typeId
    is ActionNode.If -> "If 条件"
    is ActionNode.Switch -> "Switch"
    is ActionNode.Repeat -> "Repeat × $times"
    is ActionNode.While -> "While"
    is ActionNode.ForEach -> "ForEach $variableName"
    is ActionNode.Parallel -> "Parallel (${branches.size})"
    is ActionNode.Try -> "Try / Catch"
    is ActionNode.CallFlow -> "调用流程 ${flowId.value}"
    is ActionNode.Return -> "Return"
    is ActionNode.Break -> "Break"
    is ActionNode.Continue -> "Continue"
}

private fun ConfigMap.summary(): String = entries.take(3).joinToString(" · ") { (key, value) -> "$key=${value.toEditorText()}" }

private fun ConfigValue?.toEditorText(): String = when (this) {
    null, ConfigValue.NullValue -> ""
    is ConfigValue.StringValue -> value
    is ConfigValue.NumberValue -> value.toString().removeSuffix(".0")
    is ConfigValue.BooleanValue -> value.toString()
    is ConfigValue.ListValue -> value.joinToString(",") { it.toEditorText() }
    is ConfigValue.ObjectValue -> value.entries.joinToString(",") { "${it.key}:${it.value.toEditorText()}" }
}
