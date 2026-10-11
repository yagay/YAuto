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
    localizedTitles: Map<String, String>,
) {
    val context = LocalContext.current
    val snapshot by scanner.snapshot.collectAsState()
    val scanning by scanner.scanning.collectAsState()
    val verificationProgress by scanner.verificationProgress.collectAsState()
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
                    ?.use { it.write(featureHealthDiagnosticJson(context, current, localizedTitles)) }
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
    val visible = remember(snapshot, normalized, localizedTitles) {
        snapshot?.items.orEmpty().filter { item ->
            normalized.isEmpty() ||
                normalized in item.title.lowercase() ||
                normalized in localizedTitles[item.featureId].orEmpty().lowercase() ||
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
                                snapshot!!.unknownCount,
                                snapshot!!.brokenCount,
                                snapshot!!.unsupportedCount,
                            )
                            else -> stringResource(TextR.string.feature_health_not_scanned)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (snapshot != null && snapshot!!.unknownCount > 0) {
                        Text(
                            stringResource(TextR.string.feature_health_unknown_count, snapshot!!.unknownCount),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
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
                        onClick = scanner::requestReadOnlyVerification,
                        enabled = !scanning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(
                            if (scanning) TextR.string.feature_health_scanning
                            else TextR.string.feature_health_probe_read_only
                        ))
                    }
                    OutlinedButton(
                        onClick = { scanner.requestScan(force = true) },
                        enabled = !scanning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(TextR.string.feature_health_scan_now))
                    }
                    verificationProgress?.let { (completed, total) ->
                        Text(
                            stringResource(TextR.string.feature_health_probe_progress, completed, total),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (snapshot != null && snapshot!!.items.none { it.probe != null }) {
                        Text(
                            stringResource(TextR.string.feature_health_readiness_only_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (snapshot?.items?.any { it.probe != null } == true) {
                        Text(
                            stringResource(
                                TextR.string.feature_health_probe_summary,
                                snapshot!!.probedCount, snapshot!!.probeFailedCount,
                                snapshot!!.manualTestCount,
                            ),
                            style = MaterialTheme.typography.bodySmall,
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
            FeatureHealthRow(item, localizedTitles[item.featureId] ?: item.title, scanning) {
                scanner.requestFeatureVerification(item.featureId)
            }
        }
    }
}

@Composable
private fun FeatureHealthRow(
    item: FeatureHealthItem,
    translatedTitle: String,
    scanning: Boolean,
    onVerify: () -> Unit,
) {
    val statusText = when (item.status) {
        FeatureHealthStatus.READY -> stringResource(TextR.string.feature_health_authorized_unverified)
        FeatureHealthStatus.UNKNOWN -> stringResource(TextR.string.feature_health_unknown)
        FeatureHealthStatus.BLOCKED -> stringResource(TextR.string.feature_health_blocked)
        FeatureHealthStatus.BROKEN -> stringResource(TextR.string.feature_health_broken)
        FeatureHealthStatus.UNSUPPORTED -> stringResource(TextR.string.feature_health_unsupported)
    }
    val statusColor = when (item.status) {
        FeatureHealthStatus.READY -> MacroPalette.Constraint
        FeatureHealthStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
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
                    translatedTitle,
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
            item.probe?.let { probe ->
                val label = when (probe.status) {
                    FeatureProbeStatus.RESPONDED -> TextR.string.feature_health_probe_responded
                    FeatureProbeStatus.FAILED -> TextR.string.feature_health_probe_failed
                    FeatureProbeStatus.NOT_TESTABLE -> TextR.string.feature_health_probe_manual
                    FeatureProbeStatus.SKIPPED -> TextR.string.feature_health_probe_skipped
                }
                Text(
                    stringResource(label),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (probe.status == FeatureProbeStatus.FAILED)
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (probe.status == FeatureProbeStatus.RESPONDED && probe.interfaceName != null) {
                    Text(
                        stringResource(
                            TextR.string.feature_health_probe_evidence,
                            probe.interfaceName,
                            probe.observedValue.orEmpty(),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                    )
                } else if (probe.status == FeatureProbeStatus.FAILED) {
                    Text(
                        stringResource(
                            TextR.string.feature_health_probe_error,
                            probe.interfaceName.orEmpty(),
                            probe.detail.orEmpty(),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
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
            OutlinedButton(
                onClick = onVerify,
                enabled = !scanning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(TextR.string.feature_health_probe_one))
            }
        }
    }
}

private fun featureHealthDiagnosticJson(
    context: android.content.Context,
    snapshot: FeatureHealthSnapshot,
    localizedTitles: Map<String, String>,
): String {
    val items = JSONArray()
    snapshot.items.forEach { item ->
        items.put(
            JSONObject()
                .put("featureId", item.featureId)
                .put("title", localizedTitles[item.featureId] ?: item.title)
                .put("sourceTitle", item.title)
                .put("localizedTitle", localizedTitles[item.featureId] ?: item.title)
                .put("kind", item.kind.name)
                .put("status", item.status.name)
                .put("backendId", item.backendId)
                .put("missingRequirements", JSONArray(item.missingRequirements.map { it.id }))
                .put("detail", item.detail)
                .put("probeStatus", item.probe?.status?.name)
                .put("probeInterface", item.probe?.interfaceName)
                .put("probeObservedValue", item.probe?.observedValue)
                .put("probeError", item.probe?.detail)
                .put("reason", featureHealthReason(context, item))
                .put("solution", featureHealthResolution(context, item))
        )
    }
    return JSONObject()
        .put("schemaVersion", 2)
        .put(
            "scanType",
            if (snapshot.items.any { it.probe != null }) "READ_ONLY_API" else "READINESS_ONLY",
        )
        .put("hasRealInterfaceEvidence", snapshot.probedCount + snapshot.probeFailedCount > 0)
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
                .put("unknown", snapshot.unknownCount)
                .put("blocked", snapshot.blockedCount)
                .put("broken", snapshot.brokenCount)
                .put("unsupported", snapshot.unsupportedCount)
                .put("interfaceResponded", snapshot.probedCount)
                .put("interfaceFailed", snapshot.probeFailedCount)
                .put("needsManualTest", snapshot.manualTestCount),
        )
        .put(
            "accessState",
            JSONObject().apply {
                snapshot.accessState.toSortedMap(compareBy { it.id }).forEach { (requirement, available) ->
                    put(requirement.id, available)
                }
            },
        )
        .put(
            "accessAvailability",
            JSONObject().apply {
                snapshot.accessAvailability.toSortedMap(compareBy { it.id }).forEach { (requirement, status) ->
                    put(requirement.id, status.name)
                }
            },
        )
        .put("items", items)
        .toString(2)
}
