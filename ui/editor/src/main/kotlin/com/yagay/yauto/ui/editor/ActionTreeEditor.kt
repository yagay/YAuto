package com.yagay.yauto.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yagay.yauto.core.model.*
import com.yagay.yauto.core.registry.*
import java.util.UUID

private fun nodeId() = NodeId(UUID.randomUUID().toString())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionTreeDialog(
    title: String,
    initial: List<ActionNode>,
    descriptors: List<FeatureDescriptor>,
    flows: List<Flow>,
    onDismiss: () -> Unit,
    onSave: (List<ActionNode>) -> Unit,
) {
    var nodes by remember { mutableStateOf(initial) }
    var adding by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ActionNode?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = { TextButton(onClick = onDismiss) { Text("取消") } },
                    actions = { TextButton(onClick = { onSave(nodes) }) { Text("保存") } },
                )
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                nodes.forEachIndexed { index, node ->
                    key(node.id.value) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(nodeLabel(node, descriptors, flows))
                                Row {
                                    TextButton(onClick = { editing = node }) { Text("编辑") }
                                    TextButton(enabled = index > 0, onClick = {
                                        nodes = nodes.toMutableList().apply { add(index - 1, removeAt(index)) }
                                    }) { Text("↑") }
                                    TextButton(enabled = index < nodes.lastIndex, onClick = {
                                        nodes = nodes.toMutableList().apply { add(index + 1, removeAt(index)) }
                                    }) { Text("↓") }
                                    TextButton(onClick = { nodes = nodes.filterNot { it.id == node.id } }) { Text("删除") }
                                }
                            }
                        }
                    }
                }
                Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("＋ 添加") }
            }
        }
    }

    if (adding) AlertDialog(
        onDismissRequest = { adding = false },
        title = { Text("添加动作或结构") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TextButton(onClick = { adding = false; picker = true }) { Text("普通动作") }
                val templates = listOf<Pair<String, () -> ActionNode>>(
                    "条件分支" to { ActionNode.If(nodeId(), PredicateNode.Literal(true), emptyList()) },
                    "多路分支" to { ActionNode.Switch(nodeId(), "", emptyList()) },
                    "重复次数" to { ActionNode.Repeat(nodeId(), 1, emptyList()) },
                    "条件循环" to { ActionNode.While(nodeId(), PredicateNode.Literal(false), emptyList()) },
                    "遍历列表" to { ActionNode.ForEach(nodeId(), emptyList(), "item", emptyList()) },
                    "并行分支" to { ActionNode.Parallel(nodeId(), listOf(emptyList(), emptyList())) },
                    "尝试 / 捕获 / 收尾" to { ActionNode.Try(nodeId(), emptyList()) },
                    "调用流程" to { ActionNode.CallFlow(nodeId(), flows.firstOrNull()?.id ?: FlowId("")) },
                    "返回值" to { ActionNode.Return(nodeId()) },
                    "退出循环" to { ActionNode.Break(nodeId()) },
                    "继续循环" to { ActionNode.Continue(nodeId()) },
                )
                templates.forEach { (name, create) ->
                    TextButton(onClick = {
                        val node = create()
                        nodes = nodes + node
                        editing = node
                        adding = false
                    }) { Text(name) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { adding = false }) { Text("取消") } },
    )

    if (picker) MacroFeaturePickerDialog(
        kind = FeatureKind.ACTION,
        descriptors = descriptors,
        onDismiss = { picker = false },
        onPick = { feature ->
            val node = ActionNode.Action(nodeId(), feature)
            nodes = nodes + node
            picker = false
        },
    )

    editing?.let { node ->
        NodeDialog(
            node,
            descriptors,
            flows,
            onDismiss = { editing = null },
            onSave = { updated ->
                nodes = nodes.map { if (it.id == node.id) updated else it }
                editing = null
            },
        )
    }
}

private fun nodeLabel(node: ActionNode, descriptors: List<FeatureDescriptor>, flows: List<Flow>): String = when (node) {
    is ActionNode.Action -> descriptors.firstOrNull { it.id.value == node.feature.typeId }?.title ?: node.feature.typeId
    is ActionNode.If -> "条件分支"
    is ActionNode.Switch -> "多路分支 (${node.cases.size})"
    is ActionNode.Repeat -> "重复 ${node.times} 次"
    is ActionNode.While -> "条件循环"
    is ActionNode.ForEach -> "遍历列表：${node.variableName}"
    is ActionNode.Parallel -> "并行 (${node.branches.size})"
    is ActionNode.Try -> "尝试 / 捕获 / 收尾"
    is ActionNode.CallFlow -> "调用：${flows.firstOrNull { it.id == node.flowId }?.name ?: node.flowId.value}"
    is ActionNode.Return -> "返回值"
    is ActionNode.Break -> "退出循环"
    is ActionNode.Continue -> "继续循环"
}

