package com.yagay.yauto.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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

private enum class FlowParamSide { INPUT, OUTPUT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacroFlowEditorScreen(
    initial: Flow? = null,
    descriptors: List<FeatureDescriptor>,
    flows: List<Flow>,
    onSave: (Flow) -> Unit,
    onBack: () -> Unit,
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var description by remember(initial?.id) { mutableStateOf(initial?.description.orEmpty()) }
    var inputs by remember(initial?.id) { mutableStateOf(initial?.inputs.orEmpty()) }
    var outputs by remember(initial?.id) { mutableStateOf(initial?.outputs.orEmpty()) }
    var actions by remember(initial?.id) { mutableStateOf(initial?.actions.orEmpty()) }
    var picker by remember { mutableStateOf<Pair<Int?, FeatureRef?>?>(null) }
    var tree by remember { mutableStateOf(false) }
    var paramEdit by remember { mutableStateOf<Triple<FlowParamSide, Int?, FlowParameter?>?>(null) }
    var actionMenu by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial == null) "添加流程 / 动作块" else "编辑流程 / 动作块") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹") } },
                actions = {
                    TextButton(enabled = name.isNotBlank(), onClick = {
                        onSave(
                            Flow(
                                id = initial?.id ?: FlowId(UUID.randomUUID().toString()),
                                name = name.trim(),
                                inputs = inputs,
                                outputs = outputs,
                                actions = actions,
                                description = description.trim().ifBlank { null },
                                source = initial?.source,
                            )
                        )
                    }) { Text("保存") }
                },
            )
        }
    ) { padding ->
        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("流程名称") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text("说明") }, minLines = 2)
            }
            item {
                MacroSection("输入参数", MacroPalette.Flow, count = inputs.size, subtitle = "调用流程时传入，替代 %par1 / %par2", onAdd = {
                    paramEdit = Triple(FlowParamSide.INPUT, null, null)
                }) {
                    if (inputs.isEmpty()) FlowEmpty("没有输入参数")
                    inputs.forEachIndexed { index, parameter ->
                        MacroItemRow(
                            title = parameter.name,
                            subtitle = "${parameter.type}${if (parameter.required) " · 必填" else ""}${defaultLabel(parameter)}",
                            accent = MacroPalette.Flow,
                            onClick = { paramEdit = Triple(FlowParamSide.INPUT, index, parameter) },
                            onMenu = { inputs = inputs.filterIndexed { i, _ -> i != index } },
                        )
                    }
                }
            }
            item {
                MacroSection(
                    "动作",
                    MacroPalette.Action,
                    count = actions.size,
                    subtitle = "与自动化共用同一功能目录",
                    onAdd = { picker = null to null },
                    trailing = { TextButton(onClick = { tree = true }) { Text("结构", color = androidx.compose.ui.graphics.Color.White) } },
                ) {
                    if (actions.isEmpty()) FlowEmpty("点击 ＋ 添加动作")
                    actions.forEachIndexed { index, node ->
                        val feature = (node as? ActionNode.Action)?.feature
                        MacroItemRow(
                            title = if (feature != null) descriptors.firstOrNull { it.id.value == feature.typeId }?.title ?: feature.typeId else flowNodeTitle(node, flows),
                            subtitle = feature?.config?.entries?.filterNot { it.key.startsWith("source.") }?.take(3)?.joinToString(" · ") { "${it.key}=${flowValueText(it.value)}" },
                            accent = MacroPalette.Action,
                            onClick = {
                                if (feature != null) picker = index to feature else tree = true
                            },
                            onMenu = { actionMenu = index },
                        )
                    }
                }
            }
            item {
                MacroSection("输出参数", MacroPalette.Constraint, count = outputs.size, subtitle = "调用者可按名称读取返回数据", onAdd = {
                    paramEdit = Triple(FlowParamSide.OUTPUT, null, null)
                }) {
                    if (outputs.isEmpty()) FlowEmpty("没有输出参数")
                    outputs.forEachIndexed { index, parameter ->
                        MacroItemRow(
                            title = parameter.name,
                            subtitle = parameter.type.name,
                            accent = MacroPalette.Constraint,
                            onClick = { paramEdit = Triple(FlowParamSide.OUTPUT, index, parameter) },
                            onMenu = { outputs = outputs.filterIndexed { i, _ -> i != index } },
                        )
                    }
                }
            }
        }
    }

    picker?.let { (index, feature) ->
        MacroFeaturePickerDialog(
            kind = FeatureKind.ACTION,
            descriptors = descriptors,
            initial = feature,
            onDismiss = { picker = null },
            onPick = { selected ->
                actions = if (index == null || index !in actions.indices) {
                    actions + ActionNode.Action(NodeId(UUID.randomUUID().toString()), selected)
                } else actions.toMutableList().apply {
                    val old = this[index] as? ActionNode.Action
                    this[index] = old?.copy(feature = selected) ?: ActionNode.Action(NodeId(UUID.randomUUID().toString()), selected)
                }
                picker = null
            },
        )
    }

    if (tree) {
        ActionTreeDialog(
            title = "流程动作结构",
            initial = actions,
            descriptors = descriptors,
            flows = flows,
            onDismiss = { tree = false },
            onSave = { actions = it; tree = false },
        )
    }

    paramEdit?.let { (side, index, initialParam) ->
        FlowParameterDialog(
            title = if (side == FlowParamSide.INPUT) "输入参数" else "输出参数",
            initial = initialParam,
            allowDefault = side == FlowParamSide.INPUT,
            onDismiss = { paramEdit = null },
            onSave = { parameter ->
                if (side == FlowParamSide.INPUT) inputs = inputs.upsertParameter(index, parameter)
                else outputs = outputs.upsertParameter(index, parameter)
                paramEdit = null
            },
        )
    }

    actionMenu?.let { index ->
        AlertDialog(
            onDismissRequest = { actionMenu = null },
            title = { Text("动作操作") },
            text = {
                Column {
                    TextButton(enabled = index > 0, onClick = { actions = actions.moveFlowItem(index, index - 1); actionMenu = null }) { Text("上移") }
                    TextButton(enabled = index < actions.lastIndex, onClick = { actions = actions.moveFlowItem(index, index + 1); actionMenu = null }) { Text("下移") }
                    TextButton(onClick = { actions = actions.filterIndexed { i, _ -> i != index }; actionMenu = null }) { Text("删除") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { actionMenu = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun FlowParameterDialog(
    title: String,
    initial: FlowParameter?,
    allowDefault: Boolean,
    onDismiss: () -> Unit,
    onSave: (FlowParameter) -> Unit,
) {
    var name by remember(initial?.name) { mutableStateOf(initial?.name.orEmpty()) }
    var type by remember(initial?.name) { mutableStateOf(initial?.type ?: ValueType.STRING) }
    var required by remember(initial?.name) { mutableStateOf(initial?.required ?: false) }
    var default by remember(initial?.name) { mutableStateOf(initial?.defaultValue?.let(::flowValueText).orEmpty()) }
    var typeMenu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("名称") }, singleLine = true)
                Box {
                    OutlinedButton(onClick = { typeMenu = true }, modifier = Modifier.fillMaxWidth()) { Text("类型：${type.name}") }
                    DropdownMenu(typeMenu, { typeMenu = false }) {
                        ValueType.entries.forEach { item -> DropdownMenuItem({ Text(item.name) }, onClick = { type = item; typeMenu = false }) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("必填", Modifier.weight(1f)); Switch(required, { required = it })
                }
                if (allowDefault) OutlinedTextField(default, { default = it }, Modifier.fillMaxWidth(), label = { Text("默认值") })
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onSave(FlowParameter(name.trim(), type, required, if (allowDefault && default.isNotEmpty()) ConfigValue.StringValue(default) else ConfigValue.NullValue))
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun FlowEmpty(text: String) {
    Box(Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun List<FlowParameter>.upsertParameter(index: Int?, value: FlowParameter): List<FlowParameter> =
    if (index == null || index !in indices) this + value else toMutableList().apply { this[index] = value }

private fun <T> List<T>.moveFlowItem(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices || from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

private fun defaultLabel(parameter: FlowParameter): String = when (parameter.defaultValue) {
    ConfigValue.NullValue -> ""
    else -> " · 默认 ${flowValueText(parameter.defaultValue)}"
}

private fun flowNodeTitle(node: ActionNode, flows: List<Flow>): String = when (node) {
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

private fun flowValueText(value: ConfigValue): String = when (value) {
    ConfigValue.NullValue -> ""
    is ConfigValue.StringValue -> value.value
    is ConfigValue.NumberValue -> if (value.value % 1.0 == 0.0) value.value.toLong().toString() else value.value.toString()
    is ConfigValue.BooleanValue -> value.value.toString()
    is ConfigValue.ListValue -> value.value.joinToString(",") { flowValueText(it) }
    is ConfigValue.ObjectValue -> value.value.entries.joinToString(",") { "${it.key}=${flowValueText(it.value)}" }
}
