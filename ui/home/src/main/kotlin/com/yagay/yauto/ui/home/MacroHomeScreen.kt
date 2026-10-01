package com.yagay.yauto.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.model.Automation
import com.yagay.yauto.core.model.Flow
import com.yagay.yauto.ui.design.*

private enum class HomeTab(val label: String) {
    HOME("首页"), AUTOMATIONS("自动化"), FLOWS("流程"), SETTINGS("设置")
}

private data class HomeTile(
    val title: String,
    val subtitle: String,
    val color: androidx.compose.ui.graphics.Color,
    val action: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacroHomeScreen(
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
    var tab by remember { mutableStateOf(HomeTab.HOME) }
    var pendingAutomationDelete by remember { mutableStateOf<Automation?>(null) }
    var pendingFlowDelete by remember { mutableStateOf<Flow?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("YAuto", fontWeight = FontWeight.Bold)
                        Text(
                            "自动化 ${automations.size} · 流程 $flowCount · 功能 $featureCount",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onNewAutomation) { Text("＋") }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                HomeTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Text(tabGlyph(item)) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            HomeTab.HOME -> HomeDashboard(
                modifier = Modifier.padding(padding),
                featureCount = featureCount,
                automations = automations,
                flows = flows,
                importerNames = importerNames,
                importSummary = importSummary,
                runtimeSummary = runtimeSummary,
                onNewAutomation = onNewAutomation,
                onNewFlow = onNewFlow,
                onImport = onImport,
                onVariables = onEditVariables,
                onDiagnostics = onOpenDiagnostics,
                onSettings = onOpenSettings,
                onBackup = onBackup,
                onRestore = onRestore,
                onManual = onRunManual,
                onShowAutomations = { tab = HomeTab.AUTOMATIONS },
                onShowFlows = { tab = HomeTab.FLOWS },
            )
            HomeTab.AUTOMATIONS -> AutomationList(
                Modifier.padding(padding),
                automations,
                onNewAutomation,
                onEditAutomation,
                onToggleAutomation,
                { pendingAutomationDelete = it },
            )
            HomeTab.FLOWS -> FlowList(
                Modifier.padding(padding),
                flows,
                onNewFlow,
                onEditFlow,
                { pendingFlowDelete = it },
            )
            HomeTab.SETTINGS -> SettingsPage(
                Modifier.padding(padding),
                importerNames,
                onImport,
                onEditVariables,
                onOpenDiagnostics,
                onOpenSettings,
                onBackup,
                onRestore,
                onRunManual,
            )
        }
    }

    pendingAutomationDelete?.let { automation ->
        AlertDialog(
            onDismissRequest = { pendingAutomationDelete = null },
            title = { Text("删除自动化？") },
            text = { Text("“${automation.name}”将从工作区删除。") },
            confirmButton = { TextButton(onClick = { onDeleteAutomation(automation); pendingAutomationDelete = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { pendingAutomationDelete = null }) { Text("取消") } },
        )
    }
    pendingFlowDelete?.let { flow ->
        AlertDialog(
            onDismissRequest = { pendingFlowDelete = null },
            title = { Text("删除流程？") },
            text = { Text("删除“${flow.name}”。仍被自动化引用时会被阻止。") },
            confirmButton = { TextButton(onClick = { onDeleteFlow(flow); pendingFlowDelete = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { pendingFlowDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun HomeDashboard(
    modifier: Modifier,
    featureCount: Int,
    automations: List<Automation>,
    flows: List<Flow>,
    importerNames: List<String>,
    importSummary: String?,
    runtimeSummary: String?,
    onNewAutomation: () -> Unit,
    onNewFlow: () -> Unit,
    onImport: () -> Unit,
    onVariables: () -> Unit,
    onDiagnostics: () -> Unit,
    onSettings: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onManual: () -> Unit,
    onShowAutomations: () -> Unit,
    onShowFlows: () -> Unit,
) {
    val tiles = listOf(
        HomeTile("添加自动化", "Trigger → Action → Constraint", MacroPalette.Trigger, onNewAutomation),
        HomeTile("自动化", "${automations.size} 条规则", MacroPalette.Action, onShowAutomations),
        HomeTile("流程 / 动作块", "${flows.size} 个可复用流程", MacroPalette.Flow, onShowFlows),
        HomeTile("导入 / 导出", importerNames.joinToString(" / "), MacroPalette.Utility, onImport),
        HomeTile("变量", "全局与局部数据", MacroPalette.Variable, onVariables),
        HomeTile("运行日志", "诊断与执行追踪", MacroPalette.Diagnostics, onDiagnostics),
        HomeTile("权限 / 后端", "Android · Shizuku · Root · LSPosed", MacroPalette.State, onSettings),
        HomeTile("备份", "导出完整工作区", MacroPalette.Utility, onBackup),
        HomeTile("恢复", "合并工作区备份", MacroPalette.Utility, onRestore),
        HomeTile("手动运行", "发送测试触发器", MacroPalette.Action, onManual),
    )

    Column(modifier.fillMaxSize()) {
        if (importSummary != null || runtimeSummary != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                importSummary?.let { AssistChip(onClick = {}, label = { Text(it) }) }
                runtimeSummary?.let { AssistChip(onClick = {}, label = { Text(it) }) }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(tiles, key = { it.title }) { tile ->
                MacroHomeTile(tile.title, tile.subtitle, tile.color, onClick = tile.action)
            }
            item {
                Card(Modifier.aspectRatio(1.08f)) {
                    Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Text("i", style = MaterialTheme.typography.headlineMedium)
                        Column {
                            Text("能力概览", fontWeight = FontWeight.SemiBold)
                            Text("$featureCount 个已注册功能", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AutomationList(
    modifier: Modifier,
    automations: List<Automation>,
    onNew: () -> Unit,
    onEdit: (Automation) -> Unit,
    onToggle: (Automation, Boolean) -> Unit,
    onDelete: (Automation) -> Unit,
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) { Text("＋ 添加自动化") } }
        if (automations.isEmpty()) item { EmptyCard("还没有自动化", "点击上方按钮新建，或在设置页导入 MacroDroid / Tasker / ShortX。") }
        items(automations, key = { it.id.value }) { automation ->
            Card(Modifier.fillMaxWidth().clickable { onEdit(automation) }) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(automation.name, fontWeight = FontWeight.SemiBold)
                            automation.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        Switch(automation.enabled, { onToggle(automation, it) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(onClick = {}, label = { Text("触发 ${automation.activation.events.size}") })
                        AssistChip(onClick = {}, label = { Text("状态 ${automation.activation.states.size}") })
                        AssistChip(onClick = {}, label = { Text("动作 ${automation.onEnter.size + automation.onEvent.size + automation.onExit.size}") })
                    }
                    Row {
                        TextButton(onClick = { onEdit(automation) }) { Text("编辑") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { onDelete(automation) }) { Text("删除") }
                    }
                }
            }
        }
    }
}

@Composable
private fun FlowList(
    modifier: Modifier,
    flows: List<Flow>,
    onNew: () -> Unit,
    onEdit: (Flow) -> Unit,
    onDelete: (Flow) -> Unit,
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) { Text("＋ 添加流程 / 动作块") } }
        if (flows.isEmpty()) item { EmptyCard("还没有流程", "流程相当于可复用的动作块，可被多个自动化调用。") }
        items(flows, key = { it.id.value }) { flow ->
            Card(Modifier.fillMaxWidth().clickable { onEdit(flow) }) {
                Column(Modifier.padding(12.dp)) {
                    Text(flow.name, fontWeight = FontWeight.SemiBold)
                    Text("输入 ${flow.inputs.size} · 输出 ${flow.outputs.size} · 动作 ${flow.actions.size}", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { onEdit(flow) }) { Text("编辑") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { onDelete(flow) }) { Text("删除") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(
    modifier: Modifier,
    importerNames: List<String>,
    onImport: () -> Unit,
    onVariables: () -> Unit,
    onDiagnostics: () -> Unit,
    onSettings: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onManual: () -> Unit,
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { SettingsRow("运行权限与后端", "Android / Accessibility / Shizuku / Root / LSPosed", onSettings) }
        item { SettingsRow("全局变量", "统一管理持久变量", onVariables) }
        item { SettingsRow("诊断中心", "执行日志、Root、LSPosed 与系统诊断", onDiagnostics) }
        item { SettingsRow("导入自动化", importerNames.joinToString(" / "), onImport) }
        item { SettingsRow("备份工作区", "自动化、流程、变量与未知兼容配置", onBackup) }
        item { SettingsRow("恢复工作区", "确认后合并备份", onRestore) }
        item { SettingsRow("发送手动测试事件", "用于调试 core.event.manual", onManual) }
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Text("›") },
        modifier = Modifier.clickable(onClick = onClick),
    )
    HorizontalDivider()
}

@Composable
private fun EmptyCard(title: String, subtitle: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun tabGlyph(tab: HomeTab): String = when (tab) {
    HomeTab.HOME -> "⌂"
    HomeTab.AUTOMATIONS -> "≡"
    HomeTab.FLOWS -> "↳"
    HomeTab.SETTINGS -> "⚙"
}
