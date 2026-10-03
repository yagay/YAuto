package com.yagay.yauto.ui.home

import androidx.compose.runtime.Composable
import com.yagay.yauto.core.model.Automation
import com.yagay.yauto.core.model.Flow

/** Public YAuto home entrypoint; the legacy implementation remains encapsulated in this module. */
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
) = MacroHomeScreen(
    featureCount = featureCount,
    automations = automations,
    flowCount = flowCount,
    importerNames = importerNames,
    importSummary = importSummary,
    runtimeSummary = runtimeSummary,
    onNewAutomation = onNewAutomation,
    onEditAutomation = onEditAutomation,
    onToggleAutomation = onToggleAutomation,
    onDeleteAutomation = onDeleteAutomation,
    onImport = onImport,
    onRunManual = onRunManual,
    onOpenDiagnostics = onOpenDiagnostics,
    flows = flows,
    onNewFlow = onNewFlow,
    onEditFlow = onEditFlow,
    onDeleteFlow = onDeleteFlow,
    onEditVariables = onEditVariables,
    onOpenSettings = onOpenSettings,
    onBackup = onBackup,
    onRestore = onRestore,
)
