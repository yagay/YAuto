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
import com.yagay.yauto.ui.design.PageBackButton
import com.yagay.yauto.ui.design.PageBackHandler
import java.util.UUID

@Composable
internal fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun featureTitle(feature: FeatureRef, descriptors: Map<String, FeatureDescriptor>): String =
    descriptors[feature.typeId]?.let { localizedFeatureTitle(it) } ?: feature.typeId

@Composable
internal fun featureSummary(feature: FeatureRef, descriptors: Map<String, FeatureDescriptor>): String {
    val descriptor = descriptors[feature.typeId]
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
internal fun actionNodeTitle(node: ActionNode, flows: List<Flow>): String = when (node) {
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
    is ActionNode.Label -> stringResource(TextR.string.tree_label_format, node.name)
    is ActionNode.Goto -> stringResource(TextR.string.tree_goto_format, node.label)
    is ActionNode.Return -> stringResource(TextR.string.node_return)
    is ActionNode.Break -> stringResource(TextR.string.node_break)
    is ActionNode.Continue -> stringResource(TextR.string.node_continue)
}

internal fun <T> List<T>.moveItem(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices || from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

internal fun <T> List<T>.removeItem(index: Int): List<T> =
    if (index !in indices) this else toMutableList().apply { removeAt(index) }

internal fun List<FeatureRef>.upsertFeature(index: Int?, feature: FeatureRef): List<FeatureRef> =
    if (index == null || index !in indices) this + feature
    else toMutableList().apply { this[index] = feature }

internal fun List<ActionNode>.upsertActionFeature(index: Int?, feature: FeatureRef): List<ActionNode> =
    if (index == null || index !in indices) {
        this + ActionNode.Action(NodeId(UUID.randomUUID().toString()), feature)
    } else {
        toMutableList().apply {
            val old = this[index] as? ActionNode.Action
            this[index] = old?.copy(feature = feature)
                ?: ActionNode.Action(NodeId(UUID.randomUUID().toString()), feature)
        }
    }

internal fun simpleConditions(node: PredicateNode?): List<FeatureRef>? = when (node) {
    null -> emptyList()
    is PredicateNode.Condition -> listOf(node.feature)
    is PredicateNode.All -> node.children
        .mapNotNull { (it as? PredicateNode.Condition)?.feature }
        .takeIf { it.size == node.children.size }
    else -> null
}
