package com.yagay.yauto

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.diagnostics.CollectorStatus
import com.yagay.yauto.core.diagnostics.DiagnosticSnapshot
import com.yagay.yauto.core.importer.ImportInput
import com.yagay.yauto.core.model.Automation
import com.yagay.yauto.core.model.AutomationId
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.Flow
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.storage.WorkspaceBackup
import com.yagay.yauto.core.storage.WorkspaceBackupCodec
import com.yagay.yauto.core.storage.WorkspaceData
import com.yagay.yauto.core.storage.featureIds
import com.yagay.yauto.core.storage.merge
import com.yagay.yauto.core.storage.references
import com.yagay.yauto.ui.design.R as TextR
import com.yagay.yauto.ui.diagnostics.DiagnosticsScreen
import com.yagay.yauto.ui.editor.AutomationEditorScreen
import com.yagay.yauto.ui.editor.FlowEditorScreen
import com.yagay.yauto.ui.editor.GlobalVariablesScreen
import com.yagay.yauto.ui.editor.FeatureAvailabilityTone
import com.yagay.yauto.ui.editor.FeatureAvailabilityUi
import com.yagay.yauto.ui.editor.LocalFeatureAvailability
import com.yagay.yauto.ui.editor.LocalEditorVariableNames
import com.yagay.yauto.ui.editor.LocalHardwareKeyCatalogLoader
import com.yagay.yauto.ui.editor.LocalHardwareKeyCapture
import com.yagay.yauto.platform.accessibility.AccessibilityRuntimeBridge
import com.yagay.yauto.platform.xposed.XposedSystemEventRuntimeBridge
import com.yagay.yauto.core.registry.HardwareKeyCaptureResult
import com.yagay.yauto.ui.home.HomeScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

private enum class AppPage {
    HOME,
    AUTOMATION,
    FLOW,
    VARIABLES,
    SETTINGS,
    DIAGNOSTICS,
}

