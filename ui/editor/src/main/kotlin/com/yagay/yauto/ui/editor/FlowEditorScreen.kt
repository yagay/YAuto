package com.yagay.yauto.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.model.*
import com.yagay.yauto.core.registry.FeatureDescriptor
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowEditorScreen(initial: Flow?, descriptors: List<FeatureDescriptor>, flows: List<Flow>, onSave: (Flow) -> Unit, onBack: () -> Unit) {
    var flow by remember(initial?.id) { mutableStateOf(initial ?: Flow(FlowId(UUID.randomUUID().toString()), "新流程")) }
    var actionsOpen by remember { mutableStateOf(false) }
    var parameter by remember { mutableStateOf<Pair<Boolean, Int?>?>(null) }
    Scaffold(topBar = { TopAppBar(title = { Text("编辑流程") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
        actions = { TextButton(enabled = flow.name.isNotBlank(), onClick = { onSave(flow.copy(name = flow.name.trim())) }) { Text("保存") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(flow.name, { flow = flow.copy(name = it) }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(flow.description.orEmpty(), { flow = flow.copy(description = it.ifBlank { null }) }, label = { Text("说明") }, modifier = Modifier.fillMaxWidth())
            for (input in listOf(true, false)) {
                Text(if (input) "输入参数" else "输出参数", style = MaterialTheme.typography.titleMedium)
                val parameters = if (input) flow.inputs else flow.outputs
                parameters.forEachIndexed { index, value -> Row {
                    TextButton(onClick = { parameter = input to index }) { Text("${value.name} · ${value.type}") }
                    TextButton(onClick = { val updated = parameters.filterIndexed { i, _ -> i != index }; flow = if (input) flow.copy(inputs = updated) else flow.copy(outputs = updated) }) { Text("删除") }
                } }
                OutlinedButton(onClick = { parameter = input to null }) { Text("+ 参数") }
            }
            Button(onClick = { actionsOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("编辑动作 (${flow.actions.size})") }
            Text("调用时可以传入参数，并把返回值保存到变量。输入参数可通过变量名或 input.参数名引用。")
        }
    }
    if (actionsOpen) ActionTreeDialog("流程动作", flow.actions, descriptors, flows.filterNot { it.id == flow.id } + flow,
        { actionsOpen = false }) { flow = flow.copy(actions = it); actionsOpen = false }
    parameter?.let { (input, index) ->
        val values = if (input) flow.inputs else flow.outputs
        ParameterDialog(index?.let { values.getOrNull(it) }, values.filterIndexed { i, _ -> i != index }.map { it.name }.toSet(), { parameter = null }) { value ->
            val updated = if (index == null) values + value else values.toMutableList().apply { this[index] = value }
            flow = if (input) flow.copy(inputs = updated) else flow.copy(outputs = updated)
            parameter = null
        }
    }
}

@Composable private fun ParameterDialog(initial: FlowParameter?, names: Set<String>, onDismiss: () -> Unit, onSave: (FlowParameter) -> Unit) {
    var draft by remember { mutableStateOf(initial ?: FlowParameter("", ValueType.ANY)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("流程参数") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, label = { Text("参数名") })
            Text("类型")
            ValueType.entries.forEach { type -> Row {
                RadioButton(draft.type == type, { draft = draft.copy(type = type) }); Text(type.name)
            } }
            Row { Text("必填"); Switch(draft.required, { draft = draft.copy(required = it) }) }
            Text("默认值")
            TypedValueEditor(draft.defaultValue) { draft = draft.copy(defaultValue = it) }
        }
    }, confirmButton = { TextButton(enabled = draft.name.isNotBlank() && draft.name.trim() !in names, onClick = { onSave(draft.copy(name = draft.name.trim())) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
fun GlobalVariablesScreen(initial: Map<String, String>, onSave: (Map<String, String>) -> Unit, onBack: () -> Unit) {
    MacroGlobalVariablesScreen(initial = initial, onSave = onSave, onBack = onBack)
}
