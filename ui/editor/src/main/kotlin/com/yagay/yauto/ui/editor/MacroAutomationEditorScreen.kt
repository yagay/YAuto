package com.yagay.yauto.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.model.*
import com.yagay.yauto.core.registry.*
import com.yagay.yauto.ui.design.*
import java.util.UUID

private enum class MacroActionPhase { EVENT, ENTER, EXIT }
private enum class ActivationMode { EVENT, STATE }

private data class MacroEditRequest(
    val kind: FeatureKind,
    val index: Int? = null,
    val initial: FeatureRef? = null,
    val actionPhase: MacroActionPhase? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacroAutomationEditorScreen(
    descriptors: List<FeatureDescriptor>,
    initial: Automation? = null,
    onSave: (Automation) -> Unit,
    onBack: () -> Unit,
    flows: List<Flow> = emptyList(),
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name ?: "") }
    var enabled by remember(initial?.id) { mutableStateOf(initial?.enabled ?: true) }
    var description by remember(initial?.id) { mutableStateOf(initial?.description.orEmpty()) }
    var events by remember(initial?.id) { mutableStateOf(initial?.activation?.events.orEmpty()) }
    var states by remember(initial?.id) { mutableStateOf(initial?.activation?.states.orEmpty()) }
    var onEvent by remember(initial?.id) { mutableStateOf(initial?.onEvent.orEmpty()) }
    var onEnter by remember(initial?.id) { mutableStateOf(initial?.onEnter.orEmpty()) }
    var onExit by remember(initial?.id) { mutableStateOf(initial?.onExit.orEmpty()) }
    var variables by remember(initial?.id) { mutableStateOf(initial?.variables.orEmpty()) }
    var policy by remember(initial?.id) { mutableStateOf(initial?.executionPolicy ?: ExecutionPolicy()) }

    val originalCondition = initial?.activation?.condition
    val simpleInitial = remember(initial?.id) { simpleConditions(originalCondition) }
    var conditions by remember(initial?.id) { mutableStateOf(simpleInitial.orEmpty()) }
    var preservedComplex by remember(initial?.id) { mutableStateOf(if (simpleInitial == null) originalCondition else null) }

    var activationMode by remember { mutableStateOf(if (events.isNotEmpty() || states.isEmpty()) ActivationMode.EVENT else ActivationMode.STATE) }
    var actionPhase by remember { mutableStateOf(MacroActionPhase.EVENT) }
    var request by remember { mutableStateOf<MacroEditRequest?>(null) }
    var menu by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var treePhase by remember { mutableStateOf<MacroActionPhase?>(null) }
    var advanced by remember { mutableStateOf(false) }
    var variableEdit by remember { mutableStateOf<Pair<String?, String>?>(null) }

    val runtimeLimit = policy.maxRuntimeMs
    val loopLimit = policy.maxLoopIterations

    fun save() {
        val simple = conditions.map { PredicateNode.Condition(it) }
        val predicate = when {
            preservedComplex != null && simple.isEmpty() -> preservedComplex
            preservedComplex != null -> PredicateNode.All(listOfNotNull(preservedComplex) + simple)
            simple.isEmpty() -> null
            simple.size == 1 -> simple.single()
            else -> PredicateNode.All(simple)
        }
        onSave(
            Automation(
                id = initial?.id ?: AutomationId(UUID.randomUUID().toString()),
                name = name.trim().ifBlank { "未命名自动化" },
                enabled = enabled,
                workspaceId = initial?.workspaceId,
                activation = Activation(events = events, states = states, condition = predicate),
                onEnter = onEnter,
                onEvent = onEvent,
                onExit = onExit,
                variables = variables,
                executionPolicy = policy,
                description = description.trim().ifBlank { null },
                source = initial?.source,
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial == null) "添加自动化" else "编辑自动化") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹") } },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("启用", style = MaterialTheme.typography.labelMedium)
                        Switch(enabled, { enabled = it })
                        TextButton(onClick = ::save) { Text("保存") }
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(
                    name,
                    { name = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("自动化名称") },
                    singleLine = true,
                )
            }

            item {
                MacroSection(
                    title = "触发器",
                    color = MacroPalette.Trigger,
                    count = events.size + states.size,
                    subtitle = "事件触发，或持续状态进入/退出",
                    onAdd = {
                        request = MacroEditRequest(if (activationMode == ActivationMode.EVENT) FeatureKind.EVENT else FeatureKind.STATE)
                    },
                ) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = activationMode == ActivationMode.EVENT,
                            onClick = { activationMode = ActivationMode.EVENT },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                        ) { Text("事件 ${events.size}") }
                        SegmentedButton(
                            selected = activationMode == ActivationMode.STATE,
                            onClick = { activationMode = ActivationMode.STATE },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                        ) { Text("状态 ${states.size}") }
                    }
                    val activationItems = if (activationMode == ActivationMode.EVENT) events else states
                    val kind = if (activationMode == ActivationMode.EVENT) FeatureKind.EVENT else FeatureKind.STATE
                    if (activationItems.isEmpty()) EmptyHint(if (kind == FeatureKind.EVENT) "点击 ＋ 添加触发器" else "点击 ＋ 添加持续状态")
                    activationItems.forEachIndexed { index, feature ->
                        MacroItemRow(
                            title = featureTitle(feature, descriptors),
                            subtitle = featureSummary(feature),
                            accent = if (kind == FeatureKind.EVENT) MacroPalette.Trigger else MacroPalette.State,
                            onClick = { request = MacroEditRequest(kind, index, feature) },
                            onMenu = { menu = (if (kind == FeatureKind.EVENT) "event" else "state") to index },
                        )
                    }
                }
            }

            item {
                MacroSection(
                    title = "动作",
                    color = MacroPalette.Action,
                    count = onEvent.size + onEnter.size + onExit.size,
                    subtitle = "按顺序执行；支持分支、循环、并行与调用流程",
                    onAdd = { request = MacroEditRequest(FeatureKind.ACTION, actionPhase = actionPhase) },
                    trailing = {
                        TextButton(onClick = { treePhase = actionPhase }) { Text("结构", color = androidx.compose.ui.graphics.Color.White) }
                    },
                ) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val phases = listOf(
                            MacroActionPhase.EVENT to "事件 ${onEvent.size}",
                            MacroActionPhase.ENTER to "进入 ${onEnter.size}",
                            MacroActionPhase.EXIT to "退出 ${onExit.size}",
                        )
                        phases.forEachIndexed { index, pair ->
                            SegmentedButton(
                                selected = actionPhase == pair.first,
                                onClick = { actionPhase = pair.first },
                                shape = SegmentedButtonDefaults.itemShape(index, phases.size),
                            ) { Text(pair.second) }
                        }
                    }
                    val nodes = when (actionPhase) {
                        MacroActionPhase.EVENT -> onEvent
                        MacroActionPhase.ENTER -> onEnter
                        MacroActionPhase.EXIT -> onExit
                    }
                    if (nodes.isEmpty()) EmptyHint("点击 ＋ 添加动作")
                    nodes.forEachIndexed { index, node ->
                        val feature = (node as? ActionNode.Action)?.feature
                        MacroItemRow(
                            title = feature?.let { featureTitle(it, descriptors) } ?: actionNodeTitle(node, flows),
                            subtitle = feature?.let(::featureSummary),
                            accent = MacroPalette.Action,
                            onClick = {
                                if (feature != null) request = MacroEditRequest(FeatureKind.ACTION, index, feature, actionPhase)
                                else treePhase = actionPhase
                            },
                            onMenu = { menu = "action:${actionPhase.name}" to index },
                        )
                    }
                }
            }

            item {
                MacroSection(
                    title = "约束",
                    color = MacroPalette.Constraint,
                    count = conditions.size + if (preservedComplex != null) 1 else 0,
                    subtitle = "只有约束满足时自动化才会继续",
                    onAdd = { request = MacroEditRequest(FeatureKind.CONDITION) },
                ) {
                    if (preservedComplex != null) {
                        MacroItemRow(
                            title = "复杂条件组合",
                            subtitle = "从旧规则保留；可在结构编辑器中继续维护",
                            accent = MacroPalette.Constraint,
                            onClick = {},
                        )
                    }
                    if (conditions.isEmpty() && preservedComplex == null) EmptyHint("没有约束：自动化不会被额外限制")
                    conditions.forEachIndexed { index, feature ->
                        MacroItemRow(
                            title = featureTitle(feature, descriptors),
                            subtitle = featureSummary(feature),
                            accent = MacroPalette.Constraint,
                            onClick = { request = MacroEditRequest(FeatureKind.CONDITION, index, feature) },
                            onMenu = { menu = "condition" to index },
                        )
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth().clickable { advanced = !advanced }) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("高级设置", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            Text(if (advanced) "⌃" else "⌄")
                        }
                        if (advanced) {
                            OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text("说明") }, minLines = 2)
                            Text("局部变量", fontWeight = FontWeight.Medium)
                            variables.forEach { (key, value) ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f).clickable { variableEdit = key to value.asText() }) {
                                        Text(key)
                                        Text(value.asText(), style = MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton(onClick = { variables = variables - key }) { Text("删除") }
                                }
                            }
                            OutlinedButton(onClick = { variableEdit = null to "" }, modifier = Modifier.fillMaxWidth()) { Text("＋ 添加局部变量") }
                            HorizontalDivider()
                            Text("冲突策略", fontWeight = FontWeight.Medium)
                            listOf(
                                ConflictPolicy.QUEUE to "排队运行",
                                ConflictPolicy.IGNORE_NEW to "忽略新触发",
                                ConflictPolicy.CANCEL_PREVIOUS to "取消上次运行",
                                ConflictPolicy.PARALLEL to "并行运行",
                            ).forEach { (value, label) ->
                                Row(Modifier.fillMaxWidth().clickable { policy = policy.copy(conflictPolicy = value) }, verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(policy.conflictPolicy == value, { policy = policy.copy(conflictPolicy = value) })
                                    Text(label)
                                }
                            }
                            Text("最长运行 ${runtimeLimit} ms · 最大循环 $loopLimit", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    request?.let { edit ->
        MacroFeaturePickerDialog(
            kind = edit.kind,
            descriptors = descriptors,
            initial = edit.initial,
            onDismiss = { request = null },
            onPick = { feature ->
                when (edit.kind) {
                    FeatureKind.EVENT -> events = events.upsertFeature(edit.index, feature)
                    FeatureKind.STATE -> states = states.upsertFeature(edit.index, feature)
                    FeatureKind.CONDITION -> conditions = conditions.upsertFeature(edit.index, feature)
                    FeatureKind.ACTION -> when (edit.actionPhase ?: MacroActionPhase.EVENT) {
                        MacroActionPhase.EVENT -> onEvent = onEvent.upsertActionFeature(edit.index, feature)
                        MacroActionPhase.ENTER -> onEnter = onEnter.upsertActionFeature(edit.index, feature)
                        MacroActionPhase.EXIT -> onExit = onExit.upsertActionFeature(edit.index, feature)
                    }
                }
                request = null
            },
        )
    }

    menu?.let { (section, index) ->
        val size = when {
            section == "event" -> events.size
            section == "state" -> states.size
            section == "condition" -> conditions.size
            section.startsWith("action:") -> when (MacroActionPhase.valueOf(section.substringAfter(':'))) {
                MacroActionPhase.EVENT -> onEvent.size
                MacroActionPhase.ENTER -> onEnter.size
                MacroActionPhase.EXIT -> onExit.size
            }
            else -> 0
        }
        AlertDialog(
            onDismissRequest = { menu = null },
            title = { Text("项目操作") },
            text = {
                Column {
                    TextButton(enabled = index > 0, onClick = {
                        when {
                            section == "event" -> events = events.moveItem(index, index - 1)
                            section == "state" -> states = states.moveItem(index, index - 1)
                            section == "condition" -> conditions = conditions.moveItem(index, index - 1)
                            section.startsWith("action:") -> when (MacroActionPhase.valueOf(section.substringAfter(':'))) {
                                MacroActionPhase.EVENT -> onEvent = onEvent.moveItem(index, index - 1)
                                MacroActionPhase.ENTER -> onEnter = onEnter.moveItem(index, index - 1)
                                MacroActionPhase.EXIT -> onExit = onExit.moveItem(index, index - 1)
                            }
                        }
                        menu = null
                    }) { Text("上移") }
                    TextButton(enabled = index < size - 1, onClick = {
                        when {
                            section == "event" -> events = events.moveItem(index, index + 1)
                            section == "state" -> states = states.moveItem(index, index + 1)
                            section == "condition" -> conditions = conditions.moveItem(index, index + 1)
                            section.startsWith("action:") -> when (MacroActionPhase.valueOf(section.substringAfter(':'))) {
                                MacroActionPhase.EVENT -> onEvent = onEvent.moveItem(index, index + 1)
                                MacroActionPhase.ENTER -> onEnter = onEnter.moveItem(index, index + 1)
                                MacroActionPhase.EXIT -> onExit = onExit.moveItem(index, index + 1)
                            }
                        }
                        menu = null
                    }) { Text("下移") }
                    TextButton(onClick = {
                        when {
                            section == "event" -> events = events.removeItem(index)
                            section == "state" -> states = states.removeItem(index)
                            section == "condition" -> conditions = conditions.removeItem(index)
                            section.startsWith("action:") -> when (MacroActionPhase.valueOf(section.substringAfter(':'))) {
                                MacroActionPhase.EVENT -> onEvent = onEvent.removeItem(index)
                                MacroActionPhase.ENTER -> onEnter = onEnter.removeItem(index)
                                MacroActionPhase.EXIT -> onExit = onExit.removeItem(index)
                            }
                        }
                        menu = null
                    }) { Text("删除") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { menu = null }) { Text("取消") } },
        )
    }

    treePhase?.let { phase ->
        val nodes = when (phase) {
            MacroActionPhase.EVENT -> onEvent
            MacroActionPhase.ENTER -> onEnter
            MacroActionPhase.EXIT -> onExit
        }
        ActionTreeDialog(
            title = when (phase) {
                MacroActionPhase.EVENT -> "事件动作结构"
                MacroActionPhase.ENTER -> "进入动作结构"
                MacroActionPhase.EXIT -> "退出动作结构"
            },
            initial = nodes,
            descriptors = descriptors,
            flows = flows,
            onDismiss = { treePhase = null },
            onSave = {
                when (phase) {
                    MacroActionPhase.EVENT -> onEvent = it
                    MacroActionPhase.ENTER -> onEnter = it
                    MacroActionPhase.EXIT -> onExit = it
                }
                treePhase = null
            },
        )
    }

    variableEdit?.let { (oldName, oldValue) ->
        var variableName by remember(oldName) { mutableStateOf(oldName.orEmpty()) }
        var variableValue by remember(oldName, oldValue) { mutableStateOf(oldValue) }
        AlertDialog(
            onDismissRequest = { variableEdit = null },
            title = { Text(if (oldName == null) "添加局部变量" else "编辑局部变量") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(variableName, { variableName = it }, label = { Text("变量名") }, singleLine = true)
                    OutlinedTextField(variableValue, { variableValue = it }, label = { Text("值") })
                }
            },
            confirmButton = {
                TextButton(enabled = variableName.isNotBlank(), onClick = {
                    variables = variables.toMutableMap().apply {
                        oldName?.takeIf { it != variableName.trim() }?.let(::remove)
                        put(variableName.trim(), ConfigValue.StringValue(variableValue))
                    }
                    variableEdit = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { variableEdit = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun featureTitle(feature: FeatureRef, descriptors: List<FeatureDescriptor>): String =
    descriptors.firstOrNull { it.id.value == feature.typeId }?.title ?: feature.typeId

private fun featureSummary(feature: FeatureRef): String = feature.config.entries
    .filterNot { it.key.startsWith("source.") }
    .take(3)
    .joinToString(" · ") { "${it.key}=${it.value.asText()}" }

private fun actionNodeTitle(node: ActionNode, flows: List<Flow>): String = when (node) {
    is ActionNode.Action -> node.feature.typeId
    is ActionNode.If -> "条件分支"
    is ActionNode.Switch -> "多路分支"
    is ActionNode.Repeat -> "重复 ${node.times} 次"
    is ActionNode.While -> "条件循环"
    is ActionNode.ForEach -> "遍历列表"
    is ActionNode.Parallel -> "并行分支"
    is ActionNode.Try -> "尝试 / 捕获"
    is ActionNode.CallFlow -> "调用流程：${flows.firstOrNull { it.id == node.flowId }?.name ?: node.flowId.value}"
    is ActionNode.Return -> "返回"
    is ActionNode.Break -> "退出循环"
    is ActionNode.Continue -> "继续循环"
}

private fun ConfigValue.asText(): String = when (this) {
    ConfigValue.NullValue -> ""
    is ConfigValue.StringValue -> value
    is ConfigValue.NumberValue -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    is ConfigValue.BooleanValue -> value.toString()
    is ConfigValue.ListValue -> value.joinToString(",") { it.asText() }
    is ConfigValue.ObjectValue -> value.entries.joinToString(",") { "${it.key}=${it.value.asText()}" }
}

private fun <T> List<T>.moveItem(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices || from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

private fun <T> List<T>.removeItem(index: Int): List<T> =
    if (index !in indices) this else toMutableList().apply { removeAt(index) }

private fun List<FeatureRef>.upsertFeature(index: Int?, feature: FeatureRef): List<FeatureRef> =
    if (index == null || index !in indices) this + feature else toMutableList().apply { this[index] = feature }

private fun List<ActionNode>.upsertActionFeature(index: Int?, feature: FeatureRef): List<ActionNode> =
    if (index == null || index !in indices) this + ActionNode.Action(NodeId(UUID.randomUUID().toString()), feature)
    else toMutableList().apply {
        val old = this[index] as? ActionNode.Action
        this[index] = old?.copy(feature = feature) ?: ActionNode.Action(NodeId(UUID.randomUUID().toString()), feature)
    }

private fun simpleConditions(node: PredicateNode?): List<FeatureRef>? = when (node) {
    null -> emptyList()
    is PredicateNode.Condition -> listOf(node.feature)
    is PredicateNode.All -> node.children.mapNotNull { (it as? PredicateNode.Condition)?.feature }.takeIf { it.size == node.children.size }
    else -> null
}
