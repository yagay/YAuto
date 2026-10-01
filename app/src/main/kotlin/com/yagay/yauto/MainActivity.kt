package com.yagay.yauto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import com.yagay.yauto.core.diagnostics.*
import com.yagay.yauto.core.importer.ImportInput
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.storage.WorkspaceData
import com.yagay.yauto.core.storage.merge
import com.yagay.yauto.ui.design.YAutoTheme
import com.yagay.yauto.ui.diagnostics.DiagnosticsScreen
import com.yagay.yauto.ui.editor.AutomationEditorScreen
import com.yagay.yauto.ui.home.HomeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = AppGraph(this)
        setContent {
            YAutoTheme {
                var page by remember { mutableStateOf("home") }
                var statuses by remember { mutableStateOf<List<CollectorStatus>>(emptyList()) }
                var diagnosticSnapshot by remember { mutableStateOf<DiagnosticSnapshot?>(null) }
                var collecting by remember { mutableStateOf(false) }
                var workspace by remember { mutableStateOf(WorkspaceData()) }
                var importSummary by remember { mutableStateOf<String?>(null) }
                var runtimeSummary by remember { mutableStateOf<String?>(null) }
                val scope = rememberCoroutineScope()

                LaunchedEffect(Unit) { workspace = graph.workspace.load() }

                val diagnosticsExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                    val snapshot = diagnosticSnapshot
                    if (uri != null && snapshot != null) scope.launch(Dispatchers.IO) {
                        val text = Json { prettyPrint = true; encodeDefaults = true }.encodeToString(DiagnosticSnapshot.serializer(), snapshot)
                        contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(text) }
                    }
                }

                val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: byteArrayOf()
                            graph.importers.import(ImportInput(uri.lastPathSegment, contentResolver.getType(uri), bytes))
                        }
                        graph.importReports.save(result)
                        if (result.success) {
                            workspace = workspace.merge(result.bundle.automations, result.bundle.flows, result.bundle.globalVariables)
                            graph.workspace.save(workspace)
                            graph.runtime.resetState()
                            importSummary = "${result.importerId}: 导入 ${result.bundle.automations.size} 个自动化、${result.bundle.flows.size} 个流程；${result.issues.size} 个兼容提示"
                        } else {
                            importSummary = "导入失败：${result.issues.firstOrNull()?.message ?: "未知格式"}"
                        }
                    }
                }

                when (page) {
                    "editor" -> AutomationEditorScreen { page = "home" }
                    "diagnostics" -> DiagnosticsScreen(
                        statuses = statuses,
                        snapshot = diagnosticSnapshot,
                        collecting = collecting,
                        onCollect = {
                            scope.launch {
                                collecting = true
                                statuses = graph.diagnosticRegistry.all().map { it.status() }
                                diagnosticSnapshot = graph.diagnostics.snapshot()
                                collecting = false
                            }
                        },
                        onExport = { diagnosticsExportLauncher.launch("YAuto-diagnostic.json") },
                        onBack = { page = "home" },
                    )
                    else -> HomeScreen(
                        featureCount = graph.features.allDescriptors().size,
                        automationCount = workspace.automations.size,
                        flowCount = workspace.flows.size,
                        importerNames = graph.importers.all().map { it.displayName },
                        importSummary = importSummary,
                        runtimeSummary = runtimeSummary,
                        onOpenEditor = { page = "editor" },
                        onImport = { importLauncher.launch(arrayOf("application/json", "text/xml", "application/xml", "application/octet-stream", "*/*")) },
                        onRunManual = {
                            scope.launch {
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
                            scope.launch { statuses = graph.diagnosticRegistry.all().map { it.status() } }
                        },
                    )
                }
            }
        }
    }
}
