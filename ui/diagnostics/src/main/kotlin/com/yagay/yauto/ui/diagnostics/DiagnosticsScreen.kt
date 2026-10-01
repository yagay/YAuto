package com.yagay.yauto.ui.diagnostics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.diagnostics.CollectorStatus
import com.yagay.yauto.core.diagnostics.DiagnosticSnapshot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    statuses: List<CollectorStatus>,
    snapshot: DiagnosticSnapshot?,
    collecting: Boolean,
    onCollect: () -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("诊断中心") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Execution Trace + Root + LSPosed + SystemUI/system_server 相关日志")
            statuses.forEach { status ->
                ListItem(headlineContent = { Text(status.collectorId) }, supportingContent = { Text(status.message.orEmpty()) }, trailingContent = { Text(if (status.available) "可用" else "不可用") })
            }
            Button(onClick = onCollect, enabled = !collecting, modifier = Modifier.fillMaxWidth()) { Text(if (collecting) "正在收集…" else "收集诊断") }
            OutlinedButton(onClick = onExport, enabled = snapshot != null && !collecting, modifier = Modifier.fillMaxWidth()) { Text("导出诊断 JSON") }
            snapshot?.let { data ->
                Text("已读取 ${data.records.size} 条诊断记录", style = MaterialTheme.typography.titleMedium)
                data.records.take(12).forEach { record ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${record.source} · ${record.title}", style = MaterialTheme.typography.labelLarge)
                            Text(record.message.take(700), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Text("诊断只在本机生成，不会自动上传。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
