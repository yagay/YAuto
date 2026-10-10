package com.yagay.yauto.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.model.*
import com.yagay.yauto.core.registry.*
import com.yagay.yauto.ui.design.R as TextR
import com.yagay.yauto.ui.design.NavigationDialog
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
    val descriptorById = remember(descriptors) { descriptors.associateBy { it.id.value } }

    fun navigateBack() {
        when {
            editing != null -> editing = null
            picker -> picker = false
            adding -> adding = false
            else -> onDismiss()
        }
    }

    NavigationDialog(onBack = ::navigateBack) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        TextButton(onClick = ::navigateBack) { Text(stringResource(TextR.string.common_cancel)) }
                    },
                    actions = {
                        TextButton(onClick = { onSave(nodes) }) { Text(stringResource(TextR.string.common_save)) }
                    },
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
                                Text(nodeLabel(node, descriptorById, flows))
                                Row {
                                    TextButton(onClick = { editing = node }) {
                                        Text(stringResource(TextR.string.common_edit))
                                    }
                                    TextButton(
                                        enabled = index > 0,
                                        onClick = {
                                            nodes = nodes.toMutableList().apply { add(index - 1, removeAt(index)) }
                                        },
                                    ) { androidx.compose.material3.Icon(painter = androidx.compose.ui.res.painterResource(com.yagay.yauto.ui.design.R.drawable.ic_arrow_up), contentDescription = androidx.compose.ui.res.stringResource(com.yagay.yauto.ui.design.R.string.icon_move_up)) }
                                    TextButton(
                                        enabled = index < nodes.lastIndex,
                                        onClick = {
                                            nodes = nodes.toMutableList().apply { add(index + 1, removeAt(index)) }
                                        },
                                    ) { androidx.compose.material3.Icon(painter = androidx.compose.ui.res.painterResource(com.yagay.yauto.ui.design.R.drawable.ic_arrow_down), contentDescription = androidx.compose.ui.res.stringResource(com.yagay.yauto.ui.design.R.string.icon_move_down)) }
                                    TextButton(onClick = { nodes = nodes.filterNot { it.id == node.id } }) {
                                        Text(stringResource(TextR.string.common_delete))
                                    }
                                }
                            }
                        }
                    }
                }
                Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(TextR.string.tree_add))
                }
            }
        }
    }

    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(TextR.string.tree_add_action_or_structure)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TextButton(onClick = {
                        adding = false
                        picker = true
                    }) { Text(stringResource(TextR.string.tree_normal_action)) }

                    val templates = listOf<Pair<String, () -> ActionNode>>(
                        stringResource(TextR.string.tree_if) to {
                            ActionNode.If(nodeId(), PredicateNode.Literal(true), emptyList())
                        },
                        stringResource(TextR.string.tree_switch) to {
                            ActionNode.Switch(nodeId(), "", emptyList())
                        },
                        stringResource(TextR.string.tree_repeat) to {
                            ActionNode.Repeat(nodeId(), 1, emptyList())
                        },
                        stringResource(TextR.string.tree_while) to {
                            ActionNode.While(nodeId(), PredicateNode.Literal(false), emptyList())
                        },
                        stringResource(TextR.string.tree_while) + " (do)" to {
                            ActionNode.DoWhile(nodeId(), PredicateNode.Literal(false), emptyList())
                        },
                        stringResource(TextR.string.tree_wait_until) to {
                            ActionNode.WaitUntil(nodeId(), PredicateNode.Literal(false))
                        },
                        stringResource(TextR.string.tree_wait_until) + " (event)" to {
                            ActionNode.WaitEvent(nodeId(), emptyList())
                        },
                        stringResource(TextR.string.tree_foreach) to {
                            ActionNode.ForEach(nodeId(), emptyList(), "item", emptyList())
                        },
                        stringResource(TextR.string.tree_parallel) to {
                            ActionNode.Parallel(nodeId(), listOf(emptyList(), emptyList()))
                        },
                        stringResource(TextR.string.tree_try) to {
                            ActionNode.Try(nodeId(), emptyList())
                        },
                        stringResource(TextR.string.tree_call_flow) to {
                            ActionNode.CallFlow(nodeId(), flows.firstOrNull()?.id ?: FlowId(""))
                        },
                        stringResource(TextR.string.tree_return_value) to { ActionNode.Return(nodeId()) },
                        stringResource(TextR.string.tree_break) to { ActionNode.Break(nodeId()) },
                        stringResource(TextR.string.tree_continue) to { ActionNode.Continue(nodeId()) },
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
            dismissButton = {
                TextButton(onClick = { adding = false }) { Text(stringResource(TextR.string.common_cancel)) }
            },
        )
    }

    if (picker) {
        MacroFeaturePickerDialog(
            kind = FeatureKind.ACTION,
            descriptors = descriptors,
            onDismiss = { picker = false },
            onPick = { feature ->
                nodes = nodes + ActionNode.Action(nodeId(), feature)
                picker = false
            },
        )
    }

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

