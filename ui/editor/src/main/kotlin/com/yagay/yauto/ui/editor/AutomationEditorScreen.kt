package com.yagay.yauto.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.model.*
import com.yagay.yauto.core.registry.*
import com.yagay.yauto.ui.design.*
import com.yagay.yauto.ui.design.R as TextR
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
    var preservedComplex by remember(initial?.id) {
        mutableStateOf(if (simpleInitial == null) originalCondition else null)
    }

    var activationMode by remember {
        mutableStateOf(if (events.isNotEmpty() || states.isEmpty()) ActivationMode.EVENT else ActivationMode.STATE)
    }
    var actionPhase by remember { mutableStateOf(MacroActionPhase.EVENT) }
    var request by remember { mutableStateOf<MacroEditRequest?>(null) }
    var menu by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var treePhase by remember { mutableStateOf<MacroActionPhase?>(null) }
    var advanced by remember { mutableStateOf(false) }
    var variableEdit by remember { mutableStateOf<Pair<String?, String>?>(null) }

    val runtimeLimit = policy.maxRuntimeMs
    val loopLimit = policy.maxLoopIterations
    val unnamedAutomation = stringResource(TextR.string.automation_unnamed)
    val locale = currentEditorLocale()

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
                name = name.trim().ifBlank { unnamedAutomation },
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
                title = {
                    Text(
                        stringResource(
                            if (initial == null) TextR.string.automation_add_title
                            else TextR.string.automation_edit_title
                        )
                    )
                },
                navigationIcon = { TextButton(onClick = onBack) { androidx.compose.material3.Icon(painter = androidx.compose.ui.res.painterResource(com.yagay.yauto.ui.design.R.drawable.ic_back), contentDescription = androidx.compose.ui.res.stringResource(com.yagay.yauto.ui.design.R.string.icon_back)) } },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(TextR.string.automation_enabled), style = MaterialTheme.typography.labelMedium)
                        Switch(enabled, { enabled = it })
                        TextButton(onClick = ::save) { Text(stringResource(TextR.string.common_save)) }
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
                    label = { Text(stringResource(TextR.string.automation_name)) },
                    singleLine = true,
                )
            }

            item {
                MacroSection(
                    title = stringResource(TextR.string.automation_triggers),
                    color = MacroPalette.Trigger,
                    count = events.size + states.size,
                    subtitle = stringResource(TextR.string.automation_triggers_subtitle),
                    onAdd = {
                        request = MacroEditRequest(
                            if (activationMode == ActivationMode.EVENT) FeatureKind.EVENT else FeatureKind.STATE
                        )
                    },
                ) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = activationMode == ActivationMode.EVENT,
                            onClick = { activationMode = ActivationMode.EVENT },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                        ) {
                            Text(stringResource(TextR.string.automation_event_count_format, events.size))
                        }
                        SegmentedButton(
                            selected = activationMode == ActivationMode.STATE,
                            onClick = { activationMode = ActivationMode.STATE },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                        ) {
                            Text(stringResource(TextR.string.automation_state_count_format, states.size))
                        }
                    }
                    val activationItems = if (activationMode == ActivationMode.EVENT) events else states
                    val kind = if (activationMode == ActivationMode.EVENT) FeatureKind.EVENT else FeatureKind.STATE
                    if (activationItems.isEmpty()) {
                        EmptyHint(
                            stringResource(
                                if (kind == FeatureKind.EVENT) TextR.string.automation_add_event_hint
                                else TextR.string.automation_add_state_hint
                            )
                        )
                    }
                    activationItems.forEachIndexed { index, feature ->
                        MacroItemRow(
                            title = featureTitle(feature, descriptors),
                            subtitle = featureSummary(feature, descriptors),
                            accent = if (kind == FeatureKind.EVENT) MacroPalette.Trigger else MacroPalette.State,
                            onClick = { request = MacroEditRequest(kind, index, feature) },
                            onMenu = { menu = (if (kind == FeatureKind.EVENT) "event" else "state") to index },
                        )
                    }
                }
            }

            item {
                MacroSection(
                    title = stringResource(TextR.string.automation_actions),
                    color = MacroPalette.Action,
                    count = onEvent.size + onEnter.size + onExit.size,
                    subtitle = stringResource(TextR.string.automation_actions_subtitle),
                    onAdd = { request = MacroEditRequest(FeatureKind.ACTION, actionPhase = actionPhase) },
                    trailing = {
                        TextButton(onClick = { treePhase = actionPhase }) {
                            Text(stringResource(TextR.string.flow_structure), color = androidx.compose.ui.graphics.Color.White)
                        }
                    },
                ) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val phases = listOf(
                            MacroActionPhase.EVENT to stringResource(TextR.string.automation_event_phase_count_format, onEvent.size),
                            MacroActionPhase.ENTER to stringResource(TextR.string.automation_enter_phase_count_format, onEnter.size),
                            MacroActionPhase.EXIT to stringResource(TextR.string.automation_exit_phase_count_format, onExit.size),
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
                    if (nodes.isEmpty()) EmptyHint(stringResource(TextR.string.automation_add_action_hint))
                    nodes.forEachIndexed { index, node ->
                        val feature = (node as? ActionNode.Action)?.feature
                        MacroItemRow(
                            title = feature?.let { featureTitle(it, descriptors) } ?: actionNodeTitle(node, flows),
                            subtitle = feature?.let { featureSummary(it, descriptors) },
                            accent = MacroPalette.Action,
                            onClick = {
                                if (feature != null) {
                                    request = MacroEditRequest(FeatureKind.ACTION, index, feature, actionPhase)
                                } else {
                                    treePhase = actionPhase
                                }
                            },
                            onMenu = { menu = "action:${actionPhase.name}" to index },
                        )
                    }
                }
            }

            item {
                MacroSection(
                    title = stringResource(TextR.string.automation_constraints),
                    color = MacroPalette.Constraint,
                    count = conditions.size + if (preservedComplex != null) 1 else 0,
                    subtitle = stringResource(TextR.string.automation_constraints_subtitle),
                    onAdd = { request = MacroEditRequest(FeatureKind.CONDITION) },
                ) {
                    if (preservedComplex != null) {
                        MacroItemRow(
                            title = stringResource(TextR.string.automation_complex_condition_title),
                            subtitle = stringResource(TextR.string.automation_complex_condition_subtitle),
                            accent = MacroPalette.Constraint,
                            onClick = {},
                        )
                    }
                    if (conditions.isEmpty() && preservedComplex == null) {
                        EmptyHint(stringResource(TextR.string.automation_no_constraints))
                    }
                    conditions.forEachIndexed { index, feature ->
                        MacroItemRow(
                            title = featureTitle(feature, descriptors),
                            subtitle = featureSummary(feature, descriptors),
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
                            Text(
                                stringResource(TextR.string.automation_advanced_settings),
                                Modifier.weight(1f),
                                fontWeight = FontWeight.SemiBold,
                            )
                            Icon(
                                painter = androidx.compose.ui.res.painterResource(
                                    if (advanced) TextR.drawable.ic_arrow_up else TextR.drawable.ic_arrow_down
                                ),
                                contentDescription = stringResource(
                                    if (advanced) TextR.string.icon_collapse else TextR.string.icon_expand
                                ),
                            )
                        }
                        if (advanced) {
                            OutlinedTextField(
                                description,
                                { description = it },
                                Modifier.fillMaxWidth(),
                                label = { Text(stringResource(TextR.string.automation_description)) },
                                minLines = 2,
                            )
                            Text(stringResource(TextR.string.automation_local_variables), fontWeight = FontWeight.Medium)
                            variables.forEach { (key, value) ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(
                                        Modifier.weight(1f).clickable {
                                            variableEdit = key to editorConfigValueText(value, locale)
                                        }
                                    ) {
                                        Text(key)
                                        Text(localizedConfigValue(value), style = MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton(onClick = { variables = variables - key }) {
                                        Text(stringResource(TextR.string.common_delete))
                                    }
                                }
                            }
                            OutlinedButton(
                                onClick = { variableEdit = null to "" },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(TextR.string.automation_add_local_variable)) }
                            HorizontalDivider()
                            Text(stringResource(TextR.string.automation_conflict_policy), fontWeight = FontWeight.Medium)
                            listOf(
                                ConflictPolicy.QUEUE to stringResource(TextR.string.automation_conflict_queue),
                                ConflictPolicy.IGNORE_NEW to stringResource(TextR.string.automation_conflict_ignore_new),
                                ConflictPolicy.CANCEL_PREVIOUS to stringResource(TextR.string.automation_conflict_cancel_previous),
                                ConflictPolicy.PARALLEL to stringResource(TextR.string.automation_conflict_parallel),
                            ).forEach { (value, label) ->
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        policy = policy.copy(conflictPolicy = value)
                                    },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        policy.conflictPolicy == value,
                                        { policy = policy.copy(conflictPolicy = value) },
                                    )
                                    Text(label)
                                }
                            }
                            Text(
                                stringResource(TextR.string.automation_limits_format, runtimeLimit, loopLimit),
                                style = MaterialTheme.typography.bodySmall,
                            )
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
            title = { Text(stringResource(TextR.string.automation_item_operations)) },
            text = {
                Column {
                    TextButton(
                        enabled = index > 0,
                        onClick = {
                            when {
                                section == "event" -> events = events.moveItem(index, index - 1)
                                section == "state" -> states = states.moveItem(index, index - 1)
                                section == "condition" -> conditions = conditions.moveItem(index, index - 1)
                                section.startsWith("action:") -> when (
                                    MacroActionPhase.valueOf(section.substringAfter(':'))
                                ) {
                                    MacroActionPhase.EVENT -> onEvent = onEvent.moveItem(index, index - 1)
                                    MacroActionPhase.ENTER -> onEnter = onEnter.moveItem(index, index - 1)
                                    MacroActionPhase.EXIT -> onExit = onExit.moveItem(index, index - 1)
                                }
                            }
                            menu = null
                        },
                    ) { Text(stringResource(TextR.string.flow_move_up)) }
                    TextButton(
                        enabled = index < size - 1,
                        onClick = {
                            when {
                                section == "event" -> events = events.moveItem(index, index + 1)
                                section == "state" -> states = states.moveItem(index, index + 1)
                                section == "condition" -> conditions = conditions.moveItem(index, index + 1)
                                section.startsWith("action:") -> when (
                                    MacroActionPhase.valueOf(section.substringAfter(':'))
                                ) {
                                    MacroActionPhase.EVENT -> onEvent = onEvent.moveItem(index, index + 1)
                                    MacroActionPhase.ENTER -> onEnter = onEnter.moveItem(index, index + 1)
                                    MacroActionPhase.EXIT -> onExit = onExit.moveItem(index, index + 1)
                                }
                            }
                            menu = null
                        },
                    ) { Text(stringResource(TextR.string.flow_move_down)) }
                    TextButton(
                        onClick = {
                            when {
                                section == "event" -> events = events.removeItem(index)
                                section == "state" -> states = states.removeItem(index)
                                section == "condition" -> conditions = conditions.removeItem(index)
                                section.startsWith("action:") -> when (
                                    MacroActionPhase.valueOf(section.substringAfter(':'))
                                ) {
                                    MacroActionPhase.EVENT -> onEvent = onEvent.removeItem(index)
                                    MacroActionPhase.ENTER -> onEnter = onEnter.removeItem(index)
                                    MacroActionPhase.EXIT -> onExit = onExit.removeItem(index)
                                }
                            }
                            menu = null
                        },
                    ) { Text(stringResource(TextR.string.common_delete)) }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { menu = null }) { Text(stringResource(TextR.string.common_cancel)) }
            },
        )
    }

    treePhase?.let { phase ->
        val nodes = when (phase) {
            MacroActionPhase.EVENT -> onEvent
            MacroActionPhase.ENTER -> onEnter
            MacroActionPhase.EXIT -> onExit
        }
        ActionTreeDialog(
            title = stringResource(
                when (phase) {
                    MacroActionPhase.EVENT -> TextR.string.automation_event_tree_title
                    MacroActionPhase.ENTER -> TextR.string.automation_enter_tree_title
                    MacroActionPhase.EXIT -> TextR.string.automation_exit_tree_title
                }
            ),
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
            title = {
                Text(
                    stringResource(
                        if (oldName == null) TextR.string.automation_add_local_variable_title
                        else TextR.string.automation_edit_local_variable_title
                    )
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        variableName,
                        { variableName = it },
                        label = { Text(stringResource(TextR.string.automation_variable_name)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        variableValue,
                        { variableValue = it },
                        label = { Text(stringResource(TextR.string.variables_value)) },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = variableName.isNotBlank(),
                    onClick = {
                        variables = variables.toMutableMap().apply {
                            oldName?.takeIf { it != variableName.trim() }?.let(::remove)
                            put(variableName.trim(), ConfigValue.StringValue(variableValue))
                        }
                        variableEdit = null
                    },
                ) { Text(stringResource(TextR.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { variableEdit = null }) { Text(stringResource(TextR.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun featureTitle(feature: FeatureRef, descriptors: List<FeatureDescriptor>): String =
    descriptors.firstOrNull { it.id.value == feature.typeId }?.let { localizedFeatureTitle(it) } ?: feature.typeId

@Composable
private fun featureSummary(feature: FeatureRef, descriptors: List<FeatureDescriptor>): String {
    val descriptor = descriptors.firstOrNull { it.id.value == feature.typeId }
    val items = feature.config.entries
        .filterNot { it.key.startsWith("source.") }
        .take(3)
        .map { entry ->
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
    return localizedList(items)
}

@Composable
private fun actionNodeTitle(node: ActionNode, flows: List<Flow>): String = when (node) {
    is ActionNode.Action -> node.feature.typeId
    is ActionNode.If -> stringResource(TextR.string.node_if)
    is ActionNode.Switch -> stringResource(TextR.string.node_switch)
    is ActionNode.Repeat -> stringResource(TextR.string.node_repeat_format, node.times)
    is ActionNode.While -> stringResource(TextR.string.node_while)
    is ActionNode.DoWhile -> stringResource(TextR.string.node_while) + " (do)"
    is ActionNode.WaitUntil -> stringResource(TextR.string.tree_wait_until)
    is ActionNode.WaitEvent -> stringResource(TextR.string.tree_wait_until) + " (event)"
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

private fun <T> List<T>.moveItem(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices || from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

private fun <T> List<T>.removeItem(index: Int): List<T> =
    if (index !in indices) this else toMutableList().apply { removeAt(index) }

private fun List<FeatureRef>.upsertFeature(index: Int?, feature: FeatureRef): List<FeatureRef> =
    if (index == null || index !in indices) this + feature
    else toMutableList().apply { this[index] = feature }

private fun List<ActionNode>.upsertActionFeature(index: Int?, feature: FeatureRef): List<ActionNode> =
    if (index == null || index !in indices) {
        this + ActionNode.Action(NodeId(UUID.randomUUID().toString()), feature)
    } else {
        toMutableList().apply {
            val old = this[index] as? ActionNode.Action
            this[index] = old?.copy(feature = feature)
                ?: ActionNode.Action(NodeId(UUID.randomUUID().toString()), feature)
        }
    }

private fun simpleConditions(node: PredicateNode?): List<FeatureRef>? = when (node) {
    null -> emptyList()
    is PredicateNode.Condition -> listOf(node.feature)
    is PredicateNode.All -> node.children
        .mapNotNull { (it as? PredicateNode.Condition)?.feature }
        .takeIf { it.size == node.children.size }
    else -> null
}
