package com.yagay.yauto.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.model.*
import com.yagay.yauto.core.registry.*
import com.yagay.yauto.ui.design.*
import com.yagay.yauto.ui.design.R as TextR
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
                title = {
                    Text(stringResource(if (initial == null) TextR.string.flow_add_title else TextR.string.flow_edit_title))
                },
                navigationIcon = { TextButton(onClick = onBack) { androidx.compose.material3.Icon(painter = androidx.compose.ui.res.painterResource(com.yagay.yauto.ui.design.R.drawable.ic_back), contentDescription = androidx.compose.ui.res.stringResource(com.yagay.yauto.ui.design.R.string.icon_back)) } },
                actions = {
                    TextButton(
                        enabled = name.isNotBlank(),
                        onClick = {
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
                        },
                    ) { Text(stringResource(TextR.string.common_save)) }
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
                OutlinedTextField(
                    name,
                    { name = it },
                    Modifier.fillMaxWidth(),
                    label = { Text(stringResource(TextR.string.flow_name)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    description,
                    { description = it },
                    Modifier.fillMaxWidth(),
                    label = { Text(stringResource(TextR.string.flow_description)) },
                    minLines = 2,
                )
            }
            item {
                MacroSection(
                    stringResource(TextR.string.flow_inputs),
                    MacroPalette.Flow,
                    count = inputs.size,
                    subtitle = stringResource(TextR.string.flow_inputs_subtitle),
                    onAdd = { paramEdit = Triple(FlowParamSide.INPUT, null, null) },
                ) {
                    if (inputs.isEmpty()) FlowEmpty(stringResource(TextR.string.flow_no_inputs))
                    inputs.forEachIndexed { index, parameter ->
                        MacroItemRow(
                            title = parameter.name,
                            subtitle = flowParameterSummary(parameter),
                            accent = MacroPalette.Flow,
                            onClick = { paramEdit = Triple(FlowParamSide.INPUT, index, parameter) },
                            onMenu = { inputs = inputs.filterIndexed { i, _ -> i != index } },
                        )
                    }
                }
            }
            item {
                MacroSection(
                    stringResource(TextR.string.flow_actions),
                    MacroPalette.Action,
                    count = actions.size,
                    subtitle = stringResource(TextR.string.flow_actions_subtitle),
                    onAdd = { picker = null to null },
                    trailing = {
                        TextButton(onClick = { tree = true }) {
                            Text(stringResource(TextR.string.flow_structure), color = androidx.compose.ui.graphics.Color.White)
                        }
                    },
                ) {
                    if (actions.isEmpty()) FlowEmpty(stringResource(TextR.string.flow_add_action_hint))
                    actions.forEachIndexed { index, node ->
                        val feature = (node as? ActionNode.Action)?.feature
                        val descriptor = feature?.let { ref ->
                            descriptors.firstOrNull { it.id.value == ref.typeId }
                        }
                        val summary = feature?.config?.entries
                            ?.filterNot { it.key.startsWith("source.") }
                            ?.take(3)
                            ?.map { entry ->
                                val field = descriptor?.fields?.firstOrNull { it.key == entry.key }
                                val label = if (descriptor != null && field != null) {
                                    localizedFieldLabelShared(descriptor.id.value, field)
                                } else {
                                    entry.key
                                }
                                val value = if (
                                    descriptor != null && field is FieldSchema.Choice && entry.value is ConfigValue.StringValue
                                ) {
                                    localizedChoiceOptionShared(descriptor.id.value, field.key, entry.value.value)
                                } else {
                                    localizedConfigValue(entry.value)
                                }
                                stringResource(TextR.string.flow_config_entry_format, label, value)
                            }
                            ?.let { localizedList(it) }
                        MacroItemRow(
                            title = descriptor?.let { localizedFeatureTitle(it) }
                                ?: feature?.typeId
                                ?: flowNodeTitle(node, flows),
                            subtitle = summary,
                            accent = MacroPalette.Action,
                            onClick = { if (feature != null) picker = index to feature else tree = true },
                            onMenu = { actionMenu = index },
                        )
                    }
                }
            }
            item {
                MacroSection(
                    stringResource(TextR.string.flow_outputs),
                    MacroPalette.Constraint,
                    count = outputs.size,
                    subtitle = stringResource(TextR.string.flow_outputs_subtitle),
                    onAdd = { paramEdit = Triple(FlowParamSide.OUTPUT, null, null) },
                ) {
                    if (outputs.isEmpty()) FlowEmpty(stringResource(TextR.string.flow_no_outputs))
                    outputs.forEachIndexed { index, parameter ->
                        MacroItemRow(
                            title = parameter.name,
                            subtitle = valueTypeLabel(parameter.type),
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
                    this[index] = old?.copy(feature = selected)
                        ?: ActionNode.Action(NodeId(UUID.randomUUID().toString()), selected)
                }
                picker = null
            },
        )
    }

    if (tree) {
        ActionTreeDialog(
            title = stringResource(TextR.string.flow_action_tree_title),
            initial = actions,
            descriptors = descriptors,
            flows = flows,
            onDismiss = { tree = false },
            onSave = {
                actions = it
                tree = false
            },
        )
    }

    paramEdit?.let { (side, index, initialParam) ->
        FlowParameterDialog(
            title = stringResource(
                if (side == FlowParamSide.INPUT) TextR.string.flow_input_parameter else TextR.string.flow_output_parameter
            ),
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
            title = { Text(stringResource(TextR.string.flow_action_menu_title)) },
            text = {
                Column {
                    TextButton(
                        enabled = index > 0,
                        onClick = {
                            actions = actions.moveFlowItem(index, index - 1)
                            actionMenu = null
                        },
                    ) { Text(stringResource(TextR.string.flow_move_up)) }
                    TextButton(
                        enabled = index < actions.lastIndex,
                        onClick = {
                            actions = actions.moveFlowItem(index, index + 1)
                            actionMenu = null
                        },
                    ) { Text(stringResource(TextR.string.flow_move_down)) }
                    TextButton(
                        onClick = {
                            actions = actions.filterIndexed { i, _ -> i != index }
                            actionMenu = null
                        },
                    ) { Text(stringResource(TextR.string.common_delete)) }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { actionMenu = null }) { Text(stringResource(TextR.string.common_cancel)) }
            },
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
    val locale = currentEditorLocale()
    var name by remember(initial?.name) { mutableStateOf(initial?.name.orEmpty()) }
    var type by remember(initial?.name) { mutableStateOf(initial?.type ?: ValueType.STRING) }
    var required by remember(initial?.name) { mutableStateOf(initial?.required ?: false) }
    var default by remember(initial?.name, locale) {
        mutableStateOf(editorConfigValueText(initial?.defaultValue, locale))
    }
    var typeMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    name,
                    { name = it },
                    Modifier.fillMaxWidth(),
                    label = { Text(stringResource(TextR.string.flow_parameter_name)) },
                    singleLine = true,
                )
                Box {
                    OutlinedButton(onClick = { typeMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(TextR.string.flow_parameter_type_format, valueTypeLabel(type)))
                    }
                    DropdownMenu(typeMenu, { typeMenu = false }) {
                        ValueType.entries.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(valueTypeLabel(item)) },
                                onClick = {
                                    type = item
                                    typeMenu = false
                                },
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(TextR.string.flow_parameter_required), Modifier.weight(1f))
                    Switch(required, { required = it })
                }
                if (allowDefault) {
                    OutlinedTextField(
                        default,
                        { default = it },
                        Modifier.fillMaxWidth(),
                        label = { Text(stringResource(TextR.string.flow_parameter_default)) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        FlowParameter(
                            name.trim(),
                            type,
                            required,
                            if (allowDefault && default.isNotEmpty()) {
                                when (type) {
                                    ValueType.NUMBER, ValueType.DURATION -> parseLocalizedDouble(default, locale)
                                        ?.let(ConfigValue::NumberValue)
                                        ?: ConfigValue.StringValue(default)
                                    ValueType.BOOLEAN -> when (default.trim().lowercase()) {
                                        "true" -> ConfigValue.BooleanValue(true)
                                        "false" -> ConfigValue.BooleanValue(false)
                                        else -> ConfigValue.StringValue(default)
                                    }
                                    else -> ConfigValue.StringValue(default)
                                }
                            } else {
                                ConfigValue.NullValue
                            },
                        )
                    )
                },
            ) { Text(stringResource(TextR.string.common_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(TextR.string.common_cancel)) }
        },
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

@Composable
private fun flowParameterSummary(parameter: FlowParameter): String {
    val parts = buildList {
        add(valueTypeLabel(parameter.type))
        if (parameter.required) add(stringResource(TextR.string.flow_parameter_required))
        if (parameter.defaultValue != ConfigValue.NullValue) {
            add(stringResource(TextR.string.flow_default_value_format, localizedConfigValue(parameter.defaultValue)))
        }
    }
    return localizedList(parts)
}

@Composable
private fun valueTypeLabel(type: ValueType): String = stringResource(
    when (type) {
        ValueType.STRING -> TextR.string.flow_value_type_string
        ValueType.NUMBER -> TextR.string.flow_value_type_number
        ValueType.BOOLEAN -> TextR.string.flow_value_type_boolean
        ValueType.LIST -> TextR.string.flow_value_type_list
        ValueType.OBJECT -> TextR.string.flow_value_type_object
        ValueType.APP -> TextR.string.flow_value_type_app
        ValueType.PACKAGE -> TextR.string.flow_value_type_package
        ValueType.COMPONENT -> TextR.string.flow_value_type_component
        ValueType.URI -> TextR.string.flow_value_type_uri
        ValueType.FILE -> TextR.string.flow_value_type_file
        ValueType.DATE_TIME -> TextR.string.flow_value_type_date_time
        ValueType.DURATION -> TextR.string.flow_value_type_duration
        ValueType.COLOR -> TextR.string.flow_value_type_color
        ValueType.LOCATION -> TextR.string.flow_value_type_location
        ValueType.ANY -> TextR.string.flow_value_type_any
    }
)

@Composable
private fun flowNodeTitle(node: ActionNode, flows: List<Flow>): String = when (node) {
    is ActionNode.Action -> node.feature.typeId
    is ActionNode.If -> stringResource(TextR.string.node_if)
    is ActionNode.Switch -> stringResource(TextR.string.node_switch)
    is ActionNode.Repeat -> stringResource(TextR.string.node_repeat_format, node.times)
    is ActionNode.While -> stringResource(TextR.string.node_while)
    is ActionNode.ForEach -> stringResource(TextR.string.node_foreach)
    is ActionNode.Parallel -> stringResource(TextR.string.node_parallel)
    is ActionNode.Try -> stringResource(TextR.string.node_try)
    is ActionNode.CallFlow -> stringResource(
        TextR.string.node_call_flow_format,
        flows.firstOrNull { it.id == node.flowId }?.name ?: node.flowId.value,
    )
    is ActionNode.Return -> stringResource(TextR.string.node_return)
    is ActionNode.Break -> stringResource(TextR.string.node_break)
    is ActionNode.Continue -> stringResource(TextR.string.node_continue)
}
