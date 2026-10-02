package com.yagay.yauto.ui.diagnostics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.diagnostics.*
import com.yagay.yauto.ui.design.MacroItemRow
import com.yagay.yauto.ui.design.MacroPalette
import com.yagay.yauto.ui.design.localizedDateTime
import com.yagay.yauto.ui.design.R as TextR

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
                            DiagnosticPage.Overview -> stringResource(TextR.string.diagnostics_title)
                            is DiagnosticPage.Source -> sourceLabel(current.source)
                            is DiagnosticPage.Record -> current.record.title
                        }
                    )
                },
                navigationIcon = {
                    TextButton(onClick = {
                        page = when (val current = page) {
                            DiagnosticPage.Overview -> {
                                onBack()
                                DiagnosticPage.Overview
                            }
                            is DiagnosticPage.Source -> DiagnosticPage.Overview
                            is DiagnosticPage.Record -> DiagnosticPage.Source(current.source)
                        }
                    }) { androidx.compose.material3.Icon(painter = androidx.compose.ui.res.painterResource(com.yagay.yauto.ui.design.R.drawable.ic_back), contentDescription = androidx.compose.ui.res.stringResource(com.yagay.yauto.ui.design.R.string.icon_back)) }
                },
                actions = {
                    if (page == DiagnosticPage.Overview) {
                        TextButton(onClick = onCollect, enabled = !collecting) {
                            Text(
                                stringResource(
                                    if (collecting) TextR.string.diagnostics_collecting_short
                                    else TextR.string.diagnostics_refresh
                                )
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        when (val current = page) {
            DiagnosticPage.Overview -> DiagnosticOverview(
                Modifier.padding(padding), statuses, records, snapshot != null, collecting,
                onCollect, onExport, onSource = { page = DiagnosticPage.Source(it) },
            )
            is DiagnosticPage.Source -> DiagnosticSourcePage(
                Modifier.padding(padding), current.source,
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
                    Text(stringResource(TextR.string.diagnostics_header), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(TextR.string.diagnostics_header_detail), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(TextR.string.diagnostics_local_only), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCollect, enabled = !collecting, modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            if (collecting) TextR.string.diagnostics_collecting
                            else TextR.string.diagnostics_collect
                        )
                    )
                }
                OutlinedButton(
                    onClick = onExport,
                    enabled = hasSnapshot && !collecting,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(TextR.string.diagnostics_export_json)) }
            }
        }
        item { Text(stringResource(TextR.string.diagnostics_sources), fontWeight = FontWeight.SemiBold) }
        items(DiagnosticSource.entries, key = { it.name }) { source ->
            val sourceRecords = records.filter { it.source == source }
            val errors = sourceRecords.count { it.severity == DiagnosticSeverity.ERROR }
            MacroItemRow(
                title = sourceLabel(source),
                subtitle = if (errors > 0) {
                    stringResource(TextR.string.diagnostics_source_summary_errors_format, sourceRecords.size, errors)
                } else {
                    stringResource(TextR.string.diagnostics_source_summary_format, sourceRecords.size)
                },
                accent = sourceAccent(source),
                onClick = { onSource(source) },
            )
        }
        item { Text(stringResource(TextR.string.diagnostics_collectors), fontWeight = FontWeight.SemiBold) }
        if (statuses.isEmpty()) {
            item {
                Text(
                    stringResource(TextR.string.diagnostics_collectors_unchecked),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(statuses, key = { it.collectorId }) { status ->
            ListItem(
                headlineContent = { Text(status.collectorId) },
                supportingContent = { status.message?.let { Text(it) } },
                trailingContent = {
                    Text(
                        stringResource(
                            if (status.available) TextR.string.diagnostics_available
                            else TextR.string.diagnostics_unavailable
                        )
                    )
                },
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
            Text(
                stringResource(TextR.string.diagnostics_record_count_format, records.size),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        if (records.isEmpty()) {
            item {
                Text(
                    stringResource(TextR.string.diagnostics_no_source_records_format, sourceLabel(source)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(records.asReversed(), key = { "${it.timestampEpochMs}:${it.title}:${it.message.hashCode()}" }) { record ->
            MacroItemRow(
                title = stringResource(
                    TextR.string.diagnostics_severity_title_format,
                    severityLabel(record.severity),
                    record.title,
                ),
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
                    Text(
                        stringResource(
                            TextR.string.diagnostics_source_severity_format,
                            sourceLabel(record.source),
                            severityLabel(record.severity),
                        ),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(TextR.string.diagnostics_time_format, localizedDateTime(record.timestampEpochMs)),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    record.context.executionId?.let {
                        Text(stringResource(TextR.string.diagnostics_context_execution_format, it), style = MaterialTheme.typography.labelSmall)
                    }
                    record.context.automationId?.let {
                        Text(stringResource(TextR.string.diagnostics_context_automation_format, it), style = MaterialTheme.typography.labelSmall)
                    }
                    record.context.flowId?.let {
                        Text(stringResource(TextR.string.diagnostics_context_flow_format, it), style = MaterialTheme.typography.labelSmall)
                    }
                    record.context.featureId?.let {
                        Text(stringResource(TextR.string.diagnostics_context_feature_format, it), style = MaterialTheme.typography.labelSmall)
                    }
                    record.context.backendId?.let {
                        Text(stringResource(TextR.string.diagnostics_context_backend_format, it), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        item {
            Text(stringResource(TextR.string.diagnostics_details), fontWeight = FontWeight.SemiBold)
            SelectionContainer { Text(record.message) }
        }
        if (record.attributes.isNotEmpty()) {
            item { Text(stringResource(TextR.string.diagnostics_attributes), fontWeight = FontWeight.SemiBold) }
            items(record.attributes.entries.toList(), key = { it.key }) { (key, value) ->
                ListItem(
                    headlineContent = { Text(key) },
                    supportingContent = { SelectionContainer { Text(value) } },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun sourceLabel(source: DiagnosticSource): String = stringResource(
    when (source) {
        DiagnosticSource.YAUTO -> TextR.string.diagnostics_source_yauto
        DiagnosticSource.ANDROID -> TextR.string.diagnostics_source_android
        DiagnosticSource.ROOT -> TextR.string.diagnostics_source_root
        DiagnosticSource.LSPOSED -> TextR.string.diagnostics_source_lsposed
        DiagnosticSource.SYSTEM_UI -> TextR.string.diagnostics_source_system_ui
        DiagnosticSource.SYSTEM_SERVER -> TextR.string.diagnostics_source_system_server
        DiagnosticSource.ZYGOTE -> TextR.string.diagnostics_source_zygote
        DiagnosticSource.IMPORTER -> TextR.string.diagnostics_source_importer
    }
)

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

@Composable
private fun severityLabel(severity: DiagnosticSeverity): String = stringResource(
    when (severity) {
        DiagnosticSeverity.DEBUG -> TextR.string.diagnostics_severity_debug
        DiagnosticSeverity.INFO -> TextR.string.diagnostics_severity_info
        DiagnosticSeverity.WARNING -> TextR.string.diagnostics_severity_warning
        DiagnosticSeverity.ERROR -> TextR.string.diagnostics_severity_error
    }
)

private fun severityAccent(severity: DiagnosticSeverity) = when (severity) {
    DiagnosticSeverity.DEBUG -> MacroPalette.Utility
    DiagnosticSeverity.INFO -> MacroPalette.Action
    DiagnosticSeverity.WARNING -> MacroPalette.State
    DiagnosticSeverity.ERROR -> MacroPalette.Trigger
}