@Composable
private fun NodeDialog(
    initial: ActionNode,
    descriptors: List<FeatureDescriptor>,
    flows: List<Flow>,
    onDismiss: () -> Unit,
    onSave: (ActionNode) -> Unit,
) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var child by remember { mutableStateOf<Pair<String, List<ActionNode>>?>(null) }
    var saveChild by remember { mutableStateOf<((List<ActionNode>) -> Unit)?>(null) }
    var predicate by remember { mutableStateOf<PredicateNode?>(null) }
    var savePredicate by remember { mutableStateOf<((PredicateNode) -> Unit)?>(null) }

    fun children(label: String, actions: List<ActionNode>, update: (List<ActionNode>) -> Unit) {
        child = label to actions
        saveChild = update
    }
    fun condition(value: PredicateNode, update: (PredicateNode) -> Unit) {
        predicate = value
        savePredicate = update
    }

    val action = draft as? ActionNode.Action
    if (action != null && descriptors.any { it.id.value == action.feature.typeId }) {
        MacroFeaturePickerDialog(
            kind = FeatureKind.ACTION,
            descriptors = descriptors,
            initial = action.feature,
            onDismiss = onDismiss,
            onPick = { onSave(action.copy(feature = it)) },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(nodeLabel(draft, descriptors, flows)) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (val node = draft) {
                    is ActionNode.If -> {
                        TextButton(onClick = { condition(node.condition) { draft = node.copy(condition = it) } }) { Text("编辑条件") }
                        BranchButton("满足时", node.thenActions) { children("满足时", node.thenActions) { draft = node.copy(thenActions = it) } }
                        BranchButton("不满足时", node.elseActions) { children("不满足时", node.elseActions) { draft = node.copy(elseActions = it) } }
                    }
                    is ActionNode.Switch -> {
                        OutlinedTextField(node.expression, { draft = node.copy(expression = it) }, label = { Text("表达式 / 变量") })
                        node.cases.forEachIndexed { index, case ->
                            OutlinedTextField(
                                case.match,
                                { text -> draft = node.copy(cases = node.cases.toMutableList().apply { this[index] = case.copy(match = text) }) },
                                label = { Text("匹配值 ${index + 1}") },
                            )
                            BranchButton("分支 ${index + 1}", case.actions) {
                                children("分支 ${index + 1}", case.actions) { actions ->
                                    draft = node.copy(cases = node.cases.toMutableList().apply { this[index] = case.copy(actions = actions) })
                                }
                            }
                            TextButton(onClick = { draft = node.copy(cases = node.cases.filterIndexed { i, _ -> i != index }) }) { Text("删除此分支") }
                        }
                        TextButton(onClick = { draft = node.copy(cases = node.cases + SwitchCase("", emptyList())) }) { Text("＋ 分支") }
                        BranchButton("默认分支", node.defaultActions) { children("默认分支", node.defaultActions) { draft = node.copy(defaultActions = it) } }
                    }
                    is ActionNode.Repeat -> {
                        OutlinedTextField(node.times.toString(), { text ->
                            text.toIntOrNull()?.takeIf { it >= 0 }?.let { draft = node.copy(times = it) }
                        }, label = { Text("次数") })
                        BranchButton("循环动作", node.actions) { children("循环动作", node.actions) { draft = node.copy(actions = it) } }
                    }
                    is ActionNode.While -> {
                        TextButton(onClick = { condition(node.condition) { draft = node.copy(condition = it) } }) { Text("编辑循环条件") }
                        BranchButton("循环动作", node.actions) { children("循环动作", node.actions) { draft = node.copy(actions = it) } }
                    }
                    is ActionNode.ForEach -> {
                        OutlinedTextField(node.variableName, { draft = node.copy(variableName = it) }, label = { Text("当前项变量名") })
                        ValueListEditor(node.values) { draft = node.copy(values = it) }
                        BranchButton("循环动作", node.actions) { children("循环动作", node.actions) { draft = node.copy(actions = it) } }
                    }
                    is ActionNode.Parallel -> {
                        node.branches.forEachIndexed { index, actions ->
                            BranchButton("并行分支 ${index + 1}", actions) {
                                children("并行分支 ${index + 1}", actions) { updated ->
                                    draft = node.copy(branches = node.branches.toMutableList().apply { this[index] = updated })
                                }
                            }
                            TextButton(onClick = { draft = node.copy(branches = node.branches.filterIndexed { i, _ -> i != index }) }) { Text("删除分支") }
                        }
                        TextButton(onClick = { draft = node.copy(branches = node.branches + listOf(emptyList())) }) { Text("＋ 并行分支") }
                    }
                    is ActionNode.Try -> {
                        BranchButton("尝试", node.actions) { children("尝试", node.actions) { draft = node.copy(actions = it) } }
                        BranchButton("发生错误", node.onError) { children("发生错误", node.onError) { draft = node.copy(onError = it) } }
                        BranchButton("收尾", node.finallyActions) { children("收尾", node.finallyActions) { draft = node.copy(finallyActions = it) } }
                    }
                    is ActionNode.CallFlow -> {
                        if (flows.isEmpty()) Text("请先在流程页创建流程。")
                        flows.forEach { flow ->
                            Row {
                                RadioButton(node.flowId == flow.id, { draft = node.copy(flowId = flow.id) })
                                Text(flow.name)
                            }
                        }
                        OutlinedTextField(node.resultVariable.orEmpty(), {
                            draft = node.copy(resultVariable = it.ifBlank { null })
                        }, label = { Text("返回值变量") })
                        ConfigMapEditor(node.input) { draft = node.copy(input = it) }
                    }
                    is ActionNode.Return -> TypedValueEditor(node.value) { draft = node.copy(value = it) }
                    is ActionNode.Action -> Text("此功能当前未安装，原有配置会保留。")
                    is ActionNode.Break -> Text("退出当前循环。")
                    is ActionNode.Continue -> Text("跳到当前循环的下一次迭代。")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(draft) },
                enabled = (draft as? ActionNode.CallFlow)?.flowId?.value?.isNotBlank() != false,
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    child?.let { (label, nodes) ->
        ActionTreeDialog(
            label, nodes, descriptors, flows,
            onDismiss = { child = null },
            onSave = { saveChild?.invoke(it); child = null },
        )
    }
    predicate?.let { value ->
        PredicateDialog(
            value,
            descriptors,
            onDismiss = { predicate = null },
            onSave = { savePredicate?.invoke(it); predicate = null },
        )
    }
}

@Composable
private fun BranchButton(label: String, nodes: List<ActionNode>, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text("$label (${nodes.size})") }
}

