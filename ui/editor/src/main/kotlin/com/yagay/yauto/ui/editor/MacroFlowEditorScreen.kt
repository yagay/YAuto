package com.yagay.yauto.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.model.*
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.ui.design.*
import com.yagay.yauto.ui.design.R as TextR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacroFlowEditorScreen(
    initial: Flow?,
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
    var inputEditor by remember { mutableStateOf<Pair<Int?, FlowParameter?>?>(null) }
    var outputEditor by remember { mutableStateOf<Pair<Int?, FlowParameter?>?>(null) }
    var actionMenu by remember { mutableStateOf<Int?>(null) }
    var tree by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (initial == null) TextR.string.flow_add_title else TextR.string.flow_edit_title
                        )
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(com.yagay.yauto.ui.design.R.drawable.ic_back),
                            contentDescription = stringResource(com.yagay.yauto.ui.design.R.string.icon_back),
                        )
                    }
                },
                actions = {
                    TextButton(
                        enabled = name.isNotBlank(),
                        onClick = {
                            onSave(
                                Flow(
                                    id = initial?.id ?: FlowId.random(),
                                    name = name.trim(),
                                    description = description.trim().takeIf { it.isNotEmpty() },
                                    inputs = inputs,
                                    outputs = outputs,
                                    actions = actions,
                                )
                            )
                        }
                    ) { Text(stringResource(TextR.string.common_save)) }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(TextR.string.flow_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(TextR.string.flow_description)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            }
            item {
                MacroSection(
                    stringResource(TextR.string.flow_inputs),
                    MacroPalette.Variable,
                    count = inputs.size,
                    subtitle = stringResource(TextR.string.flow_inputs_subtitle),
                    onAdd = { inputEditor = null to null },
                ) {
                    if (inputs.isEmpty()) FlowEmpty(stringResource(TextR.string.flow_no_inputs))
                    inputs.forEachIndexed { index, parameter ->
                        FlowParameterRow(parameter, onClick = { inputEditor = index to parameter })
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
                                val label = descriptor?.let { owner ->
                                    owner.fields.firstOrNull { it.key == entry.key }?.let { field ->
                                        localizedFieldLabelShared(owner.id.value, field)
                                    }
                                } ?: entry.key
                                stringResource(TextR.string.flow_config_entry_format, label, flowValueText(entry.value))
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
                    onAdd = { outputEditor = null to null },
                ) {
                    if (outputs.isEmpty()) FlowEmpty(stringResource(TextR.string.flow_no_outputs))
                    outputs.forEachIndexed { index, parameter ->
                        FlowParameterRow(parameter, onClick = { outputEditor = index to parameter })
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
                actions = if (index == null) {
                    actions + ActionNode.Action(selected)
                } else {
                    actions.toMutableList().also { it[index] = ActionNode.Action(selected) }
                }
                picker = null
            },
        )
    }

    inputEditor?.let { (index, parameter) ->
        FlowParameterDialog(
            title = stringResource(
                if (parameter == null) TextR.string.flow_input_parameter else TextR.string.flow_input_parameter
            ),
            initial = parameter,
            onDismiss = { inputEditor = null },
            onDelete = if (index != null) {
                {
                    inputs = inputs.toMutableList().also { it.removeAt(index) }
                    inputEditor = null
                }
            } else null,
            onSave = { updated ->
                inputs = if (index == null) inputs + updated
                else inputs.toMutableList().also { it[index] = updated }
                inputEditor = null
            },
        )
    }

    outputEditor?.let { (index, parameter) ->
        FlowParameterDialog(
            title = stringResource(TextR.string.flow_output_parameter),
            initial = parameter,
            onDismiss = { outputEditor = null },
            onDelete = if (index != null) {
                {
                    outputs = outputs.toMutableList().also { it.removeAt(index) }
                    outputEditor = null
                }
            } else null,
            onSave = { updated ->
                outputs = if (index == null) outputs + updated
                else outputs.toMutableList().also { it[index] = updated }
                outputEditor = null
            },
        )
    }

    actionMenu?.let { index ->
        val node = actions.getOrNull(index)
        AlertDialog(
            onDismissRequest = { actionMenu = null },
            title = { Text(stringResource(TextR.string.flow_action_menu_title)) },
            text = {
                Column {
                    if (index > 0) {
                        TextButton(onClick = {
                            actions = actions.toMutableList().also {
                                val item = it.removeAt(index)
                                it.add(index - 1, item)
                            }
                            actionMenu = null
                        }) { Text(stringResource(TextR.string.flow_move_up)) }
                    }
                    if (index < actions.lastIndex) {
                        TextButton(onClick = {
                            actions = actions.toMutableList().also {
                                val item = it.removeAt(index)
                                it.add(index + 1, item)
                            }
                            actionMenu = null
                        }) { Text(stringResource(TextR.string.flow_move_down)) }
                    }
                    TextButton(onClick = {
                        actions = actions.toMutableList().also { it.removeAt(index) }
                        actionMenu = null
                    }) { Text(stringResource(TextR.string.common_delete)) }
                }
            },
            confirmButton = {},
        )
    }

    if (tree) {
        ActionTreeEditorDialog(
            title = stringResource(TextR.string.flow_action_tree_title),
            nodes = actions,
            descriptors = descriptors,
            flows = flows,
            onDismiss = { tree = false },
            onSave = {
                actions = it
                tree = false
            },
        )
    }
}

@Composable
private fun FlowParameterRow(parameter: FlowParameter, onClick: () -> Unit) {
    val suffix = buildList {
        if (parameter.required) add(stringResource(TextR.string.flow_parameter_required_suffix))
        parameter.defaultValue?.let {
            add(stringResource(TextR.string.flow_default_suffix_format, flowValueText(it)))
        }
    }.joinToString("")
    ListItem(
        headlineContent = { Text(parameter.name) },
        supportingContent = { Text(flowValueTypeLabel(parameter.type) + suffix) },
        modifier = Modifier.fillMaxWidth(),
    )
    TextButton(onClick = onClick) { Text(stringResource(TextR.string.common_edit)) }
}

@Composable
private fun FlowParameterDialog(
    title: String,
    initial: FlowParameter?,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (FlowParameter) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var type by remember(initial) { mutableStateOf(initial?.type ?: ValueType.STRING) }
    var required by remember(initial) { mutableStateOf(initial?.required ?: false) }
    var defaultText by remember(initial) { mutableStateOf(initial?.defaultValue.editorText()) }
    val types = ValueType.entries

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(TextR.string.flow_parameter_name)) },
                    singleLine = true,
                )
                Text(stringResource(TextR.string.flow_parameter_type_format, flowValueTypeLabel(type)))
                types.forEach { option ->
                    Row {
                        RadioButton(selected = type == option, onClick = { type = option })
                        TextButton(onClick = { type = option }) { Text(flowValueTypeLabel(option)) }
                    }
                }
                Row {
                    Checkbox(checked = required, onCheckedChange = { required = it })
                    TextButton(onClick = { required = !required }) {
                        Text(stringResource(TextR.string.flow_parameter_required))
                    }
                }
                OutlinedTextField(
                    value = defaultText,
                    onValueChange = { defaultText = it },
                    label = { Text(stringResource(TextR.string.flow_parameter_default)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        FlowParameter(
                            name = name.trim(),
                            type = type,
                            required = required,
                            defaultValue = defaultText.takeIf { it.isNotBlank() }?.let { ConfigValue.StringValue(it) },
                        )
                    )
                }
            ) { Text(stringResource(TextR.string.common_confirm)) }
        },
        dismissButton = {
            Row {
                onDelete?.let { delete ->
                    TextButton(onClick = delete) { Text(stringResource(TextR.string.common_delete)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(TextR.string.common_cancel)) }
            }
        },
    )
}

