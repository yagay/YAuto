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


@Composable
internal fun NodeDialog(
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
internal fun BranchButton(label: String, nodes: List<ActionNode>, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(TextR.string.tree_branch_count_format, label, nodes.size))
    }
}
