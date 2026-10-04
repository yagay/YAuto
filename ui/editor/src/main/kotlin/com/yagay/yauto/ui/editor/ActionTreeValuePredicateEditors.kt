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

private enum class ValueEditorKind { TEXT, NUMBER, BOOLEAN, NULL }
private enum class PredicateGroupKind { ALL, ANY, NONE }

@Composable
internal fun TypedValueEditor(value: ConfigValue, onChange: (ConfigValue) -> Unit) {
    val currentKind = when (value) {
        is ConfigValue.NumberValue -> ValueEditorKind.NUMBER
        is ConfigValue.BooleanValue -> ValueEditorKind.BOOLEAN
        ConfigValue.NullValue -> ValueEditorKind.NULL
        else -> ValueEditorKind.TEXT
    }
    val scalarKinds = listOf(
        ValueEditorKind.TEXT to stringResource(TextR.string.value_text),
        ValueEditorKind.NUMBER to stringResource(TextR.string.value_number),
        ValueEditorKind.BOOLEAN to stringResource(TextR.string.value_boolean),
        ValueEditorKind.NULL to stringResource(TextR.string.value_null),
    )
    Row {
        scalarKinds.forEach { (kind, label) ->
            FilterChip(
                selected = kind == currentKind && value !is ConfigValue.ListValue && value !is ConfigValue.ObjectValue,
                onClick = {
                    onChange(
                        when (kind) {
                            ValueEditorKind.NUMBER -> ConfigValue.NumberValue(0.0)
                            ValueEditorKind.BOOLEAN -> ConfigValue.BooleanValue(false)
                            ValueEditorKind.NULL -> ConfigValue.NullValue
                            ValueEditorKind.TEXT -> ConfigValue.StringValue("")
                        }
                    )
                },
                label = { Text(label) },
            )
        }
    }
    Row {
        TextButton(onClick = { onChange(ConfigValue.ListValue(emptyList())) }) {
            Text(stringResource(TextR.string.value_list))
        }
        TextButton(onClick = { onChange(ConfigValue.ObjectValue(emptyMap())) }) {
            Text(stringResource(TextR.string.value_object))
        }
    }
    when (value) {
        is ConfigValue.StringValue -> OutlinedTextField(
            value.value,
            { onChange(ConfigValue.StringValue(it)) },
            label = { Text(stringResource(TextR.string.value_value)) },
        )
        is ConfigValue.NumberValue -> {
            var text by remember(value) { mutableStateOf(value.value.toString()) }
            OutlinedTextField(
                text,
                { raw ->
                    text = raw
                    raw.toDoubleOrNull()?.takeIf { it.isFinite() }?.let {
                        onChange(ConfigValue.NumberValue(it))
                    }
                },
                label = { Text(stringResource(TextR.string.value_numeric)) },
            )
        }
        is ConfigValue.BooleanValue -> Switch(value.value, { onChange(ConfigValue.BooleanValue(it)) })
        is ConfigValue.ListValue -> ValueListEditor(value.value) { onChange(ConfigValue.ListValue(it)) }
        is ConfigValue.ObjectValue -> ConfigMapEditor(value.value) { onChange(ConfigValue.ObjectValue(it)) }
        ConfigValue.NullValue -> Text(stringResource(TextR.string.value_null))
    }
}

@Composable
internal fun ValueListEditor(values: List<ConfigValue>, onChange: (List<ConfigValue>) -> Unit) {
    values.forEachIndexed { index, value ->
        TypedValueEditor(value) { updated ->
            onChange(values.toMutableList().apply { this[index] = updated })
        }
        TextButton(onClick = { onChange(values.filterIndexed { i, _ -> i != index }) }) {
            Text(stringResource(TextR.string.value_delete_item_format, index + 1))
        }
    }
    TextButton(onClick = { onChange(values + ConfigValue.StringValue("")) }) {
        Text(stringResource(TextR.string.value_add_item))
    }
}

