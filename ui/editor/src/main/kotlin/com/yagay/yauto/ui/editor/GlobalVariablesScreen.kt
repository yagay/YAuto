package com.yagay.yauto.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yagay.yauto.ui.design.MacroItemRow
import com.yagay.yauto.ui.design.MacroPalette
import com.yagay.yauto.ui.design.R as TextR
import com.yagay.yauto.ui.design.PageBackButton

private data class VariableDraft(val originalName: String?, val name: String, val value: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalVariablesScreen(
    initial: Map<String, String>,
    onSave: (Map<String, String>) -> Unit,
    onBack: () -> Unit,
) {
    var values by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf<VariableDraft?>(null) }
    fun navigateBack() {
        if (draft != null) draft = null else onBack()
    }
    BackHandler(onBack = ::navigateBack)

    val locale = currentEditorLocale()
    val nameComparator = remember(locale) { localizedStringComparator(locale) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            draft == null -> stringResource(TextR.string.variables_title)
                            draft?.originalName == null -> stringResource(TextR.string.variables_add_title)
                            else -> stringResource(TextR.string.variables_edit_title)
                        }
                    )
                },
                navigationIcon = {
                    PageBackButton(onBack = ::navigateBack)
                },
                actions = {
                    if (draft == null) {
                        TextButton(onClick = { onSave(values) }) { Text(stringResource(TextR.string.common_save)) }
                    }
                },
            )
        },
        floatingActionButton = {
            if (draft == null) {
                FloatingActionButton(onClick = { draft = VariableDraft(null, "", "") }) { androidx.compose.material3.Icon(painter = androidx.compose.ui.res.painterResource(com.yagay.yauto.ui.design.R.drawable.ic_add), contentDescription = androidx.compose.ui.res.stringResource(com.yagay.yauto.ui.design.R.string.icon_add)) }
            }
        },
    ) { padding ->
        val current = draft
        if (current != null) {
            VariableEditorPage(
                Modifier.padding(padding),
                current,
                existingNames = current.originalName?.let { values.keys - it } ?: values.keys,
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
            val filtered = remember(values, query, nameComparator) {
                values.entries
                    .filter { query.isBlank() || it.key.contains(query, true) || it.value.contains(query, true) }
                    .sortedWith { left, right -> nameComparator.compare(left.key, right.key) }
            }
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MacroPalette.Variable.copy(alpha = .10f))) {
                        Column(Modifier.padding(14.dp)) {
                            Text(stringResource(TextR.string.variables_title), fontWeight = FontWeight.SemiBold)
                            Text(stringResource(TextR.string.variables_description), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        query,
                        { query = it },
                        Modifier.fillMaxWidth(),
                        label = { Text(stringResource(TextR.string.variables_search)) },
                        singleLine = true,
                    )
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            stringResource(
                                if (values.isEmpty()) TextR.string.variables_empty else TextR.string.variables_no_match
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(filtered, key = { it.key }) { entry ->
                    MacroItemRow(
                        title = entry.key,
                        subtitle = entry.value.ifBlank { stringResource(TextR.string.variables_empty_value) },
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
            label = { Text(stringResource(TextR.string.variables_name)) },
            singleLine = true,
            isError = draft.name.isNotBlank() && !valid,
            supportingText = {
                Text(
                    stringResource(
                        when {
                            clean.isBlank() -> TextR.string.variables_name_required
                            clean in existingNames -> TextR.string.variables_name_duplicate
                            else -> TextR.string.variables_name_hint
                        }
                    )
                )
            },
        )
        OutlinedTextField(
            draft.value,
            { onChange(draft.copy(value = it)) },
            Modifier.fillMaxWidth(),
            label = { Text(stringResource(TextR.string.variables_value)) },
            minLines = 3,
        )
        Button(onClick = { onSave(draft) }, enabled = valid, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(TextR.string.variables_save))
        }
    }
}