@Composable
internal fun YAutoAppScreen(graph: AppGraph) {
    val context = LocalContext.current
    var page by remember { mutableStateOf(AppPage.HOME) }
    var editingAutomation by remember { mutableStateOf<Automation?>(null) }
    var editingFlow by remember { mutableStateOf<Flow?>(null) }
    var workspaceReady by remember { mutableStateOf(false) }
    var workspaceSaving by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<WorkspaceBackup?>(null) }
    var backupText by remember { mutableStateOf<String?>(null) }
    var statuses by remember { mutableStateOf<List<CollectorStatus>>(emptyList()) }
    var diagnosticSnapshot by remember { mutableStateOf<DiagnosticSnapshot?>(null) }
    var collecting by remember { mutableStateOf(false) }
    var workspace by remember { mutableStateOf(WorkspaceData()) }
    var importSummary by remember { mutableStateOf<String?>(null) }
    var runtimeSummary by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val saveLock = remember { Mutex() }
    val featureDescriptors = remember(graph) { graph.features.allDescriptors() }
    val featureHealthSnapshot by graph.featureHealth.snapshot.collectAsState()
    val featureAvailability = remember(featureHealthSnapshot, context) {
        featureHealthSnapshot?.items.orEmpty().associate { item ->
            item.featureId to FeatureAvailabilityUi(
                statusLabel = featureHealthStatusLabel(context, item.status),
                summary = featureHealthListSummary(context, item),
                tone = when (item.status) {
                    FeatureHealthStatus.READY -> FeatureAvailabilityTone.READY
                    FeatureHealthStatus.BLOCKED -> FeatureAvailabilityTone.BLOCKED
                    FeatureHealthStatus.BROKEN -> FeatureAvailabilityTone.BROKEN
                    FeatureHealthStatus.UNSUPPORTED -> FeatureAvailabilityTone.UNSUPPORTED
                },
            )
        }
    }

    BackHandler(enabled = page != AppPage.HOME) {
        when (page) {
            AppPage.AUTOMATION -> editingAutomation = null
            AppPage.FLOW -> editingFlow = null
            else -> Unit
        }
        page = AppPage.HOME
    }

    fun operation(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                importSummary = context.getString(
                    TextR.string.main_operation_failed_format,
                    error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            workspace = graph.workspace.load()
            workspaceReady = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            importSummary = context.getString(
                TextR.string.main_workspace_load_failed_format,
                error.message ?: error.javaClass.simpleName,
            )
        }
    }

    fun saveWorkspace(
        updated: WorkspaceData,
        resetId: AutomationId? = null,
        onSaved: () -> Unit = {},
    ) {
        if (workspaceSaving) return
        workspaceSaving = true
        operation {
            try {
                saveLock.withLock {
                    graph.workspace.save(updated)
                    workspace = updated
                }
                onSaved()
                resetId?.let(graph.runtime::resetState) ?: graph.runtime.resetState()
                graph.runtime.dispatch(
                    RuntimeEvent("android.event.workspace_changed", source = "ui"),
                    statesOnly = true,
                )
            } finally {
                workspaceSaving = false
            }
        }
    }

    val diagnosticsExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val snapshot = diagnosticSnapshot
        if (uri != null && snapshot != null) operation {
            withContext(Dispatchers.IO) {
                val text = Json { prettyPrint = true; encodeDefaults = true }
                    .encodeToString(DiagnosticSnapshot.serializer(), snapshot)
                context.contentResolver.openOutputStream(uri, "wt")
                    ?.bufferedWriter()
                    ?.use { it.write(text) }
            }
        }
    }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val text = backupText
        if (uri != null && text != null) operation {
            withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri, "wt"))
                    .bufferedWriter()
                    .use { it.write(text) }
            }
            importSummary = context.getString(TextR.string.main_backup_saved)
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) operation {
            pendingRestore = withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                    val message = context.getString(TextR.string.main_backup_too_large)
                    val bytes = input.readBoundedBytes(MAX_WORKSPACE_FILE_BYTES, message)
                    WorkspaceBackupCodec.decode(bytes.toString(Charsets.UTF_8))
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) operation {
            val importer = withContext(Dispatchers.IO) {
                val message = context.getString(TextR.string.main_import_too_large)
                val bytes = context.contentResolver.openInputStream(uri)
                    ?.use { it.readBoundedBytes(MAX_WORKSPACE_FILE_BYTES, message) }
                    ?: byteArrayOf()
                val input = ImportInput(uri.lastPathSegment, context.contentResolver.getType(uri), bytes)
                graph.importers.bestFor(input) to input
            }
            val result = withContext(Dispatchers.IO) {
                importer.first?.import(importer.second) ?: graph.importers.import(importer.second)
            }
            graph.importReports.save(result)
            if (result.success) {
                val updated = workspace.merge(
                    result.bundle.automations,
                    result.bundle.flows,
                    result.bundle.globalVariables,
                )
                saveWorkspace(updated) {
                    val importerName = importer.first?.displayName ?: result.importerId
                    importSummary = context.getString(
                        TextR.string.main_import_summary_format,
                        importerName,
                        result.bundle.automations.size,
                        result.bundle.flows.size,
                        result.issues.size,
                    )
                }
            } else {
                importSummary = context.getString(
                    TextR.string.main_import_failed_format,
                    result.issues.firstOrNull()?.message
                        ?: context.getString(TextR.string.main_unknown_format),
                )
            }
        }
    }

    if (!workspaceReady) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text(importSummary ?: context.getString(TextR.string.main_loading_workspace))
            Button(onClick = {
                operation {
                    workspace = graph.workspace.load()
                    workspaceReady = true
                    importSummary = null
                }
            }) {
                Text(context.getString(TextR.string.common_retry))
            }
        }
        return
    }

    pendingRestore?.let { backup ->
        val unknown = backup.workspace.featureIds().filter { graph.features.descriptor(it) == null }
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text(context.getString(TextR.string.main_restore_backup_title)) },
            text = {
                Text(
                    context.getString(
                        TextR.string.main_restore_backup_message,
                        backup.workspace.automations.size,
                        backup.workspace.flows.size,
                        unknown.size,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    saveWorkspace(
                        workspace.merge(
                            backup.workspace.automations,
                            backup.workspace.flows,
                            backup.workspace.globalVariables,
                            backup.workspace.persistentVariables,
                        )
                    ) {
                        importSummary = context.getString(TextR.string.main_backup_restored)
                    }
                    pendingRestore = null
                }) {
                    Text(context.getString(TextR.string.common_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) {
                    Text(context.getString(TextR.string.common_cancel))
                }
            },
        )
    }

    if (workspaceSaving) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(context.getString(TextR.string.main_saving)) },
            text = { CircularProgressIndicator() },
        )
    }

    when (page) {
        AppPage.AUTOMATION -> CompositionLocalProvider(
            LocalFeatureAvailability provides featureAvailability,
            LocalEditorVariableNames provides (workspace.globalVariables.keys + workspace.persistentVariables.keys),
            LocalHardwareKeyCatalogLoader provides { graph.hardwareKeys.load() },
            LocalHardwareKeyCapture provides { timeoutMs -> captureHardwareKey(graph, timeoutMs) },
        ) {
            AutomationEditorScreen(
            flows = workspace.flows,
            descriptors = featureDescriptors,
            initial = editingAutomation,
            onSave = { automation ->
                val exists = workspace.automations.any { it.id == automation.id }
                val automations = if (exists) {
                    workspace.automations.map { if (it.id == automation.id) automation else it }
                } else {
                    workspace.automations + automation
                }
                saveWorkspace(workspace.copy(automations = automations), automation.id)
                editingAutomation = null
                page = AppPage.HOME
            },
            onBack = {
                editingAutomation = null
                page = AppPage.HOME
            },
            )
        }

        AppPage.FLOW -> CompositionLocalProvider(
            LocalFeatureAvailability provides featureAvailability,
            LocalEditorVariableNames provides (workspace.globalVariables.keys + workspace.persistentVariables.keys),
            LocalHardwareKeyCatalogLoader provides { graph.hardwareKeys.load() },
            LocalHardwareKeyCapture provides { timeoutMs -> captureHardwareKey(graph, timeoutMs) },
        ) {
            FlowEditorScreen(
            initial = editingFlow,
            descriptors = featureDescriptors,
            flows = workspace.flows,
            onSave = { flow ->
                saveWorkspace(
                    workspace.copy(flows = workspace.flows.filterNot { it.id == flow.id } + flow)
                )
                editingFlow = null
                page = AppPage.HOME
            },
            onBack = {
                editingFlow = null
                page = AppPage.HOME
            },
            )
        }

        AppPage.VARIABLES -> GlobalVariablesScreen(
            workspace.globalVariables,
            onSave = {
                saveWorkspace(workspace.copy(globalVariables = it))
                page = AppPage.HOME
            },
            onBack = { page = AppPage.HOME },
        )

        AppPage.SETTINGS -> RuntimeSettingsScreen(graph, onBack = { page = AppPage.HOME })

        AppPage.DIAGNOSTICS -> DiagnosticsScreen(
            statuses = statuses,
            snapshot = diagnosticSnapshot,
            collecting = collecting,
            onCollect = {
                operation {
                    collecting = true
                    try {
                        statuses = graph.diagnosticRegistry.all().map { it.status() }
                        diagnosticSnapshot = graph.diagnostics.snapshot()
                    } finally {
                        collecting = false
                    }
                }
            },
            onExport = { diagnosticsExportLauncher.launch("YAuto-diagnostic.json") },
            onBack = { page = AppPage.HOME },
        )

        AppPage.HOME -> HomeScreen(
            flows = workspace.flows,
            onNewFlow = {
                editingFlow = null
                page = AppPage.FLOW
            },
            onEditFlow = {
                editingFlow = it
                page = AppPage.FLOW
            },
            onDeleteFlow = { flow ->
                if (workspace.references(flow.id)) {
                    importSummary = context.getString(TextR.string.main_flow_still_referenced)
                } else {
                    saveWorkspace(workspace.copy(flows = workspace.flows.filterNot { it.id == flow.id }))
                }
            },
            onEditVariables = { page = AppPage.VARIABLES },
            onOpenSettings = { page = AppPage.SETTINGS },
            onBackup = {
                backupText = WorkspaceBackupCodec.encode(workspace, "0.1.0")
                backupLauncher.launch("YAuto-backup.json")
            },
            onRestore = { restoreLauncher.launch(arrayOf("application/json", "*/*")) },
            featureCount = featureDescriptors.size,
            automations = workspace.automations,
            flowCount = workspace.flows.size,
            importerNames = graph.importers.all().map { it.displayName },
            importSummary = importSummary,
            runtimeSummary = runtimeSummary,
            onNewAutomation = {
                editingAutomation = null
                page = AppPage.AUTOMATION
            },
            onEditAutomation = { automation ->
                editingAutomation = automation
                page = AppPage.AUTOMATION
            },
            onToggleAutomation = { automation, enabled ->
                val updated = workspace.copy(
                    automations = workspace.automations.map {
                        if (it.id == automation.id) it.copy(enabled = enabled) else it
                    }
                )
                saveWorkspace(updated, automation.id)
            },
            onDeleteAutomation = { automation ->
                val updated = workspace.copy(
                    automations = workspace.automations.filterNot { it.id == automation.id }
                )
                saveWorkspace(updated, automation.id)
            },
            onImport = {
                importLauncher.launch(
                    arrayOf(
                        "application/json",
                        "text/xml",
                        "application/xml",
                        "application/octet-stream",
                        "*/*",
                    )
                )
            },
            onRunManual = {
                operation {
                    val result = graph.runtime.dispatch(
                        RuntimeEvent(
                            typeId = "core.event.manual",
                            payload = mapOf("name" to ConfigValue.StringValue("home-test")),
                            source = "ui",
                        )
                    )
                    runtimeSummary = context.getString(
                        TextR.string.main_runtime_summary_format,
                        result.runs.size,
                    )
                }
            },
            onOpenDiagnostics = {
                page = AppPage.DIAGNOSTICS
                operation {
                    statuses = graph.diagnosticRegistry.all().map { it.status() }
                }
            },
        )
    }
}