@Composable
private fun nodeLabel(
    node: ActionNode,
    descriptors: Map<String, FeatureDescriptor>,
    flows: List<Flow>,
): String = when (node) {
    is ActionNode.Action -> descriptors[node.feature.typeId]
        ?.let { localizedFeatureTitle(it) } ?: node.feature.typeId
    is ActionNode.If -> stringResource(TextR.string.tree_if)
    is ActionNode.Switch -> stringResource(TextR.string.tree_switch_count_format, node.cases.size)
    is ActionNode.Repeat -> stringResource(TextR.string.node_repeat_format, node.times)
    is ActionNode.While -> stringResource(TextR.string.tree_while)
    is ActionNode.DoWhile -> stringResource(TextR.string.tree_while) + " (do)"
    is ActionNode.WaitUntil -> stringResource(TextR.string.tree_wait_until)
    is ActionNode.WaitEvent -> stringResource(TextR.string.tree_wait_until) + " (event)"
    is ActionNode.ForEach -> stringResource(TextR.string.tree_foreach_variable_format, node.variableName)
    is ActionNode.Parallel -> stringResource(TextR.string.tree_parallel_count_format, node.branches.size)
    is ActionNode.Try -> stringResource(TextR.string.tree_try)
    is ActionNode.CallFlow -> stringResource(
        TextR.string.tree_call_format,
        flows.firstOrNull { it.id == node.flowId }?.name ?: node.flowId.value,
    )
    is ActionNode.Label -> stringResource(TextR.string.tree_label_format, node.name)
    is ActionNode.Goto -> stringResource(TextR.string.tree_goto_format, node.label)
    is ActionNode.Return -> stringResource(TextR.string.tree_return_value)
    is ActionNode.Break -> stringResource(TextR.string.tree_break)
    is ActionNode.Continue -> stringResource(TextR.string.tree_continue)
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
    val descriptorById = remember(descriptors) { descriptors.associateBy { it.id.value } }

    fun children(label: String, actions: List<ActionNode>, update: (List<ActionNode>) -> Unit) {
        child = label to actions
        saveChild = update
    }

    fun condition(value: PredicateNode, update: (PredicateNode) -> Unit) {
        predicate = value
        savePredicate = update
    }

    val action = draft as? ActionNode.Action
    if (action != null && descriptorById.containsKey(action.feature.typeId)) {
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
        title = { Text(nodeLabel(draft, descriptorById, flows)) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (val node = draft) {
                    is ActionNode.If -> {
                        TextButton(onClick = {
                            condition(node.condition) { draft = node.copy(condition = it) }
                        }) { Text(stringResource(TextR.string.tree_edit_condition)) }
                        val trueLabel = stringResource(TextR.string.tree_if_true)
                        val falseLabel = stringResource(TextR.string.tree_if_false)
                        BranchButton(trueLabel, node.thenActions) {
                            children(trueLabel, node.thenActions) { draft = node.copy(thenActions = it) }
                        }
                        BranchButton(falseLabel, node.elseActions) {
                            children(falseLabel, node.elseActions) { draft = node.copy(elseActions = it) }
                        }
                    }
                    is ActionNode.Switch -> {
                        OutlinedTextField(
                            node.expression,
                            { draft = node.copy(expression = it) },
                            label = { Text(stringResource(TextR.string.tree_expression_variable)) },
                        )
                        node.cases.forEachIndexed { index, case ->
                            OutlinedTextField(
                                case.match,
                                { text ->
                                    draft = node.copy(
                                        cases = node.cases.toMutableList().apply {
                                            this[index] = case.copy(match = text)
                                        }
                                    )
                                },
                                label = { Text(stringResource(TextR.string.tree_match_value_format, index + 1)) },
                            )
                            val branchLabel = stringResource(TextR.string.tree_branch_format, index + 1)
                            BranchButton(branchLabel, case.actions) {
                                children(branchLabel, case.actions) { actions ->
                                    draft = node.copy(
                                        cases = node.cases.toMutableList().apply {
                                            this[index] = case.copy(actions = actions)
                                        }
                                    )
                                }
                            }
                            TextButton(onClick = {
                                draft = node.copy(cases = node.cases.filterIndexed { i, _ -> i != index })
                            }) { Text(stringResource(TextR.string.tree_delete_branch)) }
                        }
                        TextButton(onClick = {
                            draft = node.copy(cases = node.cases + SwitchCase("", emptyList()))
                        }) { Text(stringResource(TextR.string.tree_add_branch)) }
                        val defaultLabel = stringResource(TextR.string.tree_default_branch)
                        BranchButton(defaultLabel, node.defaultActions) {
                            children(defaultLabel, node.defaultActions) {
                                draft = node.copy(defaultActions = it)
                            }
                        }
                    }
                    is ActionNode.Repeat -> {
                        OutlinedTextField(
                            node.times.toString(),
                            { text ->
                                text.toIntOrNull()?.takeIf { it >= 0 }?.let {
                                    draft = node.copy(times = it)
                                }
                            },
                            label = { Text(stringResource(TextR.string.tree_repeat_count)) },
                        )
                        val loopLabel = stringResource(TextR.string.tree_loop_actions)
                        BranchButton(loopLabel, node.actions) {
                            children(loopLabel, node.actions) { draft = node.copy(actions = it) }
                        }
                    }
                    is ActionNode.While -> {
                        TextButton(onClick = {
                            condition(node.condition) { draft = node.copy(condition = it) }
                        }) { Text(stringResource(TextR.string.tree_edit_loop_condition)) }
                        val loopLabel = stringResource(TextR.string.tree_loop_actions)
                        BranchButton(loopLabel, node.actions) {
                            children(loopLabel, node.actions) { draft = node.copy(actions = it) }
                        }
                    }
                    is ActionNode.DoWhile -> {
                        TextButton(onClick = {
                            condition(node.condition) { draft = node.copy(condition = it) }
                        }) { Text(stringResource(TextR.string.tree_edit_loop_condition)) }
                        val loopLabel = stringResource(TextR.string.tree_loop_actions)
                        BranchButton(loopLabel, node.actions) {
                            children(loopLabel, node.actions) { draft = node.copy(actions = it) }
                        }
                    }
                    is ActionNode.WaitUntil -> {
                        TextButton(onClick = {
                            condition(node.condition) { draft = node.copy(condition = it) }
                        }) { Text(stringResource(TextR.string.tree_edit_condition)) }
                        OutlinedTextField(
                            node.timeoutMs.toString(),
                            { text ->
                                text.toLongOrNull()?.takeIf { it > 0L }?.let {
                                    draft = node.copy(timeoutMs = it)
                                }
                            },
                            label = { Text(stringResource(TextR.string.tree_wait_timeout_ms)) },
                        )
                        OutlinedTextField(
                            node.pollIntervalMs.toString(),
                            { text ->
                                text.toLongOrNull()?.takeIf { it > 0L }?.let {
                                    draft = node.copy(pollIntervalMs = it)
                                }
                            },
                            label = { Text(stringResource(TextR.string.tree_wait_poll_interval_ms)) },
                        )
                    }
                    is ActionNode.WaitEvent -> {
                        Text(
                            stringResource(
                                TextR.string.tree_wait_event_events_format,
                                if (node.events.isEmpty()) stringResource(TextR.string.tree_none)
                                else node.events.joinToString { it.typeId },
                            )
                        )
                        Row {
                            Text(stringResource(TextR.string.tree_wait_unlimited))
                            Switch(
                                checked = node.unlimited,
                                onCheckedChange = { draft = node.copy(unlimited = it) },
                            )
                        }
                        if (!node.unlimited) {
                            OutlinedTextField(
                                node.timeoutMs.toString(),
                                { text ->
                                    text.toLongOrNull()?.takeIf { it > 0L }?.let {
                                        draft = node.copy(timeoutMs = it)
                                    }
                                },
                                label = { Text(stringResource(TextR.string.tree_wait_timeout_ms)) },
                            )
                        }
                        Row {
                            Text(stringResource(TextR.string.tree_wait_continue_on_timeout))
                            Switch(
                                checked = node.continueOnTimeout,
                                onCheckedChange = { draft = node.copy(continueOnTimeout = it) },
                            )
                        }
                    }
                    is ActionNode.ForEach -> {
                        OutlinedTextField(
                            node.variableName,
                            { draft = node.copy(variableName = it) },
                            label = { Text(stringResource(TextR.string.tree_foreach_variable)) },
                        )
                        ValueListEditor(node.values) { draft = node.copy(values = it) }
                        val loopLabel = stringResource(TextR.string.tree_loop_actions)
                        BranchButton(loopLabel, node.actions) {
                            children(loopLabel, node.actions) { draft = node.copy(actions = it) }
                        }
                    }
                    is ActionNode.Parallel -> {
                        node.branches.forEachIndexed { index, actions ->
                            val branchLabel = stringResource(TextR.string.tree_parallel_branch_format, index + 1)
                            BranchButton(branchLabel, actions) {
                                children(branchLabel, actions) { updated ->
                                    draft = node.copy(
                                        branches = node.branches.toMutableList().apply { this[index] = updated }
                                    )
                                }
                            }
                            TextButton(onClick = {
                                draft = node.copy(
                                    branches = node.branches.filterIndexed { i, _ -> i != index }
                                )
                            }) { Text(stringResource(TextR.string.tree_delete_parallel_branch)) }
                        }
                        TextButton(onClick = {
                            draft = node.copy(branches = node.branches + listOf(emptyList()))
                        }) { Text(stringResource(TextR.string.tree_add_parallel_branch)) }
                    }
                    is ActionNode.Try -> {
                        val tryLabel = stringResource(TextR.string.tree_try_actions)
                        val errorLabel = stringResource(TextR.string.tree_on_error)
                        val finallyLabel = stringResource(TextR.string.tree_finally)
                        BranchButton(tryLabel, node.actions) {
                            children(tryLabel, node.actions) { draft = node.copy(actions = it) }
                        }
                        BranchButton(errorLabel, node.onError) {
                            children(errorLabel, node.onError) { draft = node.copy(onError = it) }
                        }
                        BranchButton(finallyLabel, node.finallyActions) {
                            children(finallyLabel, node.finallyActions) {
                                draft = node.copy(finallyActions = it)
                            }
                        }
                    }
                    is ActionNode.CallFlow -> {
                        if (flows.isEmpty()) Text(stringResource(TextR.string.tree_create_flow_first))
                        flows.forEach { flow ->
                            Row {
                                RadioButton(node.flowId == flow.id, { draft = node.copy(flowId = flow.id) })
                                Text(flow.name)
                            }
                        }
                        OutlinedTextField(
                            node.resultVariable.orEmpty(),
                            { draft = node.copy(resultVariable = it.ifBlank { null }) },
                            label = { Text(stringResource(TextR.string.tree_result_variable)) },
                        )
                        ConfigMapEditor(node.input) { draft = node.copy(input = it) }
                    }
                    is ActionNode.Label -> OutlinedTextField(
                        node.name,
                        { draft = node.copy(name = it) },
                        label = { Text(stringResource(TextR.string.tree_label_name)) },
                    )
                    is ActionNode.Goto -> OutlinedTextField(
                        node.label,
                        { draft = node.copy(label = it) },
                        label = { Text(stringResource(TextR.string.tree_goto_target)) },
                    )
                    is ActionNode.Return -> TypedValueEditor(node.value) { draft = node.copy(value = it) }
                    is ActionNode.Action -> Text(stringResource(TextR.string.tree_missing_feature))
                    is ActionNode.Break -> Text(stringResource(TextR.string.tree_break_description))
                    is ActionNode.Continue -> Text(stringResource(TextR.string.tree_continue_description))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(draft) },
                enabled = (draft as? ActionNode.CallFlow)?.flowId?.value?.isNotBlank() != false,
            ) { Text(stringResource(TextR.string.common_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(TextR.string.common_cancel)) }
        },
    )

    child?.let { (label, nodes) ->
        ActionTreeDialog(
            label,
            nodes,
            descriptors,
            flows,
            onDismiss = { child = null },
            onSave = {
                saveChild?.invoke(it)
                child = null
            },
        )
    }
    predicate?.let { value ->
        PredicateDialog(
            value,
            descriptors,
            onDismiss = { predicate = null },
            onSave = {
                savePredicate?.invoke(it)
                predicate = null
            },
        )
    }
}

@Composable
private fun BranchButton(label: String, nodes: List<ActionNode>, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(TextR.string.tree_branch_count_format, label, nodes.size))
    }
}
