package com.yagay.yauto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.registry.AccessRequirement
import com.yagay.yauto.ui.design.MacroPalette
import com.yagay.yauto.ui.design.R as TextR

@Composable
internal fun FeatureHealthScreen(
    modifier: Modifier,
    scanner: FeatureHealthScanner,
) {
    val snapshot by scanner.snapshot.collectAsState()
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
                        snapshot?.let {
                            stringResource(
                                TextR.string.feature_health_summary_format,
                                it.items.size,
                                it.readyCount,
                                it.blockedCount,
                                it.unsupportedCount,
                            )
                        } ?: stringResource(TextR.string.feature_health_scanning),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(TextR.string.feature_health_safe_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { scanner.requestScan(force = true) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(TextR.string.feature_health_recheck))
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
        FeatureHealthStatus.UNSUPPORTED -> stringResource(TextR.string.feature_health_unsupported)
    }
    val statusColor = when (item.status) {
        FeatureHealthStatus.READY -> MacroPalette.Constraint
        FeatureHealthStatus.BLOCKED -> MacroPalette.Trigger
        FeatureHealthStatus.UNSUPPORTED -> MacroPalette.Utility
    }
    val missing = item.missingRequirements
        .map { healthRequirementLabel(it) }
        .joinToString(" · ")

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
        }
    }
}

@Composable
private fun healthRequirementLabel(value: AccessRequirement): String = stringResource(
    when (value) {
        AccessRequirement.ROOT -> TextR.string.access_root
        AccessRequirement.SHIZUKU -> TextR.string.access_shizuku
        AccessRequirement.LSPOSED -> TextR.string.access_lsposed
        AccessRequirement.ZYGISK -> TextR.string.access_zygisk
        AccessRequirement.ACCESSIBILITY -> TextR.string.access_accessibility
        AccessRequirement.USAGE_STATS -> TextR.string.access_usage_stats
        AccessRequirement.NOTIFICATION_LISTENER -> TextR.string.access_notification_listener
        AccessRequirement.POST_NOTIFICATIONS -> TextR.string.access_post_notifications
        AccessRequirement.OVERLAY -> TextR.string.access_overlay
        AccessRequirement.WRITE_SETTINGS -> TextR.string.access_write_settings
        AccessRequirement.CAMERA -> TextR.string.access_camera
        AccessRequirement.LOCATION -> TextR.string.access_location
        AccessRequirement.BLUETOOTH_CONNECT -> TextR.string.access_bluetooth
        AccessRequirement.DND_POLICY -> TextR.string.access_dnd_policy
        AccessRequirement.DEVICE_ADMIN -> TextR.string.access_device_admin
        AccessRequirement.CALENDAR -> TextR.string.access_calendar
        AccessRequirement.CONTACTS -> TextR.string.access_contacts
        AccessRequirement.CALL_LOG -> TextR.string.access_call_log
        AccessRequirement.SMS -> TextR.string.access_sms
        AccessRequirement.PHONE -> TextR.string.access_phone
        AccessRequirement.RECORD_AUDIO -> TextR.string.access_record_audio
        AccessRequirement.ACTIVITY_RECOGNITION -> TextR.string.access_activity_recognition
    }
)