private suspend fun captureHardwareKey(
    graph: AppGraph,
    timeoutMs: Long,
): HardwareKeyCaptureResult? = coroutineScope {
    val channel = Channel<HardwareKeyCaptureResult>(capacity = 3)
    val jobs = mutableListOf<kotlinx.coroutines.Job>()

    fun launchCapture(block: suspend () -> HardwareKeyCaptureResult?) {
        jobs += launch(start = CoroutineStart.UNDISPATCHED) {
            block()?.let { channel.trySend(it) }
        }
    }

    launchCapture {
        AccessibilityRuntimeBridge.awaitNextKey(timeoutMs)?.let { key ->
            HardwareKeyCaptureResult(
                keyCode = key.keyCode,
                scanCode = key.scanCode,
                deviceId = key.deviceId,
                action = key.action,
                deviceName = key.deviceName,
                deviceDescriptor = key.deviceDescriptor,
                vendorId = key.vendorId,
                productId = key.productId,
                sources = setOf("accessibility"),
            )
        }
    }

    val xposedWaiter = launch(start = CoroutineStart.UNDISPATCHED) {
        XposedSystemEventRuntimeBridge.awaitNextHardwareKey(timeoutMs)?.let { key ->
            channel.trySend(
                HardwareKeyCaptureResult(
                    keyCode = key.keyCode,
                    scanCode = key.scanCode,
                    deviceId = key.deviceId,
                    action = key.action,
                    deviceName = key.deviceName,
                    deviceDescriptor = key.deviceDescriptor,
                    vendorId = key.vendorId,
                    productId = key.productId,
                    sources = setOf("lsposed"),
                )
            )
        }
    }
    jobs += xposedWaiter

    val xposedArmed = runCatching {
        graph.xposed.beginHardwareKeyCapture(timeoutMs)
    }.getOrDefault(false)
    if (!xposedArmed) xposedWaiter.cancel()

    if (runCatching { graph.rootShell.isAvailable() }.getOrDefault(false)) {
        launchCapture { graph.hardwareKeys.captureRawKey(timeoutMs) }
    }

    val first = withTimeoutOrNull(timeoutMs.coerceIn(1_000L, 60_000L)) {
        channel.receive()
    } ?: run {
        jobs.forEach { it.cancel() }
        channel.close()
        return@coroutineScope null
    }

    // Merge reports from the same press. Android KeyCode remains primary; raw OEM identity stays hidden.
    delay(240L)
    val candidates = buildList {
        add(first)
        while (true) {
            val next = channel.tryReceive().getOrNull() ?: break
            add(next)
        }
    }

    jobs.forEach { it.cancel() }
    channel.close()
    mergeHardwareKeyCaptures(candidates)
}

