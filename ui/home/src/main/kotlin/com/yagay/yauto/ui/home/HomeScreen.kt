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
import androidx.compose.runtime.saveable.rememberSaveable
import com.yagay.yauto.ui.design.PageBackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
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
    onTestSavedAutomation: (String) -> Unit,
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
    var tab by rememberSaveable { mutableStateOf(HomeTab.HOME) }
    var automationToDelete by remember { mutableStateOf<Automation?>(null) }
    var flowToDelete by remember { mutableStateOf<Flow?>(null) }
    var showTestCenter by rememberSaveable { mutableStateOf(false) }
    var pendingAutomationTest by rememberSaveable { mutableStateOf<String?>(null) }

    // The previous visited tab is a destination, not an alias for the dashboard.
    PageBackHandler(
        enabled = tab != HomeTab.HOME && automationToDelete == null && flowToDelete == null,
    ) {
        tab = HomeTab.HOME
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold)
                        Text(
                            localizedList(
                                listOf(
                                    pluralStringResource(R.plurals.count_automation, automations.size, automations.size),
                                    pluralStringResource(R.plurals.count_flow, flowCount, flowCount),
                                    pluralStringResource(R.plurals.count_feature, featureCount, featureCount),
                                )
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onNewAutomation) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(R.drawable.ic_add),
                            contentDescription = stringResource(R.string.icon_add),
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                HomeTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = {
                            Icon(
                                painter = androidx.compose.ui.res.painterResource(tabIcon(item)),
                                contentDescription = null,
                            )
                        },
                        label = { Text(tabLabel(item)) },
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
                onManual = { showTestCenter = true },
                onShowAutomations = { tab = HomeTab.AUTOMATIONS },
                onShowFlows = { tab = HomeTab.FLOWS },
            )
            HomeTab.AUTOMATIONS -> AutomationList(
                modifier = Modifier.padding(padding),
                automations = automations,
                onNew = onNewAutomation,
                onEdit = onEditAutomation,
                onToggle = onToggleAutomation,
                onDelete = { automationToDelete = it },
            )
            HomeTab.FLOWS -> FlowList(
                modifier = Modifier.padding(padding),
                flows = flows,
                onNew = onNewFlow,
                onEdit = onEditFlow,
                onDelete = { flowToDelete = it },
            )
            HomeTab.SETTINGS -> SettingsPage(
                modifier = Modifier.padding(padding),
                importerNames = importerNames,
                onImport = onImport,
                onVariables = onEditVariables,
                onDiagnostics = onOpenDiagnostics,
                onSettings = onOpenSettings,
                onBackup = onBackup,
                onRestore = onRestore,
                onManual = { showTestCenter = true },
            )
        }
    }

    HomeTestCenterDialogs(
        visible = showTestCenter,
        pendingAutomationId = pendingAutomationTest,
        automations = automations,
        onDismiss = { showTestCenter = false },
        onChooseAutomation = { id ->
            showTestCenter = false
            pendingAutomationTest = id
        },
        onDismissConfirmation = { pendingAutomationTest = null },
        onTestAutomation = { id ->
            pendingAutomationTest = null
            onTestSavedAutomation(id)
        },
        onRunManual = {
            showTestCenter = false
            onRunManual()
        },
        onOpenDiagnostics = {
            showTestCenter = false
            onOpenDiagnostics()
        },
        onOpenSettings = {
            showTestCenter = false
            onOpenSettings()
        },
    )

    automationToDelete?.let { automation ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.home_delete_automation_title),
            message = stringResource(R.string.home_delete_automation_message, automation.name),
            onDismiss = { automationToDelete = null },
            onConfirm = {
                onDeleteAutomation(automation)
                automationToDelete = null
            },
        )
    }
    flowToDelete?.let { flow ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.home_delete_flow_title),
            message = stringResource(R.string.home_delete_flow_message, flow.name),
            onDismiss = { flowToDelete = null },
            onConfirm = {
                onDeleteFlow(flow)
                flowToDelete = null
            },
        )
    }
}