@Composable
internal fun ConfigMapEditor(values: ConfigMap, onChange: (ConfigMap) -> Unit) {
    var name by remember { mutableStateOf("") }
    values.forEach { (key, value) ->
        Text(key)
        TypedValueEditor(value) { onChange(values + (key to it)) }
        TextButton(onClick = { onChange(values - key) }) {
            Text(stringResource(TextR.string.value_delete_key_format, key))
        }
    }
    OutlinedTextField(
        name,
        { name = it },
        label = { Text(stringResource(TextR.string.value_parameter_name)) },
    )
    TextButton(
        enabled = name.isNotBlank() && name !in values,
        onClick = {
            onChange(values + (name to ConfigValue.StringValue("")))
            name = ""
        },
    ) { Text(stringResource(TextR.string.value_add_parameter)) }
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
        title = { Text(stringResource(TextR.string.predicate_edit)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row {
                    TextButton(onClick = { draft = PredicateNode.Expression("") }) {
                        Text(stringResource(TextR.string.predicate_expression))
                    }
                    TextButton(onClick = { picker = true }) {
                        Text(stringResource(TextR.string.predicate_feature))
                    }
                }
                val groups = listOf(
                    PredicateGroupKind.ALL to stringResource(TextR.string.predicate_all),
                    PredicateGroupKind.ANY to stringResource(TextR.string.predicate_any),
                    PredicateGroupKind.NONE to stringResource(TextR.string.predicate_none),
                )
                Row {
                    groups.forEach { (group, label) ->
                        TextButton(onClick = {
                            val children = when (val node = draft) {
                                is PredicateNode.All -> node.children
                                is PredicateNode.Any -> node.children
                                is PredicateNode.None -> node.children
                                else -> listOf(node)
                            }
                            draft = when (group) {
                                PredicateGroupKind.ALL -> PredicateNode.All(children)
                                PredicateGroupKind.ANY -> PredicateNode.Any(children)
                                PredicateGroupKind.NONE -> PredicateNode.None(children)
                            }
                        }) { Text(label) }
                    }
                }
                when (val node = draft) {
                    is PredicateNode.Expression -> OutlinedTextField(
                        node.expression,
                        { draft = node.copy(expression = it) },
                        label = { Text(stringResource(TextR.string.predicate_boolean_expression)) },
                    )
                    is PredicateNode.Literal -> Row {
                        Text(stringResource(TextR.string.predicate_literal))
                        Switch(node.value, { draft = PredicateNode.Literal(it) })
                    }
                    is PredicateNode.Condition -> TextButton(onClick = { editingFeature = node.feature }) {
                        Text(
                            descriptors.firstOrNull { it.id.value == node.feature.typeId }
                                ?.let { localizedFeatureTitle(it) }
                                ?: node.feature.typeId
                        )
                    }
                    else -> {
                        val children = when (node) {
                            is PredicateNode.All -> node.children
                            is PredicateNode.Any -> node.children
                            is PredicateNode.None -> node.children
                            else -> emptyList()
                        }
                        Text(
                            stringResource(
                                when (node) {
                                    is PredicateNode.All -> TextR.string.predicate_all_satisfied
                                    is PredicateNode.Any -> TextR.string.predicate_any_satisfied
                                    else -> TextR.string.predicate_none_satisfied
                                }
                            )
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
                                TextButton(onClick = { editing = index to value }) {
                                    Text(stringResource(TextR.string.predicate_condition_format, index + 1))
                                }
                                TextButton(onClick = {
                                    updated(children.filterIndexed { i, _ -> i != index })
                                }) { Text(stringResource(TextR.string.common_delete)) }
                            }
                        }
                        TextButton(onClick = { updated(children + PredicateNode.Literal(true)) }) {
                            Text(stringResource(TextR.string.predicate_add_condition))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }) { Text(stringResource(TextR.string.common_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(TextR.string.common_cancel)) }
        },
    )

    if (picker) {
        MacroFeaturePickerDialog(
            kind = FeatureKind.CONDITION,
            descriptors = descriptors,
            onDismiss = { picker = false },
            onPick = { feature ->
                draft = PredicateNode.Condition(feature)
                picker = false
            },
        )
    }

    editingFeature?.let { feature ->
        MacroFeaturePickerDialog(
            kind = FeatureKind.CONDITION,
            descriptors = descriptors,
            initial = feature,
            onDismiss = { editingFeature = null },
            onPick = { updated ->
                draft = PredicateNode.Condition(updated)
                editingFeature = null
            },
        )
    }

    editing?.let { (index, value) ->
        PredicateDialog(value, descriptors, { editing = null }) { updated ->
            draft = when (val node = draft) {
                is PredicateNode.All -> node.copy(
                    children = node.children.toMutableList().apply { this[index] = updated }
                )
                is PredicateNode.Any -> node.copy(
                    children = node.children.toMutableList().apply { this[index] = updated }
                )
                is PredicateNode.None -> node.copy(
                    children = node.children.toMutableList().apply { this[index] = updated }
                )
                else -> node
            }
            editing = null
        }
    }
}