@Composable
private fun flowValueTypeLabel(type: ValueType): String = stringResource(
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
    is ActionNode.TryCatch -> stringResource(TextR.string.node_try)
    is ActionNode.CallFlow -> stringResource(
        TextR.string.node_call_flow_format,
        flows.firstOrNull { it.id == node.flowId }?.name ?: node.flowId.value,
    )
    is ActionNode.Return -> stringResource(TextR.string.node_return)
    ActionNode.Break -> stringResource(TextR.string.node_break)
    ActionNode.Continue -> stringResource(TextR.string.node_continue)
}

private fun ConfigValue?.editorText(): String = when (this) {
    null, ConfigValue.NullValue -> ""
    is ConfigValue.StringValue -> value
    is ConfigValue.NumberValue -> value.toString()
    is ConfigValue.BooleanValue -> value.toString()
    is ConfigValue.ListValue -> value.joinToString(",") { it.editorText() }
    is ConfigValue.ObjectValue -> value.entries.joinToString(",") { "${it.key}=${it.value.editorText()}" }
}

@Composable
private fun flowValueText(value: ConfigValue): String = when (value) {
    is ConfigValue.StringValue -> value.value
    is ConfigValue.NumberValue -> value.value.toString()
    is ConfigValue.BooleanValue -> stringResource(
        if (value.value) TextR.string.runtime_value_true else TextR.string.runtime_value_false
    )
    ConfigValue.NullValue -> stringResource(TextR.string.value_null)
    is ConfigValue.ListValue -> "[${value.value.joinToString(", ") { it.editorText() }}]"
    is ConfigValue.ObjectValue -> "{${value.value.entries.joinToString(", ") { "${it.key}=${it.value.editorText()}" }}}"
}

@Composable
private fun FlowEmpty(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