@Composable
internal fun TypedValueEditor(value: ConfigValue, onChange: (ConfigValue) -> Unit) {
    val kind = when (value) {
        is ConfigValue.NumberValue -> "数字"
        is ConfigValue.BooleanValue -> "布尔"
        ConfigValue.NullValue -> "空值"
        is ConfigValue.ListValue -> "列表"
        is ConfigValue.ObjectValue -> "对象"
        else -> "文本"
    }
    Row {
        listOf("文本", "数字", "布尔", "空值").forEach { label ->
            TextButton(onClick = {
                onChange(
                    when (label) {
                        "数字" -> ConfigValue.NumberValue(0.0)
                        "布尔" -> ConfigValue.BooleanValue(false)
                        "空值" -> ConfigValue.NullValue
                        else -> ConfigValue.StringValue("")
                    }
                )
            }) { Text(if (label == kind) "[$label]" else label) }
        }
    }
    Row {
        TextButton(onClick = { onChange(ConfigValue.ListValue(emptyList())) }) { Text("列表") }
        TextButton(onClick = { onChange(ConfigValue.ObjectValue(emptyMap())) }) { Text("对象") }
    }
    when (value) {
        is ConfigValue.StringValue -> OutlinedTextField(value.value, { onChange(ConfigValue.StringValue(it)) }, label = { Text("值") })
        is ConfigValue.NumberValue -> {
            var text by remember(value) { mutableStateOf(value.value.toString()) }
            OutlinedTextField(text, { raw ->
                text = raw
                raw.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { onChange(ConfigValue.NumberValue(it)) }
            }, label = { Text("数值") })
        }
        is ConfigValue.BooleanValue -> Switch(value.value, { onChange(ConfigValue.BooleanValue(it)) })
        is ConfigValue.ListValue -> ValueListEditor(value.value) { onChange(ConfigValue.ListValue(it)) }
        is ConfigValue.ObjectValue -> ConfigMapEditor(value.value) { onChange(ConfigValue.ObjectValue(it)) }
        ConfigValue.NullValue -> Text("空值")
    }
}

@Composable
private fun ValueListEditor(values: List<ConfigValue>, onChange: (List<ConfigValue>) -> Unit) {
    values.forEachIndexed { index, value ->
        TypedValueEditor(value) { updated -> onChange(values.toMutableList().apply { this[index] = updated }) }
        TextButton(onClick = { onChange(values.filterIndexed { i, _ -> i != index }) }) { Text("删除第 ${index + 1} 项") }
    }
    TextButton(onClick = { onChange(values + ConfigValue.StringValue("")) }) { Text("＋ 项") }
}

