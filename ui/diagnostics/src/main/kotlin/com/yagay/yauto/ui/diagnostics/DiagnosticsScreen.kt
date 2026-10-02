package com.yagay.yauto.ui.diagnostics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.diagnostics.*
import com.yagay.yauto.ui.design.MacroItemRow
import com.yagay.yauto.ui.design.MacroPalette

private sealed interface DiagnosticPage {
    data object Overview : DiagnosticPage
    data class Source(val source: DiagnosticSource) : DiagnosticPage
    data class Record(val record: DiagnosticRecord, val source: DiagnosticSource) : DiagnosticPage
}

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
    var page by remember { mutableStateOf<DiagnosticPage>(DiagnosticPage.Overview) }
    val records = snapshot?.records.orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (val current = page) {
                            DiagnosticPage.Overview -> "诊断中心"
                            is DiagnosticPage.Source -> sourceLabel(current.source)
                            is DiagnosticPage.Record -> current.record.title
                        }
                    )
                },
                navigationIcon = {
                    TextButton(onClick = {
                        page = when (val current = page) {
                            DiagnosticPage.Overview -> { onBack(); DiagnosticPage.Overview }
                            is DiagnosticPage.Source -> DiagnosticPage.Overview
                            is DiagnosticPage.Record -> DiagnosticPage.Source(current.source)
                        }
                    }) { Text("‹") }
                },
                actions = {
                    if (page == DiagnosticPage.Overview) {
                        TextButton(onClick = onCollect, enabled = !collecting) { Text(if (collecting) "收集中" else "刷新") }
                    }
                },
            )
        },
    ) { padding ->
        when (val current = page) {
            DiagnosticPage.Overview -> DiagnosticOverview(
                Modifier.padding(padding),
                statuses,
                records,
                snapshot != null,
                collecting,
                onCollect,
                onExport,
                onSource = { page = DiagnosticPage.Source(it) },
            )
            is DiagnosticPage.Source -> DiagnosticSourcePage(
                Modifier.padding(padding),
                current.source,
                records.filter { it.source == current.source },
                onRecord = { page = DiagnosticPage.Record(it, current.source) },
            )
            is DiagnosticPage.Record -> DiagnosticRecordPage(Modifier.padding(padding), current.record)
        }
    }
}

