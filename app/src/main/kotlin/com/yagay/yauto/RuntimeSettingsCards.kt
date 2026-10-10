package com.yagay.yauto

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yagay.yauto.platform.accessibility.YAutoAccessibilityService
import com.yagay.yauto.ui.editor.FeatureVisibilityPreferences
import com.yagay.yauto.platform.android.isUsageStatsAccessGranted
import com.yagay.yauto.ui.design.MacroItemRow
import com.yagay.yauto.ui.design.MacroPalette
import com.yagay.yauto.ui.design.localizedList
import com.yagay.yauto.ui.design.rememberPageNavigation
import com.yagay.yauto.ui.design.PageBackHandler
import com.yagay.yauto.ui.design.PageBackButton
import com.yagay.yauto.ui.design.R as TextR
import kotlinx.coroutines.launch

private enum class RuntimeSettingsPage {
    OVERVIEW, PERMISSIONS, BACKENDS, RUNTIME, EDITOR, LOGGING, ENGINE, HEALTH
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanguageCard(selectedTag: String, onSelect: (String) -> Unit) {
    val options = listOf(
        AppLanguageManager.SYSTEM to stringResource(TextR.string.language_system),
        AppLanguageManager.ENGLISH to stringResource(TextR.string.language_english),
        AppLanguageManager.SIMPLIFIED_CHINESE to stringResource(TextR.string.language_simplified_chinese),
    )
    val selectedLabel = options.firstOrNull { it.first == selectedTag }?.second
        ?: options.first().second

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(TextR.string.language_title), fontWeight = FontWeight.SemiBold)
            Text(stringResource(TextR.string.language_subtitle), style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(TextR.string.language_current_format, selectedLabel),
                style = MaterialTheme.typography.labelMedium,
            )
            options.forEach { (tag, label) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RadioButton(selected = selectedTag == tag, onClick = { onSelect(tag) })
                    TextButton(onClick = { onSelect(tag) }) { Text(label) }
                }
            }
            Text(stringResource(TextR.string.language_android_12_hint), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun PermissionCard(
    title: String,
    available: Boolean,
    detail: String,
    button: String,
    onClick: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text(
                    if (available) stringResource(TextR.string.common_granted) else stringResource(TextR.string.common_not_granted),
                    color = if (available) MacroPalette.Constraint else MacroPalette.Trigger,
                )
            }
            Text(detail, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(button) }
        }
    }
}

@Composable
internal fun BackendCard(
    title: String,
    detail: String,
    status: String,
    authorize: Boolean = false,
    onCheck: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(TextR.string.common_status_format, status), style = MaterialTheme.typography.labelMedium)
            OutlinedButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(
                        if (authorize) TextR.string.backend_authorize_refresh else TextR.string.backend_check_status
                    )
                )
            }
        }
    }
}

@Composable
internal fun LsposedScopeCard(
    state: LsposedScopeState,
    recommended: List<String>,
    missing: List<String>,
    message: String?,
    onRefresh: () -> Unit,
    onRequest: () -> Unit,
) {
    val notChecked = stringResource(TextR.string.common_not_checked)
    val currentText = localizedList(state.currentScope).ifBlank { notChecked }
    val recommendedText = localizedList(recommended).ifBlank { notChecked }
    val missingText = localizedList(missing)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(TextR.string.backend_lsposed_scope_title),
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(TextR.string.backend_lsposed_scope_detail),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(
                    if (state.connected) {
                        TextR.string.backend_lsposed_scope_connected
                    } else {
                        TextR.string.backend_lsposed_scope_disconnected
                    }
                ),
                style = MaterialTheme.typography.labelMedium,
                color = if (state.connected) MacroPalette.Constraint else MacroPalette.Trigger,
            )
            Text(
                stringResource(TextR.string.backend_lsposed_scope_current_format, currentText),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(TextR.string.backend_lsposed_scope_recommended_format, recommendedText),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (missing.isEmpty()) {
                    stringResource(TextR.string.backend_lsposed_scope_complete)
                } else {
                    stringResource(TextR.string.backend_lsposed_scope_missing_format, missingText)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (missing.isEmpty()) MacroPalette.Constraint else MacroPalette.Trigger,
            )
            state.frameworkName?.let { framework ->
                val version = state.frameworkVersion.orEmpty()
                Text(
                    stringResource(TextR.string.common_status_format, localizedList(listOf(framework, version).filter { it.isNotBlank() })),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onRefresh,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(TextR.string.backend_lsposed_scope_refresh))
                }
                Button(
                    onClick = onRequest,
                    enabled = state.connected && missing.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(TextR.string.backend_lsposed_scope_request))
                }
            }
        }
    }
}

@Composable
internal fun EngineCard(title: String, detail: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
