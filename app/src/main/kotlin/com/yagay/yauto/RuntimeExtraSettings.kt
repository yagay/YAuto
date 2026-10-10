package com.yagay.yauto

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.logging.TraceLevel
import com.yagay.yauto.ui.design.R as TextR
import com.yagay.yauto.ui.editor.EditorDisplayPreferences
import com.yagay.yauto.ui.editor.FeatureVisibilityPreferences
import kotlinx.coroutines.launch

@Composable
internal fun SettingsToggleCard(
    title: String, explanation: String, enabled: Boolean, onValue: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(explanation, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = enabled, onCheckedChange = onValue)
        }
    }
}

@Composable
internal fun RuntimeBackgroundPreferencesPage(context: Context, refresh: Int) {
    var startAtBoot by remember(context) {
        mutableStateOf(RuntimeSettingsPreferences.startAtBoot(context))
    }
    var settingsMessage by remember { mutableStateOf<String?>(null) }
    val batteryUnrestricted = remember(context, refresh) {
        runCatching {
            context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)
    }
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SettingsToggleCard(
                stringResource(TextR.string.settings_boot_autostart),
                stringResource(TextR.string.settings_boot_autostart_description),
                startAtBoot,
            ) { next ->
                RuntimeSettingsPreferences.setStartAtBoot(context, next)
                startAtBoot = next
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(TextR.string.settings_battery_optimization),
                        fontWeight = FontWeight.SemiBold)
                    Text(stringResource(
                        if (batteryUnrestricted) TextR.string.settings_battery_unrestricted
                        else TextR.string.settings_battery_restricted),
                        style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(TextR.string.settings_battery_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    settingsMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    OutlinedButton(onClick = {
                        openPermissionSettings(context,
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) {
                            settingsMessage = it
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(TextR.string.settings_open_battery_options))
                    }
                }
            }
        }
    }
}

@Composable
internal fun EditorPreferencesPage(context: Context) {
    var root by remember(context) {
        mutableStateOf(FeatureVisibilityPreferences.showRootExclusive(context))
    }
    var advanced by remember(context) {
        mutableStateOf(EditorDisplayPreferences.showAdvancedByDefault(context))
    }
    var systemApps by remember(context) {
        mutableStateOf(EditorDisplayPreferences.showSystemAppsByDefault(context))
    }
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SettingsToggleCard(
                stringResource(TextR.string.runtime_show_root_exclusive_title),
                stringResource(TextR.string.runtime_show_root_exclusive_detail),
                root,
            ) { value ->
                FeatureVisibilityPreferences.setShowRootExclusive(context, value)
                root = value
            }
        }
        item {
            SettingsToggleCard(
                stringResource(TextR.string.settings_advanced_default),
                stringResource(TextR.string.settings_advanced_default_description),
                advanced,
            ) { value ->
                EditorDisplayPreferences.setShowAdvancedByDefault(context, value)
                advanced = value
            }
        }
        item {
            SettingsToggleCard(
                stringResource(TextR.string.settings_system_apps_default),
                stringResource(TextR.string.settings_system_apps_default_description),
                systemApps,
            ) { value ->
                EditorDisplayPreferences.setShowSystemAppsByDefault(context, value)
                systemApps = value
            }
        }
    }
}

@Composable
internal fun LogPreferencesPage(context: Context, graph: AppGraph) {
    var level by remember(context) {
        mutableStateOf(RuntimeSettingsPreferences.traceLevel(context))
    }
    var size by remember(context) {
        mutableStateOf(RuntimeSettingsPreferences.logSizeMb(context))
    }
    var confirmClear by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val success = stringResource(TextR.string.settings_logs_cleared)
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(stringResource(TextR.string.settings_log_verbosity),
                        fontWeight = FontWeight.SemiBold)
                    Text(stringResource(TextR.string.settings_log_verbosity_description),
                        style = MaterialTheme.typography.bodySmall)
                    listOf(
                        TraceLevel.DEBUG to TextR.string.settings_log_debug,
                        TraceLevel.INFO to TextR.string.settings_log_info,
                        TraceLevel.WARN to TextR.string.settings_log_warn,
                        TraceLevel.ERROR to TextR.string.settings_log_error,
                    ).forEach { (choice, textId) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = level == choice, onClick = {
                                RuntimeSettingsPreferences.setTraceLevel(context, choice)
                                level = choice
                            })
                            Text(stringResource(textId))
                        }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(stringResource(TextR.string.settings_log_storage),
                        fontWeight = FontWeight.SemiBold)
                    Text(stringResource(TextR.string.settings_log_storage_description),
                        style = MaterialTheme.typography.bodySmall)
                    RuntimeSettingsPreferences.supportedLogSizesMb.forEach { mb ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = size == mb, onClick = {
                                RuntimeSettingsPreferences.setLogSizeMb(context, mb)
                                size = mb
                            })
                            Text(stringResource(TextR.string.settings_log_megabytes, mb))
                        }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(TextR.string.settings_logs_clear_title),
                        fontWeight = FontWeight.SemiBold)
                    Text(stringResource(TextR.string.settings_logs_clear_description),
                        style = MaterialTheme.typography.bodySmall)
                    feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    OutlinedButton(onClick = { confirmClear = true },
                        modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(TextR.string.settings_logs_clear_action))
                    }
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(TextR.string.settings_logs_clear_title)) },
            text = { Text(stringResource(TextR.string.settings_logs_clear_confirmation)) },
            confirmButton = {
                Button(onClick = {
                    confirmClear = false
                    scope.launch {
                        feedback = runCatching { graph.clearExecutionLogs(); success }
                            .getOrElse { it.message ?: it.javaClass.simpleName }
                    }
                }) { Text(stringResource(TextR.string.settings_logs_clear_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(TextR.string.common_cancel))
                }
            },
        )
    }
}