@Composable
internal fun ConfigMapEditor(values: ConfigMap, onChange: (ConfigMap) -> Unit) {
    var name by remember { mutableStateOf("") }
    values.forEach { (key, value) ->
        Text(key)
        TypedValueEditor(value) { onChange(values + (key to it)) }
        TextButton(onClick = { onChange(values - key) }) { Text("删除 $key") }
    }
    OutlinedTextField(name, { name = it }, label = { Text("参数名") })
    TextButton(
        enabled = name.isNotBlank() && name !in values,
        onClick = { onChange(values + (name to ConfigValue.StringValue(""))); name = "" },
    ) { Text("＋ 参数") }
}

@Composable
internal fun PredicateDialog(
    initial: PredicateNode,
    descriptors: List<FeatureDescriptor>,
    onDismiss: () -> Unit,
    onSave: (PredicateNode) -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    var editing by remember { mutableStateOf<Pair<Int, PredicateNode>?>(null) }
    var picker by remember { mutableStateOf(false) }
    var editingFeature by remember { mutableStateOf<FeatureRef?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑条件") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row {
                    TextButton(onClick = { draft = PredicateNode.Expression("") }) { Text("表达式") }
                    TextButton(onClick = { picker = true }) { Text("功能条件") }
                }
                Row {
                    listOf("全部", "任一", "都不").forEach { label ->
                        TextButton(onClick = {
                            val children = when (val node = draft) {
                                is PredicateNode.All -> node.children
                                is PredicateNode.Any -> node.children
                                is PredicateNode.None -> node.children
                                else -> listOf(node)
                            }
                            draft = when (label) {
                                "任一" -> PredicateNode.Any(children)
                                "都不" -> PredicateNode.None(children)
                                else -> PredicateNode.All(children)
                            }
                        }) { Text(label) }
                    }
                }
                when (val node = draft) {
                    is PredicateNode.Expression -> OutlinedTextField(
                        node.expression,
                        { draft = node.copy(expression = it) },
                        label = { Text("布尔表达式") },
                    )
                    is PredicateNode.Literal -> Row {
                        Text("固定值")
                        Switch(node.value, { draft = PredicateNode.Literal(it) })
                    }
                    is PredicateNode.Condition -> TextButton(onClick = { editingFeature = node.feature }) {
                        Text(descriptors.firstOrNull { it.id.value == node.feature.typeId }?.title ?: node.feature.typeId)
                    }
                    else -> {
                        val children = when (node) {
                            is PredicateNode.All -> node.children
                            is PredicateNode.Any -> node.children
                            is PredicateNode.None -> node.children
                            else -> emptyList()
                        }
                        Text(
                            when (node) {
                                is PredicateNode.All -> "全部条件满足"
                                is PredicateNode.Any -> "任一条件满足"
                                else -> "所有条件都不满足"
                            }
                        )
                        fun updated(values: List<PredicateNode>) {
                            draft = when (node) {
                                is PredicateNode.All -> node.copy(children = values)
                                is PredicateNode.Any -> node.copy(children = values)
                                is PredicateNode.None -> node.copy(children = values)
                                else -> node
                            }
                        }
                        children.forEachIndexed { index, value ->
                            Row {
                                TextButton(onClick = { editing = index to value }) { Text("条件 ${index + 1}") }
                                TextButton(onClick = { updated(children.filterIndexed { i, _ -> i != index }) }) { Text("删除") }
                            }
                        }
                        TextButton(onClick = { updated(children + PredicateNode.Literal(true)) }) { Text("＋ 条件") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (picker) MacroFeaturePickerDialog(
        kind = FeatureKind.CONDITION,
        descriptors = descriptors,
        onDismiss = { picker = false },
        onPick = { feature -> draft = PredicateNode.Condition(feature); picker = false },
    )

    editingFeature?.let { feature ->
        MacroFeaturePickerDialog(
            kind = FeatureKind.CONDITION,
            descriptors = descriptors,
            initial = feature,
            onDismiss = { editingFeature = null },
            onPick = { updated -> draft = PredicateNode.Condition(updated); editingFeature = null },
        )
    }

    editing?.let { (index, value) ->
        PredicateDialog(value, descriptors, { editing = null }) { updated ->
            draft = when (val node = draft) {
                is PredicateNode.All -> node.copy(children = node.children.toMutableList().apply { this[index] = updated })
                is PredicateNode.Any -> node.copy(children = node.children.toMutableList().apply { this[index] = updated })
                is PredicateNode.None -> node.copy(children = node.children.toMutableList().apply { this[index] = updated })
                else -> node
            }
            editing = null
        }
    }
}
