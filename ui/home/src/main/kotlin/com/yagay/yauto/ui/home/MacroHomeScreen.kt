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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.model.Automation
import com.yagay.yauto.core.model.Flow
import com.yagay.yauto.ui.design.*
import com.yagay.yauto.ui.design.R

private enum class HomeTab { HOME, AUTOMATIONS, FLOWS, SETTINGS }

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
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.home_summary_format, automations.size, flowCount, featureCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = { TextButton(onClick = onNewAutomation) { Text("＋") } },
            )
        },
        bottomBar = {
            NavigationBar {
                HomeTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Text(tabGlyph(item)) },
                        label = { Text(tabLabel(item)) },
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            HomeTab.HOME -> HomeDashboard(
                modifier = Modifier.padding(padding), featureCount = featureCount, automations = automations,
                flows = flows, importerNames = importerNames, importSummary = importSummary,
                runtimeSummary = runtimeSummary, onNewAutomation = onNewAutomation, onNewFlow = onNewFlow,
                onImport = onImport, onVariables = onEditVariables, onDiagnostics = onOpenDiagnostics,
                onSettings = onOpenSettings, onBackup = onBackup, onRestore = onRestore, onManual = onRunManual,
                onShowAutomations = { tab = HomeTab.AUTOMATIONS }, onShowFlows = { tab = HomeTab.FLOWS },
            )
            HomeTab.AUTOMATIONS -> AutomationList(
                Modifier.padding(padding), automations, onNewAutomation, onEditAutomation,
                onToggleAutomation, { pendingAutomationDelete = it },
            )
            HomeTab.FLOWS -> FlowList(
                Modifier.padding(padding), flows, onNewFlow, onEditFlow, { pendingFlowDelete = it },
            )
            HomeTab.SETTINGS -> SettingsPage(
                Modifier.padding(padding), importerNames, onImport, onEditVariables, onOpenDiagnostics,
                onOpenSettings, onBackup, onRestore, onRunManual,
            )
        }
    }

    pendingAutomationDelete?.let { automation ->
        AlertDialog(
            onDismissRequest = { pendingAutomationDelete = null },
            title = { Text(stringResource(R.string.home_delete_automation_title)) },
            text = { Text(stringResource(R.string.home_delete_automation_message, automation.name)) },
            confirmButton = {
                TextButton(onClick = { onDeleteAutomation(automation); pendingAutomationDelete = null }) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAutomationDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
    pendingFlowDelete?.let { flow ->
        AlertDialog(
            onDismissRequest = { pendingFlowDelete = null },
            title = { Text(stringResource(R.string.home_delete_flow_title)) },
            text = { Text(stringResource(R.string.home_delete_flow_message, flow.name)) },
            confirmButton = {
                TextButton(onClick = { onDeleteFlow(flow); pendingFlowDelete = null }) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingFlowDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            },
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
        HomeTile(stringResource(R.string.home_tile_add_automation), stringResource(R.string.home_tile_add_automation_subtitle), MacroPalette.Trigger, onNewAutomation),
        HomeTile(stringResource(R.string.home_tile_automations), stringResource(R.string.home_tile_automations_subtitle, automations.size), MacroPalette.Action, onShowAutomations),
        HomeTile(stringResource(R.string.home_tile_flows), stringResource(R.string.home_tile_flows_subtitle, flows.size), MacroPalette.Flow, onShowFlows),
        HomeTile(stringResource(R.string.home_tile_import_export), importerNames.joinToString(" / "), MacroPalette.Utility, onImport),
        HomeTile(stringResource(R.string.home_tile_variables), stringResource(R.string.home_tile_variables_subtitle), MacroPalette.Variable, onVariables),
        HomeTile(stringResource(R.string.home_tile_logs), stringResource(R.string.home_tile_logs_subtitle), MacroPalette.Diagnostics, onDiagnostics),
        HomeTile(stringResource(R.string.home_tile_permissions_backends), stringResource(R.string.home_tile_permissions_backends_subtitle), MacroPalette.State, onSettings),
        HomeTile(stringResource(R.string.home_tile_backup), stringResource(R.string.home_tile_backup_subtitle), MacroPalette.Utility, onBackup),
        HomeTile(stringResource(R.string.home_tile_restore), stringResource(R.string.home_tile_restore_subtitle), MacroPalette.Utility, onRestore),
        HomeTile(stringResource(R.string.home_tile_manual_run), stringResource(R.string.home_tile_manual_run_subtitle), MacroPalette.Action, onManual),
    )

    Column(modifier.fillMaxSize()) {
        if (importSummary != null || runtimeSummary != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                importSummary?.let { AssistChip(onClick = {}, label = { Text(it) }) }
                runtimeSummary?.let { AssistChip(onClick = {}, label = { Text(it) }) }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3), modifier = Modifier.weight(1f), contentPadding = PaddingValues(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(tiles, key = { it.title }) { tile -> MacroHomeTile(tile.title, tile.subtitle, tile.color, onClick = tile.action) }
            item {
                Card(Modifier.aspectRatio(1.08f)) {
                    Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Text("i", style = MaterialTheme.typography.headlineMedium)
                        Column {
                            Text(stringResource(R.string.home_capability_overview), fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.home_registered_features_format, featureCount), style = MaterialTheme.typography.labelSmall)
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
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.home_add_automation_button)) } }
        if (automations.isEmpty()) item {
            EmptyCard(stringResource(R.string.home_no_automations_title), stringResource(R.string.home_no_automations_message))
        }
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
                        AssistChip(onClick = {}, label = { Text(stringResource(R.string.home_trigger_count_format, automation.activation.events.size)) })
                        AssistChip(onClick = {}, label = { Text(stringResource(R.string.home_state_count_format, automation.activation.states.size)) })
                        AssistChip(onClick = {}, label = { Text(stringResource(R.string.home_action_count_format, automation.onEnter.size + automation.onEvent.size + automation.onExit.size)) })
                    }
                    Row {
                        TextButton(onClick = { onEdit(automation) }) { Text(stringResource(R.string.common_edit)) }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { onDelete(automation) }) { Text(stringResource(R.string.common_delete)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FlowList(modifier: Modifier, flows: List<Flow>, onNew: () -> Unit, onEdit: (Flow) -> Unit, onDelete: (Flow) -> Unit) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.home_add_flow_button)) } }
        if (flows.isEmpty()) item { EmptyCard(stringResource(R.string.home_no_flows_title), stringResource(R.string.home_no_flows_message)) }
        items(flows, key = { it.id.value }) { flow ->
            Card(Modifier.fillMaxWidth().clickable { onEdit(flow) }) {
                Column(Modifier.padding(12.dp)) {
                    Text(flow.name, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.home_flow_summary_format, flow.inputs.size, flow.outputs.size, flow.actions.size), style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { onEdit(flow) }) { Text(stringResource(R.string.common_edit)) }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { onDelete(flow) }) { Text(stringResource(R.string.common_delete)) }
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
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SettingsRow(stringResource(R.string.settings_runtime_permissions_backends), stringResource(R.string.settings_runtime_permissions_backends_subtitle), onSettings) }
        item { SettingsRow(stringResource(R.string.settings_global_variables), stringResource(R.string.settings_global_variables_subtitle), onVariables) }
        item { SettingsRow(stringResource(R.string.settings_diagnostics), stringResource(R.string.settings_diagnostics_subtitle), onDiagnostics) }
        item { SettingsRow(stringResource(R.string.settings_import_automation), importerNames.joinToString(" / "), onImport) }
        item { SettingsRow(stringResource(R.string.settings_backup_workspace), stringResource(R.string.settings_backup_workspace_subtitle), onBackup) }
        item { SettingsRow(stringResource(R.string.settings_restore_workspace), stringResource(R.string.settings_restore_workspace_subtitle), onRestore) }
        item { SettingsRow(stringResource(R.string.settings_manual_test_event), stringResource(R.string.settings_manual_test_event_subtitle), onManual) }
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(title, fontWeight = FontWeight.Medium) }, supportingContent = { Text(subtitle) }, trailingContent = { Text("›") }, modifier = Modifier.clickable(onClick = onClick))
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

@Composable
private fun tabLabel(tab: HomeTab): String = when (tab) {
    HomeTab.HOME -> stringResource(R.string.home_tab_home)
    HomeTab.AUTOMATIONS -> stringResource(R.string.home_tab_automations)
    HomeTab.FLOWS -> stringResource(R.string.home_tab_flows)
    HomeTab.SETTINGS -> stringResource(R.string.home_tab_settings)
}

private fun tabGlyph(tab: HomeTab): String = when (tab) {
    HomeTab.HOME -> "⌂"
    HomeTab.AUTOMATIONS -> "≡"
    HomeTab.FLOWS -> "↳"
    HomeTab.SETTINGS -> "⚙"
}
