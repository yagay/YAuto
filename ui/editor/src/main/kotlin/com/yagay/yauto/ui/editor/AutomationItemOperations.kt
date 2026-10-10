package com.yagay.yauto.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yagay.yauto.core.model.ActionNode
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.NodeId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.ui.design.R as TextR
import java.util.UUID

/** Immutable tree item operations shared across all automation sections. */
internal data class EditableAutomationSections(
    val events: List<FeatureRef>,
    val states: List<FeatureRef>,
    val conditions: List<FeatureRef>,
    val onEvent: List<ActionNode>,
    val onEnter: List<ActionNode>,
    val onExit: List<ActionNode>,
) {
    fun actionPhase(section: String): List<ActionNode>? = when (section) {
        "action:EVENT" -> onEvent
        "action:ENTER" -> onEnter
        "action:EXIT" -> onExit
        else -> null
    }

    fun kind(section: String): FeatureKind = when (section) {
        "event" -> FeatureKind.EVENT
        "state" -> FeatureKind.STATE
        "condition" -> FeatureKind.CONDITION
        else -> FeatureKind.ACTION
    }

    fun count(section: String): Int = when (section) {
        "event" -> events.size
        "state" -> states.size
        "condition" -> conditions.size
        else -> actionPhase(section)?.size ?: 0
    }

    fun feature(section: String, index: Int): FeatureRef? = when (section) {
        "event" -> events.getOrNull(index)
        "state" -> states.getOrNull(index)
        "condition" -> conditions.getOrNull(index)
        else -> (actionPhase(section)?.getOrNull(index) as? ActionNode.Action)?.feature
    }

    fun actionNode(section: String, index: Int): ActionNode.Action? =
        actionPhase(section)?.getOrNull(index) as? ActionNode.Action

    fun modify(section: String, index: Int, change: AutomationItemChange): EditableAutomationSections {
        return when (section) {
            "event" -> copy(events = events.change(index, change) { it })
            "state" -> copy(states = states.change(index, change) { it })
            "condition" -> copy(conditions = conditions.change(index, change) { it })
            "action:EVENT" -> copy(onEvent = onEvent.change(index, change, ::duplicateAction))
            "action:ENTER" -> copy(onEnter = onEnter.change(index, change, ::duplicateAction))
            "action:EXIT" -> copy(onExit = onExit.change(index, change, ::duplicateAction))
            else -> this
        }
    }

    private fun duplicateAction(node: ActionNode): ActionNode? =
        (node as? ActionNode.Action)?.copy(id = NodeId(UUID.randomUUID().toString()))
}

internal enum class AutomationItemChange { UP, DOWN, DUPLICATE, DELETE, TOGGLE_ENABLED }

private fun <T> List<T>.change(
    index: Int,
    change: AutomationItemChange,
    duplicate: (T) -> T?,
): List<T> {
    if (index !in indices) return this
    return toMutableList().apply {
        when (change) {
            AutomationItemChange.UP -> if (index > 0) add(index - 1, removeAt(index))
            AutomationItemChange.DOWN -> if (index < lastIndex) add(index + 1, removeAt(index))
            AutomationItemChange.DUPLICATE -> duplicate(this[index])?.let { add(index + 1, it) }
            AutomationItemChange.DELETE -> removeAt(index)
            AutomationItemChange.TOGGLE_ENABLED -> {
                val action = this[index] as? ActionNode.Action
                if (action != null) {
                    @Suppress("UNCHECKED_CAST")
                    set(index, action.copy(enabled = !action.enabled) as T)
                }
            }
        }
    }
}

@Composable
internal fun AutomationItemOperationsDialog(
    section: String,
    index: Int,
    items: EditableAutomationSections,
    onChange: (EditableAutomationSections) -> Unit,
    onTest: (FeatureRef, FeatureKind) -> Unit,
    onDismiss: () -> Unit,
) {
    val feature = items.feature(section, index)
    val kind = items.kind(section)
    val action = items.actionNode(section, index)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(TextR.string.automation_item_operations)) },
        text = {
            Column {
                TextButton(enabled = feature != null, onClick = {
                    feature?.let { onTest(it, kind) }
                }) {
                    Text(stringResource(when (kind) {
                        FeatureKind.ACTION -> TextR.string.feature_test_action
                        FeatureKind.CONDITION -> TextR.string.feature_test_constraint
                        FeatureKind.STATE -> TextR.string.feature_test_state
                        FeatureKind.EVENT -> TextR.string.feature_test_trigger
                    }))
                }
                TextButton(enabled = feature != null,
                    onClick = { onChange(items.modify(section, index, AutomationItemChange.DUPLICATE)) }) {
                    Text(stringResource(TextR.string.feature_test_duplicate))
                }
                if (action != null) {
                    TextButton(
                        onClick = { onChange(items.modify(section, index, AutomationItemChange.TOGGLE_ENABLED)) },
                    ) {
                        Text(stringResource(if (action.enabled)
                            TextR.string.feature_test_disable_action
                        else TextR.string.feature_test_enable_action))
                    }
                }
                TextButton(enabled = index > 0,
                    onClick = { onChange(items.modify(section, index, AutomationItemChange.UP)) }) {
                    Text(stringResource(TextR.string.flow_move_up))
                }
                TextButton(enabled = index < items.count(section) - 1,
                    onClick = { onChange(items.modify(section, index, AutomationItemChange.DOWN)) }) {
                    Text(stringResource(TextR.string.flow_move_down))
                }
                TextButton(onClick = {
                    onChange(items.modify(section, index, AutomationItemChange.DELETE))
                }) { Text(stringResource(TextR.string.common_delete)) }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(TextR.string.common_cancel))
            }
        },
    )
}
