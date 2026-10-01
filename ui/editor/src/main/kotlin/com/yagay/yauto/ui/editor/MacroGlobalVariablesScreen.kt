package com.yagay.yauto.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yagay.yauto.ui.design.MacroItemRow
import com.yagay.yauto.ui.design.MacroPalette

private data class VariableDraft(val originalName: String?, val name: String, val value: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacroGlobalVariablesScreen(
    initial: Map<String, String>,
    onSave: (Map<String, String>) -> Unit,
    onBack: () -> Unit,
) {
    var values by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf<VariableDraft?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (draft == null) "全局变量" else if (draft?.originalName == null) "添加变量" else "编辑变量") },
                navigationIcon = {
                    TextButton(onClick = { if (draft != null) draft = null else onBack() }) { Text("‹") }
                },
                actions = {
                    if (draft == null) TextButton(onClick = { onSave(values) }) { Text("保存") }
                },
            )
        },
        floatingActionButton = {
            if (draft == null) FloatingActionButton(onClick = { draft = VariableDraft(null, "", "") }) { Text("＋") }
        },
    ) { padding ->
        val current = draft
        if (current != null) {
            VariableEditorPage(
                Modifier.padding(padding),
                current,
                existingNames = values.keys - current.originalName,
                onChange = { draft = it },
                onSave = { updated ->
                    val clean = updated.name.trim()
                    values = values.toMutableMap().apply {
                        updated.originalName?.takeIf { it != clean }?.let(::remove)
                        put(clean, updated.value)
                    }
                    draft = null
                },
            )
        } else {
            val filtered = remember(values, query) {
                values.entries.filter { query.isBlank() || it.key.contains(query, true) || it.value.contains(query, true) }
                    .sortedBy { it.key.lowercase() }
            }
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MacroPalette.Variable.copy(alpha = .10f))) {
                        Column(Modifier.padding(14.dp)) {
                            Text("全局变量", fontWeight = FontWeight.SemiBold)
                            Text("可被所有自动化和流程读取。变量选择器使用名称引用；当前工作区格式保留字符串值。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        query,
                        { query = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("搜索变量") },
                        singleLine = true,
                    )
                }
                if (filtered.isEmpty()) {
                    item { Text(if (values.isEmpty()) "还没有全局变量。" else "没有匹配的变量。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(filtered, key = { it.key }) { entry ->
                    MacroItemRow(
                        title = entry.key,
                        subtitle = entry.value.ifBlank { "（空字符串）" },
                        accent = MacroPalette.Variable,
                        onClick = { draft = VariableDraft(entry.key, entry.key, entry.value) },
                        onMenu = { values = values - entry.key },
                    )
                }
            }
        }
    }
}

@Composable
private fun VariableEditorPage(
    modifier: Modifier,
    draft: VariableDraft,
    existingNames: Set<String>,
    onChange: (VariableDraft) -> Unit,
    onSave: (VariableDraft) -> Unit,
) {
    val clean = draft.name.trim()
    val valid = clean.isNotBlank() && clean !in existingNames
    Column(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            draft.name,
            { onChange(draft.copy(name = it)) },
            Modifier.fillMaxWidth(),
            label = { Text("变量名称") },
            singleLine = true,
            isError = draft.name.isNotBlank() && !valid,
            supportingText = {
                when {
                    clean.isBlank() -> Text("变量名不能为空")
                    clean in existingNames -> Text("已有同名变量")
                    else -> Text("在模板中可通过变量选择器引用")
                }
            },
        )
        OutlinedTextField(
            draft.value,
            { onChange(draft.copy(value = it)) },
            Modifier.fillMaxWidth(),
            label = { Text("值") },
            minLines = 3,
        )
        Button(onClick = { onSave(draft) }, enabled = valid, modifier = Modifier.fillMaxWidth()) { Text("保存变量") }
    }
}
