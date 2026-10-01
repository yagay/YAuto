package com.yagay.yauto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import com.yagay.yauto.core.diagnostics.*
import com.yagay.yauto.core.importer.ImportInput
import com.yagay.yauto.core.model.*
import com.yagay.yauto.core.storage.*
import com.yagay.yauto.ui.design.YAutoTheme
import com.yagay.yauto.ui.diagnostics.DiagnosticsScreen
import com.yagay.yauto.ui.editor.MacroAutomationEditorScreen
import com.yagay.yauto.ui.editor.MacroFlowEditorScreen
import com.yagay.yauto.ui.editor.GlobalVariablesScreen
import com.yagay.yauto.ui.home.MacroHomeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AutomationRuntimeService.start(this)
        val graph = (application as YAutoApplication).graph
        setContent {
            YAutoTheme {
                var page by remember { mutableStateOf("home") }
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
                fun operation(block: suspend () -> Unit) {
                    scope.launch {
                        try { block() } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { importSummary = "操作失败：${error.message ?: error.javaClass.simpleName}" }
                    }
                }

                LaunchedEffect(Unit) {
                    try { workspace = graph.workspace.load(); workspaceReady = true }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { importSummary = "工作区读取失败：${error.message}。原始文件已保留。" }
                }

                fun saveWorkspace(updated: WorkspaceData, resetId: AutomationId? = null, onSaved: () -> Unit = {}) {
                    if (workspaceSaving) return
                    workspaceSaving = true
                    operation {
                        try {
                            saveLock.withLock { graph.workspace.save(updated); workspace = updated }
                            onSaved()
                            operation {
                                resetId?.let(graph.runtime::resetState) ?: graph.runtime.resetState()
                                graph.runtime.dispatch(RuntimeEvent("android.event.workspace_changed", source = "ui"), statesOnly = true)
                            }
                        } finally { workspaceSaving = false }
                    }
                }

                val diagnosticsExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                    val snapshot = diagnosticSnapshot
                    if (uri != null && snapshot != null) operation { withContext(Dispatchers.IO) {
                        val text = Json { prettyPrint = true; encodeDefaults = true }.encodeToString(DiagnosticSnapshot.serializer(), snapshot)
                        contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(text) }
                    } }
                }

                val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                    val text = backupText
                    if (uri != null && text != null) operation {
                        withContext(Dispatchers.IO) { requireNotNull(contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(text) } }
                        importSummary = "备份已保存。"
                    }
                }
                val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) operation {
                        pendingRestore = withContext(Dispatchers.IO) {
                            requireNotNull(contentResolver.openInputStream(uri)).use { input ->
                                val bytes = input.readBoundedBytes(20 * 1024 * 1024)
                                require(bytes.size <= 20 * 1024 * 1024) { "备份超过 20 MB" }
                                WorkspaceBackupCodec.decode(bytes.toString(Charsets.UTF_8))
                            }
                        }
                    }
                }

                val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) operation {
                        val result = withContext(Dispatchers.IO) {
                            val bytes = contentResolver.openInputStream(uri)?.use { it.readBoundedBytes(20 * 1024 * 1024) } ?: byteArrayOf()
                            require(bytes.size <= 20 * 1024 * 1024) { "导入文件超过 20 MB" }
                            graph.importers.import(ImportInput(uri.lastPathSegment, contentResolver.getType(uri), bytes))
                        }
                        graph.importReports.save(result)
                        if (result.success) {
                            val updated = workspace.merge(result.bundle.automations, result.bundle.flows, result.bundle.globalVariables)
                            saveWorkspace(updated) {
                                importSummary = "${result.importerId}: 导入 ${result.bundle.automations.size} 个自动化、${result.bundle.flows.size} 个流程；${result.issues.size} 个兼容提示"
                            }
                        } else {
                            importSummary = "导入失败：${result.issues.firstOrNull()?.message ?: "未知格式"}"
                        }
                    }
                }

                if (!workspaceReady) {
                    Column(Modifier.fillMaxSize().padding(24.dp)) {
                        Text(importSummary ?: "正在读取工作区…")
                        Button(onClick = { operation { workspace = graph.workspace.load(); workspaceReady = true; importSummary = null } }) { Text("重试") }
                    }
                    return@YAutoTheme
                }

                pendingRestore?.let { backup ->
                    val unknown = backup.workspace.featureIds().filter { graph.features.descriptor(it) == null }
                    AlertDialog(onDismissRequest = { pendingRestore = null }, title = { Text("恢复备份？") },
                        text = { Text("合并 ${backup.workspace.automations.size} 个自动化、${backup.workspace.flows.size} 个流程。同 ID 的项目将替换；未安装功能 ${unknown.size} 个，其配置会保留。") },
                        confirmButton = { TextButton(onClick = { saveWorkspace(workspace.merge(backup.workspace.automations, backup.workspace.flows, backup.workspace.globalVariables)) { importSummary = "已恢复备份。" }; pendingRestore = null }) { Text("恢复") } },
                        dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("取消") } })
                }

                if (workspaceSaving) AlertDialog(onDismissRequest = {}, confirmButton = {},
                    title = { Text("正在保存") }, text = { CircularProgressIndicator() })

                when (page) {
                    "editor" -> MacroAutomationEditorScreen(
                        flows = workspace.flows,
                        descriptors = graph.features.allDescriptors(),
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
                            page = "home"
                        },
                        onBack = {
                            editingAutomation = null
                            page = "home"
                        },
                    )
                    "flow" -> MacroFlowEditorScreen(
                        initial = editingFlow,
                        descriptors = graph.features.allDescriptors(),
                        flows = workspace.flows,
                        onSave = { flow ->
                            saveWorkspace(workspace.copy(flows = workspace.flows.filterNot { it.id == flow.id } + flow))
                            editingFlow = null
                            page = "home"
                        },
                        onBack = { editingFlow = null; page = "home" },
                    )
                    "variables" -> GlobalVariablesScreen(workspace.globalVariables,
                        onSave = { saveWorkspace(workspace.copy(globalVariables = it)); page = "home" }, onBack = { page = "home" })
                    "settings" -> RuntimeSettingsScreen(graph, onBack = { page = "home" })
                    "diagnostics" -> DiagnosticsScreen(
                        statuses = statuses,
                        snapshot = diagnosticSnapshot,
                        collecting = collecting,
                        onCollect = {
                            operation {
                                collecting = true
                                try {
                                    statuses = graph.diagnosticRegistry.all().map { it.status() }
                                    diagnosticSnapshot = graph.diagnostics.snapshot()
                                } finally { collecting = false }
                            }
                        },
                        onExport = { diagnosticsExportLauncher.launch("YAuto-diagnostic.json") },
                        onBack = { page = "home" },
                    )
                    else -> MacroHomeScreen(
                        flows = workspace.flows,
                        onNewFlow = { editingFlow = null; page = "flow" },
                        onEditFlow = { editingFlow = it; page = "flow" },
                        onDeleteFlow = { flow ->
                            if (workspace.references(flow.id)) importSummary = "流程仍被引用，请先移除调用。"
                            else saveWorkspace(workspace.copy(flows = workspace.flows.filterNot { it.id == flow.id }))
                        },
                        onEditVariables = { page = "variables" },
                        onOpenSettings = { page = "settings" },
                        onBackup = { backupText = WorkspaceBackupCodec.encode(workspace, "0.1.0"); backupLauncher.launch("YAuto-backup.json") },
                        onRestore = { restoreLauncher.launch(arrayOf("application/json", "*/*")) },
                        featureCount = graph.features.allDescriptors().size,
                        automations = workspace.automations,
                        flowCount = workspace.flows.size,
                        importerNames = graph.importers.all().map { it.displayName },
                        importSummary = importSummary,
                        runtimeSummary = runtimeSummary,
                        onNewAutomation = {
                            editingAutomation = null
                            page = "editor"
                        },
                        onEditAutomation = { automation ->
                            editingAutomation = automation
                            page = "editor"
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
                            val updated = workspace.copy(automations = workspace.automations.filterNot { it.id == automation.id })
                            saveWorkspace(updated, automation.id)
                        },
                        onImport = { importLauncher.launch(arrayOf("application/json", "text/xml", "application/xml", "application/octet-stream", "*/*")) },
                        onRunManual = {
                            operation {
                                val result = graph.runtime.dispatch(
                                    RuntimeEvent(
                                        typeId = "core.event.manual",
                                        payload = mapOf("name" to ConfigValue.StringValue("home-test")),
                                        source = "ui",
                                    )
                                )
                                runtimeSummary = "Runtime: 匹配并执行 ${result.runs.size} 个自动化"
                            }
                        },
                        onOpenDiagnostics = {
                            page = "diagnostics"
                            operation { statuses = graph.diagnosticRegistry.all().map { it.status() } }
                        },
                    )
                }
            }
        }
    }
}

private fun java.io.InputStream.readBoundedBytes(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit) { "文件超过 20 MB" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