private fun mergeHardwareKeyCaptures(
    captures: List<HardwareKeyCaptureResult>,
): HardwareKeyCaptureResult? {
    if (captures.isEmpty()) return null
    val android = captures
        .filter { it.keyCode > 0 || it.scanCode > 0 }
        .maxByOrNull { capture ->
            val sourceRank = when {
                "lsposed" in capture.sources -> 30
                "accessibility" in capture.sources -> 20
                else -> 10
            }
            sourceRank + (if (capture.keyCode > 0) 2 else 0) + (if (capture.scanCode > 0) 1 else 0)
        }
    val raw = captures.firstOrNull { it.linuxEvKey > 0 || it.mscScan != 0L }
    val primary = android ?: raw ?: captures.first()

    return HardwareKeyCaptureResult(
        keyCode = android?.keyCode ?: primary.keyCode,
        scanCode = android?.scanCode ?: primary.scanCode,
        deviceId = android?.deviceId ?: primary.deviceId,
        action = android?.action ?: primary.action,
        linuxEvKey = raw?.linuxEvKey ?: primary.linuxEvKey,
        mscScan = raw?.mscScan ?: primary.mscScan,
        deviceName = android?.deviceName.orEmpty().ifBlank { primary.deviceName },
        deviceDescriptor = android?.deviceDescriptor.orEmpty().ifBlank { primary.deviceDescriptor },
        vendorId = android?.vendorId?.takeIf { it > 0 } ?: primary.vendorId,
        productId = android?.productId?.takeIf { it > 0 } ?: primary.productId,
        sources = captures.flatMapTo(linkedSetOf()) { it.sources },
    )
}

private const val MAX_WORKSPACE_FILE_BYTES = 20 * 1024 * 1024
