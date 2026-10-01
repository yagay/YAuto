package com.yagay.yauto.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    featureCount: Int,
    automationCount: Int,
    flowCount: Int,
    importerNames: List<String>,
    importSummary: String?,
    runtimeSummary: String?,
    onOpenEditor: () -> Unit,
    onImport: () -> Unit,
    onRunManual: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("YAuto") }) },
        bottomBar = {
            NavigationBar {
                listOf("自动化", "流程", "界面", "变量", "日志").forEachIndexed { index, label ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = {}, label = { Text(label) })
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("模块化自动化平台", style = MaterialTheme.typography.headlineSmall)
            Text("自动化 $automationCount · 流程 $flowCount · 已注册 $featureCount 个功能")
            Text("导入：${importerNames.joinToString()}", style = MaterialTheme.typography.bodySmall)
            Button(onClick = onOpenEditor, modifier = Modifier.fillMaxWidth()) { Text("新建自动化") }
            OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("导入 MacroDroid / ShortX / Tasker") }
            OutlinedButton(onClick = onRunManual, modifier = Modifier.fillMaxWidth()) { Text("发送手动测试事件") }
            OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) { Text("诊断中心") }
            importSummary?.let { AssistChip(onClick = {}, label = { Text(it) }) }
            runtimeSummary?.let { AssistChip(onClick = {}, label = { Text(it) }) }
            Text("功能、导入器、Runtime 和诊断采集器均为独立模块，可单独增加或删除。", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
