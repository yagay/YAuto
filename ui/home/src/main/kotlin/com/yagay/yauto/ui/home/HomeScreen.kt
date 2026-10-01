package com.yagay.yauto.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.model.Automation
import com.yagay.yauto.core.model.Flow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    featureCount: Int,
    automations: List<Automation>,
    flowCount: Int,
    importerNames: List<String>,
    importSummary: String?,
    runtimeSummary: String?,
    onNewAutomation: () -> Unit,
    onEditAutomation: (Automation) -> Unit,
    onToggleAutomation: (Automation, Boolean) -> Unit,
    onDeleteAutomation: (Automation) -> Unit,
    onImport: () -> Unit,
    onRunManual: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    flows: List<Flow> = emptyList(),
    onNewFlow: () -> Unit = {},
    onEditFlow: (Flow) -> Unit = {},
    onDeleteFlow: (Flow) -> Unit = {},
    onEditVariables: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onBackup: () -> Unit = {},
    onRestore: () -> Unit = {},
) {
    var tab by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<Automation?>(null) }
    var pendingFlowDelete by remember { mutableStateOf<Flow?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("YAuto") }) },
        bottomBar = {
            NavigationBar {
                listOf("自动化", "流程", "变量", "日志").forEachIndexed { index, label ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = {}, label = { Text(label) })
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("模块化自动化平台", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text("自动化 ${automations.size} · 流程 $flowCount · 已注册 $featureCount 个功能")
                Text("导入：${importerNames.joinToString()}", style = MaterialTheme.typography.bodySmall)
            }

            item {
                if (tab == 0) {
                Button(onClick = onNewAutomation, modifier = Modifier.fillMaxWidth()) { Text("+ 新建自动化") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("导入 MacroDroid / ShortX / Tasker") }
                }
                if (tab == 1) Button(onClick = onNewFlow, modifier = Modifier.fillMaxWidth()) { Text("+ 新建流程") }
                if (tab == 2) Button(onClick = onEditVariables, modifier = Modifier.fillMaxWidth()) { Text("管理全局变量") }
                if (tab == 3) Button(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) { Text("打开诊断中心") }
            }

            importSummary?.let { summary ->
                item { AssistChip(onClick = {}, label = { Text(summary) }) }
            }
            runtimeSummary?.let { summary ->
                item { AssistChip(onClick = {}, label = { Text(summary) }) }
            }

            if (tab == 1) {
                if (flows.isEmpty()) item { Text("还没有流程。创建后可在动作中调用，也可编辑导入的流程。") }
                items(flows, key = { it.id.value }) { flow ->
                    Card(Modifier.fillMaxWidth().clickable { onEditFlow(flow) }) {
                        Column(Modifier.padding(16.dp)) {
                            Text(flow.name, style = MaterialTheme.typography.titleMedium)
                            Text("输入 ${flow.inputs.size} · 输出 ${flow.outputs.size} · 动作 ${flow.actions.size}")
                            Row { TextButton(onClick = { onEditFlow(flow) }) { Text("编辑") }; TextButton(onClick = { pendingFlowDelete = flow }) { Text("删除") } }
                        }
                    }
                }
            }
            if (tab == 0) {
            if (automations.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("还没有自动化", style = MaterialTheme.typography.titleMedium)
                            Text("可以新建规则，或者直接导入 MacroDroid、ShortX、Tasker。", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            } else {
                items(automations, key = { it.id.value }) { automation ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onEditAutomation(automation) },
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(automation.name, style = MaterialTheme.typography.titleMedium)
                                    automation.description?.takeIf { it.isNotBlank() }?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                Switch(
                                    checked = automation.enabled,
                                    onCheckedChange = { onToggleAutomation(automation, it) },
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AssistChip(
                                    onClick = {},
                                    label = { Text("事件 ${automation.activation.events.size}") },
                                )
                                AssistChip(
                                    onClick = {},
                                    label = { Text("状态 ${automation.activation.states.size}") },
                                )
                                AssistChip(
                                    onClick = {},
                                    label = { Text("动作 ${automation.onEnter.size + automation.onEvent.size + automation.onExit.size}") },
                                )
                            }
                            automation.source?.let { source ->
                                Text("导入：${source.importerId}${source.sourceType?.let { " · $it" }.orEmpty()}", style = MaterialTheme.typography.labelSmall)
                            }
                            Row {
                                TextButton(onClick = { onEditAutomation(automation) }) { Text("编辑") }
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { pendingDelete = automation }) { Text("删除") }
                            }
                        }
                    }
                }
            }
            }

            item {
                HorizontalDivider()
                OutlinedButton(onClick = onRunManual, modifier = Modifier.fillMaxWidth()) { Text("发送手动测试事件") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) { Text("诊断中心") }
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("运行权限与后端") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onBackup, modifier = Modifier.weight(1f)) { Text("备份") }
                    OutlinedButton(onClick = onRestore, modifier = Modifier.weight(1f)) { Text("恢复") }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    pendingDelete?.let { automation ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除自动化？") },
            text = { Text("“${automation.name}”将从 YAuto Workspace 中删除。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDeleteAutomation(automation)
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
    pendingFlowDelete?.let { flow -> AlertDialog(onDismissRequest = { pendingFlowDelete = null }, title = { Text("删除流程？") },
        text = { Text("删除“${flow.name}”。被规则引用的流程需要先移除调用。") },
        confirmButton = { TextButton(onClick = { onDeleteFlow(flow); pendingFlowDelete = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { pendingFlowDelete = null }) { Text("取消") } }) }
}
