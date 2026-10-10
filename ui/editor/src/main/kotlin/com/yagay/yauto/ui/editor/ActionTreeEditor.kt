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

internal fun nodeId() = NodeId(UUID.randomUUID().toString())

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
internal fun nodeLabel(
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