@Composable
private fun ConfirmDeleteDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
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
    val tiles = buildList {
        add(HomeTile(stringResource(R.string.home_tile_add_automation), stringResource(R.string.home_tile_add_automation_subtitle), MacroPalette.Trigger, onNewAutomation))
        add(HomeTile(stringResource(R.string.home_tile_automations), pluralStringResource(R.plurals.count_rule, automations.size, automations.size), MacroPalette.Action, onShowAutomations))
        add(HomeTile(stringResource(R.string.home_tile_flows), pluralStringResource(R.plurals.count_reusable_flow, flows.size, flows.size), MacroPalette.Flow, onShowFlows))
        if (importerNames.isNotEmpty()) {
            add(HomeTile(stringResource(R.string.home_tile_import_export), localizedList(importerNames), MacroPalette.Utility, onImport))
        }
        add(HomeTile(stringResource(R.string.home_tile_variables), stringResource(R.string.home_tile_variables_subtitle), MacroPalette.Variable, onVariables))
        add(HomeTile(stringResource(R.string.home_tile_logs), stringResource(R.string.home_tile_logs_subtitle), MacroPalette.Diagnostics, onDiagnostics))
        add(HomeTile(stringResource(R.string.home_tile_permissions_backends), stringResource(R.string.home_tile_permissions_backends_subtitle), MacroPalette.State, onSettings))
        add(HomeTile(stringResource(R.string.home_tile_backup), stringResource(R.string.home_tile_backup_subtitle), MacroPalette.Utility, onBackup))
        add(HomeTile(stringResource(R.string.home_tile_restore), stringResource(R.string.home_tile_restore_subtitle), MacroPalette.Utility, onRestore))
        add(HomeTile(stringResource(R.string.home_tile_manual_run), stringResource(R.string.home_tile_manual_run_subtitle), MacroPalette.Action, onManual))
    }

    Column(modifier.fillMaxSize()) {
        if (importSummary != null || runtimeSummary != null) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
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
                    Column(
                        Modifier.fillMaxSize().padding(10.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(R.drawable.ic_info),
                            contentDescription = stringResource(R.string.icon_information),
                        )
                        Column {
                            Text(stringResource(R.string.home_capability_overview), fontWeight = FontWeight.SemiBold)
                            Text(
                                pluralStringResource(R.plurals.count_feature, featureCount, featureCount),
                                style = MaterialTheme.typography.labelSmall,
                            )
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
        item {
            Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_add_automation_button))
            }
        }
        if (automations.isEmpty()) {
            item { EmptyCard(stringResource(R.string.home_no_automations_title), stringResource(R.string.home_no_automations_message)) }
        }
        items(automations, key = { it.id.value }) { automation ->
            Card(Modifier.fillMaxWidth().clickable { onEdit(automation) }) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(automation.name, fontWeight = FontWeight.SemiBold)
                            automation.description?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Switch(automation.enabled, { onToggle(automation, it) })
                    }
                    AutomationStats(automation)
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
private fun AutomationStats(automation: Automation) {
    val triggerCount = automation.activation.events.size
    val stateCount = automation.activation.states.size
    val actionCount = automation.onEnter.size + automation.onEvent.size + automation.onExit.size
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistChip(onClick = {}, label = { Text(pluralStringResource(R.plurals.count_trigger, triggerCount, triggerCount)) })
        AssistChip(onClick = {}, label = { Text(pluralStringResource(R.plurals.count_state, stateCount, stateCount)) })
        AssistChip(onClick = {}, label = { Text(pluralStringResource(R.plurals.count_action, actionCount, actionCount)) })
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
        item {
            Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_add_flow_button))
            }
        }
        if (flows.isEmpty()) {
            item { EmptyCard(stringResource(R.string.home_no_flows_title), stringResource(R.string.home_no_flows_message)) }
        }
        items(flows, key = { it.id.value }) { flow ->
            Card(Modifier.fillMaxWidth().clickable { onEdit(flow) }) {
                Column(Modifier.padding(12.dp)) {
                    Text(flow.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        localizedList(
                            listOf(
                                pluralStringResource(R.plurals.count_input, flow.inputs.size, flow.inputs.size),
                                pluralStringResource(R.plurals.count_output, flow.outputs.size, flow.outputs.size),
                                pluralStringResource(R.plurals.count_action, flow.actions.size, flow.actions.size),
                            )
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
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
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { SettingsRow(stringResource(R.string.settings_runtime_permissions_backends), stringResource(R.string.settings_runtime_permissions_backends_subtitle), onSettings) }
        item { SettingsRow(stringResource(R.string.settings_global_variables), stringResource(R.string.settings_global_variables_subtitle), onVariables) }
        item { SettingsRow(stringResource(R.string.settings_diagnostics), stringResource(R.string.settings_diagnostics_subtitle), onDiagnostics) }
        if (importerNames.isNotEmpty()) {
            item { SettingsRow(stringResource(R.string.settings_import_automation), localizedList(importerNames), onImport) }
        }
        item { SettingsRow(stringResource(R.string.settings_backup_workspace), stringResource(R.string.settings_backup_workspace_subtitle), onBackup) }
        item { SettingsRow(stringResource(R.string.settings_restore_workspace), stringResource(R.string.settings_restore_workspace_subtitle), onRestore) }
        item { SettingsRow(stringResource(R.string.test_center_title), stringResource(R.string.test_center_settings_subtitle), onManual) }
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Icon(
                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_chevron_right),
                contentDescription = stringResource(R.string.icon_open_details),
            )
        },
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

@Composable
private fun tabLabel(tab: HomeTab): String = when (tab) {
    HomeTab.HOME -> stringResource(R.string.home_tab_home)
    HomeTab.AUTOMATIONS -> stringResource(R.string.home_tab_automations)
    HomeTab.FLOWS -> stringResource(R.string.home_tab_flows)
    HomeTab.SETTINGS -> stringResource(R.string.home_tab_settings)
}

@androidx.annotation.DrawableRes
private fun tabIcon(tab: HomeTab): Int = when (tab) {
    HomeTab.HOME -> R.drawable.ic_home
    HomeTab.AUTOMATIONS -> R.drawable.ic_automations
    HomeTab.FLOWS -> R.drawable.ic_flows
    HomeTab.SETTINGS -> R.drawable.ic_settings
}
