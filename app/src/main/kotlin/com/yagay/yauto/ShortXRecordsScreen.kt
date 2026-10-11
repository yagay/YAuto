package com.yagay.yauto

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.logging.TraceEvent
import com.yagay.yauto.core.logging.TraceKind
import com.yagay.yauto.ui.design.R as TextR
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Persisted TRIGGER/ACTION/ERROR trace viewer, without a second log database. */
@Composable
internal fun ShortXRecordsScreen(context: Context, graph: AppGraph) {
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf<List<TraceEvent>>(emptyList()) }
    var selected by remember { mutableStateOf("all") }
    var message by remember { mutableStateOf<String?>(null) }
    val saved = stringResource(TextR.string.sx_saved)
    val errorText = stringResource(TextR.string.sx_records_export_error)
    val filtered = remember(records, selected) {
        if (selected == "all") records.asReversed()
        else records.asReversed().filter { it.kind.name == selected }
    }

    fun refresh() {
        scope.launch {
            records = runCatching { graph.recentExecutionRecords(500) }.getOrDefault(emptyList())
        }
    }
    LaunchedEffect(Unit) { records = graph.recentExecutionRecords(500) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val toWrite = filtered.toList()
            scope.launch {
                message = runCatching {
                    withContext(Dispatchers.IO) {
                        val json = Json { prettyPrint = true; encodeDefaults = true }
                        val contents = json.encodeToString(ListSerializer(TraceEvent.serializer()), toWrite)
                        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
                            it.write(contents)
                        } ?: error("Cannot open output stream")
                    }
                    saved
                }.getOrElse { errorText }
            }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text(stringResource(TextR.string.sx_records_hint), style = MaterialTheme.typography.bodySmall) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { refresh() }) {
                    Text(stringResource(TextR.string.sx_records_refresh))
                }
                OutlinedButton(onClick = { exportLauncher.launch("YAuto-records.json") }) {
                    Text(stringResource(TextR.string.sx_records_export))
                }
            }
        }
        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("all", TraceKind.TRIGGER.name, TraceKind.ACTION.name, TraceKind.ERROR.name)
                    .forEach { kind ->
                        FilterChip(
                            selected = selected == kind,
                            onClick = { selected = kind },
                            label = { Text(kind) },
                        )
                    }
            }
        }
        item {
            Text(stringResource(TextR.string.sx_records_count, filtered.size), fontWeight = FontWeight.SemiBold)
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        items(filtered.size) { index ->
            val event = filtered[index]
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(event.kind.name, fontWeight = FontWeight.SemiBold)
                    Text(event.message, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        DateFormat.getDateTimeInstance().format(Date(event.timestampEpochMs)),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    event.featureId?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall)
                    }
                    event.backendId?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