@Composable
private fun DiagnosticOverview(
    modifier: Modifier,
    statuses: List<CollectorStatus>,
    records: List<DiagnosticRecord>,
    hasSnapshot: Boolean,
    collecting: Boolean,
    onCollect: () -> Unit,
    onExport: () -> Unit,
    onSource: (DiagnosticSource) -> Unit,
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MacroPalette.Diagnostics.copy(alpha = .10f))) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("诊断与执行日志", fontWeight = FontWeight.SemiBold)
                    Text("按来源分组查看；Root、LSPosed、SystemUI、system_server、导入报告和 YAuto 执行记录都使用同一结构。", style = MaterialTheme.typography.bodySmall)
                    Text("诊断只在本机生成，不会自动上传。", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCollect, enabled = !collecting, modifier = Modifier.weight(1f)) { Text(if (collecting) "正在收集…" else "收集诊断") }
                OutlinedButton(onClick = onExport, enabled = hasSnapshot && !collecting, modifier = Modifier.weight(1f)) { Text("导出 JSON") }
            }
        }
        item { Text("来源", fontWeight = FontWeight.SemiBold) }
        items(DiagnosticSource.entries, key = { it.name }) { source ->
            val sourceRecords = records.filter { it.source == source }
            val errors = sourceRecords.count { it.severity == DiagnosticSeverity.ERROR }
            MacroItemRow(
                title = sourceLabel(source),
                subtitle = buildString {
                    append("${sourceRecords.size} 条记录")
                    if (errors > 0) append(" · $errors 个错误")
                },
                accent = sourceAccent(source),
                onClick = { onSource(source) },
            )
        }
        item { Text("采集器状态", fontWeight = FontWeight.SemiBold) }
        if (statuses.isEmpty()) item { Text("尚未检查采集器。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(statuses, key = { it.collectorId }) { status ->
            ListItem(
                headlineContent = { Text(status.collectorId) },
                supportingContent = { status.message?.let { Text(it) } },
                trailingContent = { Text(if (status.available) "可用" else "不可用") },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun DiagnosticSourcePage(
    modifier: Modifier,
    source: DiagnosticSource,
    records: List<DiagnosticRecord>,
    onRecord: (DiagnosticRecord) -> Unit,
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        item {
            Text("${records.size} 条记录", style = MaterialTheme.typography.labelLarge)
        }
        if (records.isEmpty()) item { Text("当前没有 ${sourceLabel(source)} 记录。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(records.asReversed(), key = { "${it.timestampEpochMs}:${it.title}:${it.message.hashCode()}" }) { record ->
            MacroItemRow(
                title = "${severityLabel(record.severity)} · ${record.title}",
                subtitle = record.message.lineSequence().firstOrNull().orEmpty().take(180),
                accent = severityAccent(record.severity),
                onClick = { onRecord(record) },
            )
        }
    }
}

@Composable
private fun DiagnosticRecordPage(modifier: Modifier, record: DiagnosticRecord) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("${sourceLabel(record.source)} · ${severityLabel(record.severity)}", fontWeight = FontWeight.SemiBold)
                    Text("时间：${record.timestampEpochMs}", style = MaterialTheme.typography.labelSmall)
                    record.context.executionId?.let { Text("Execution：$it", style = MaterialTheme.typography.labelSmall) }
                    record.context.automationId?.let { Text("Automation：$it", style = MaterialTheme.typography.labelSmall) }
                    record.context.flowId?.let { Text("Flow：$it", style = MaterialTheme.typography.labelSmall) }
                    record.context.featureId?.let { Text("Feature：$it", style = MaterialTheme.typography.labelSmall) }
                    record.context.backendId?.let { Text("Backend：$it", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        item {
            Text("详情", fontWeight = FontWeight.SemiBold)
            SelectionContainer { Text(record.message) }
        }
        if (record.attributes.isNotEmpty()) {
            item { Text("属性", fontWeight = FontWeight.SemiBold) }
            items(record.attributes.entries.toList(), key = { it.key }) { (key, value) ->
                ListItem(headlineContent = { Text(key) }, supportingContent = { SelectionContainer { Text(value) } })
                HorizontalDivider()
            }
        }
    }
}

private fun sourceLabel(source: DiagnosticSource): String = when (source) {
    DiagnosticSource.YAUTO -> "YAuto / 执行记录"
    DiagnosticSource.ANDROID -> "Android / Accessibility / Shizuku"
    DiagnosticSource.ROOT -> "Root"
    DiagnosticSource.LSPOSED -> "LSPosed"
    DiagnosticSource.SYSTEM_UI -> "SystemUI"
    DiagnosticSource.SYSTEM_SERVER -> "system_server"
    DiagnosticSource.ZYGOTE -> "Zygote"
    DiagnosticSource.IMPORTER -> "导入 / 兼容"
}

private fun sourceAccent(source: DiagnosticSource) = when (source) {
    DiagnosticSource.YAUTO -> MacroPalette.Action
    DiagnosticSource.ANDROID -> MacroPalette.State
    DiagnosticSource.ROOT -> MacroPalette.Utility
    DiagnosticSource.LSPOSED -> MacroPalette.Flow
    DiagnosticSource.SYSTEM_UI -> MacroPalette.Trigger
    DiagnosticSource.SYSTEM_SERVER -> MacroPalette.Diagnostics
    DiagnosticSource.ZYGOTE -> MacroPalette.Constraint
    DiagnosticSource.IMPORTER -> MacroPalette.Variable
}

private fun severityLabel(severity: DiagnosticSeverity): String = when (severity) {
    DiagnosticSeverity.DEBUG -> "调试"
    DiagnosticSeverity.INFO -> "信息"
    DiagnosticSeverity.WARNING -> "警告"
    DiagnosticSeverity.ERROR -> "错误"
}

private fun severityAccent(severity: DiagnosticSeverity) = when (severity) {
    DiagnosticSeverity.DEBUG -> MacroPalette.Utility
    DiagnosticSeverity.INFO -> MacroPalette.Action
    DiagnosticSeverity.WARNING -> MacroPalette.State
    DiagnosticSeverity.ERROR -> MacroPalette.Trigger
}
