package com.yagay.yauto

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yagay.yauto.ui.design.MacroPalette
import com.yagay.yauto.ui.design.R as TextR
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun FeatureHealthScreen(
    modifier: Modifier,
    scanner: FeatureHealthScanner,
) {
    val context = LocalContext.current
    val snapshot by scanner.snapshot.collectAsState()
    val scanning by scanner.scanning.collectAsState()
    val autoScan by scanner.autoScanEnabled.collectAsState()
    var exportStatus by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val current = snapshot
        if (uri != null && current != null) {
            exportStatus = runCatching {
                context.contentResolver.openOutputStream(uri, "wt")
                    ?.bufferedWriter()
                    ?.use { it.write(featureHealthDiagnosticJson(context, current)) }
                    ?: error("Unable to open output stream")
                context.getString(TextR.string.feature_health_export_saved)
            }.getOrElse { error ->
                context.getString(
                    TextR.string.feature_health_export_failed_format,
                    error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }
    var query by remember { mutableStateOf("") }
    val normalized = query.trim().lowercase()
    val visible = remember(snapshot, normalized) {
        snapshot?.items.orEmpty().filter { item ->
            normalized.isEmpty() ||
                normalized in item.title.lowercase() ||
                normalized in item.featureId.lowercase()
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "summary") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(TextR.string.runtime_settings_feature_health),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        when {
                            scanning -> stringResource(TextR.string.feature_health_scanning)
                            snapshot != null -> stringResource(
                                TextR.string.feature_health_summary_format,
                                snapshot!!.items.size,
                                snapshot!!.readyCount,
                                snapshot!!.blockedCount,
                                snapshot!!.brokenCount,
                                snapshot!!.unsupportedCount,
                            )
                            else -> stringResource(TextR.string.feature_health_not_scanned)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(TextR.string.feature_health_safe_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(TextR.string.feature_health_auto_scan),
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                stringResource(TextR.string.feature_health_auto_scan_detail),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = autoScan,
                            onCheckedChange = scanner::setAutoScanEnabled,
                        )
                    }
                    Button(
                        onClick = { scanner.requestScan(force = true) },
                        enabled = !scanning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                if (scanning) TextR.string.feature_health_scanning
                                else TextR.string.feature_health_scan_now
                            )
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            exportLauncher.launch("YAuto-feature-health-" + System.currentTimeMillis() + ".json")
                        },
                        enabled = snapshot != null && !scanning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(TextR.string.feature_health_export_log))
                    }
                    exportStatus?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item(key = "search") {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(TextR.string.feature_health_search)) },
            )
        }
        items(
            items = visible,
            key = { it.featureId },
            contentType = { "feature_health" },
        ) { item ->
            FeatureHealthRow(item)
        }
    }
}

@Composable
private fun FeatureHealthRow(item: FeatureHealthItem) {
    val statusText = when (item.status) {
        FeatureHealthStatus.READY -> stringResource(TextR.string.feature_health_ready)
        FeatureHealthStatus.BLOCKED -> stringResource(TextR.string.feature_health_blocked)
        FeatureHealthStatus.BROKEN -> stringResource(TextR.string.feature_health_broken)
        FeatureHealthStatus.UNSUPPORTED -> stringResource(TextR.string.feature_health_unsupported)
    }
    val statusColor = when (item.status) {
        FeatureHealthStatus.READY -> MacroPalette.Constraint
        FeatureHealthStatus.BLOCKED -> MacroPalette.Trigger
        FeatureHealthStatus.BROKEN -> MaterialTheme.colorScheme.error
        FeatureHealthStatus.UNSUPPORTED -> MacroPalette.Utility
    }
    val context = LocalContext.current
    val missing = item.missingRequirements.joinToString(", ") { healthRequirementLabel(context, it) }
    val reason = featureHealthReason(context, item)
    val resolution = featureHealthResolution(context, item)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    item.title,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Text(statusText, color = statusColor, style = MaterialTheme.typography.labelMedium)
            }
            Text(item.featureId, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (missing.isNotBlank()) {
                Text(
                    stringResource(TextR.string.feature_health_missing_format, missing),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item.backendId?.let {
                Text(
                    stringResource(TextR.string.feature_health_backend_format, it),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                stringResource(TextR.string.feature_health_reason_format, reason),
                style = MaterialTheme.typography.bodySmall,
            )
            resolution?.let {
                Text(
                    stringResource(TextR.string.feature_health_solution_format, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun featureHealthDiagnosticJson(
    context: android.content.Context,
    snapshot: FeatureHealthSnapshot,
): String {
    val items = JSONArray()
    snapshot.items.forEach { item ->
        items.put(
            JSONObject()
                .put("featureId", item.featureId)
                .put("title", item.title)
                .put("kind", item.kind.name)
                .put("status", item.status.name)
                .put("backendId", item.backendId)
                .put("missingRequirements", JSONArray(item.missingRequirements.map { it.id }))
                .put("detail", item.detail)
                .put("reason", featureHealthReason(context, item))
                .put("solution", featureHealthResolution(context, item))
        )
    }
    return JSONObject()
        .put("schemaVersion", 1)
        .put("startedAtEpochMs", snapshot.startedAtEpochMs)
        .put("finishedAtEpochMs", snapshot.finishedAtEpochMs)
        .put(
            "device",
            JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("brand", Build.BRAND)
                .put("model", Build.MODEL)
                .put("sdkInt", Build.VERSION.SDK_INT)
                .put("release", Build.VERSION.RELEASE),
        )
        .put(
            "summary",
            JSONObject()
                .put("total", snapshot.items.size)
                .put("ready", snapshot.readyCount)
                .put("blocked", snapshot.blockedCount)
                .put("broken", snapshot.brokenCount)
                .put("unsupported", snapshot.unsupportedCount),
        )
        .put(
            "accessState",
            JSONObject().apply {
                snapshot.accessState.toSortedMap(compareBy { it.id }).forEach { (requirement, available) ->
                    put(requirement.id, available)
                }
            },
        )
        .put("items", items)
        .toString(2)
}
