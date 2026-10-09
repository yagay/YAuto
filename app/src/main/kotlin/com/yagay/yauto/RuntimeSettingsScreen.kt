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
import com.yagay.yauto.platform.android.isUsageStatsAccessGranted
import com.yagay.yauto.ui.design.MacroItemRow
import com.yagay.yauto.ui.design.MacroPalette
import com.yagay.yauto.ui.design.localizedList
import com.yagay.yauto.ui.design.rememberPageNavigation
import com.yagay.yauto.ui.design.PageBackHandler
import com.yagay.yauto.ui.design.PageBackButton
import com.yagay.yauto.ui.design.R as TextR
import kotlinx.coroutines.launch

private enum class RuntimeSettingsPage { OVERVIEW, PERMISSIONS, BACKENDS, ENGINE, HEALTH }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimeSettingsScreen(graph: AppGraph, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val notChecked = stringResource(TextR.string.common_not_checked)
    val checking = stringResource(TextR.string.common_checking)
    var refresh by remember { mutableIntStateOf(0) }
    var backendMessage by remember(notChecked) { mutableStateOf(notChecked) }
    var lsposedScopeMessage by remember { mutableStateOf<String?>(null) }
    var navigation by rememberPageNavigation(RuntimeSettingsPage.OVERVIEW)
    val page = navigation.current

    fun navigateTo(destination: RuntimeSettingsPage) {
        navigation = navigation.forward(destination)
    }

    fun navigateBack() {
        val previous = navigation.back()
        if (previous != null) navigation = previous else onBack()
    }

    // Nested settings pages consume system/gesture back before the app shell.
    PageBackHandler(enabled = navigation.canGoBack, onBack = ::navigateBack)
    val scope = rememberCoroutineScope()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    DisposableEffect(graph.workspace) {
        val subscription = graph.workspace.addListener {
            scope.launch { refresh++ }
        }
        onDispose { subscription.close() }
    }

    val notificationAccess = remember(refresh) {
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    }
    val notifications = remember(refresh) {
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val camera = remember(refresh) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    val location = remember(refresh) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    val bluetooth = remember(refresh) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }
    val overlay = remember(refresh) { Settings.canDrawOverlays(context) }
    val dndPolicy = remember(refresh) {
        context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
    }
    val writeSettings = remember(refresh) { Settings.System.canWrite(context) }
    val usageStats = remember(refresh) { isUsageStatsAccessGranted(context) }
    val accessibility = remember(refresh) {
        val component = ComponentName(context, YAutoAccessibilityService::class.java).flattenToString()
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')
            ?.any { it.equals(component, ignoreCase = true) } == true
    }
    val grantedCount = listOf(
        accessibility, usageStats, writeSettings, notificationAccess, notifications, camera,
        location, bluetooth, overlay, dndPolicy,
    ).count { it }
    val currentLanguageTag = remember(refresh) { AppLanguageManager.currentTag(context) }

    LaunchedEffect(page) {
        if (page == RuntimeSettingsPage.BACKENDS) {
            runCatching { graph.lsposedScopes.start() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (page) {
                            RuntimeSettingsPage.OVERVIEW -> stringResource(TextR.string.runtime_settings_title_overview)
                            RuntimeSettingsPage.PERMISSIONS -> stringResource(TextR.string.runtime_settings_title_permissions)
                            RuntimeSettingsPage.BACKENDS -> stringResource(TextR.string.runtime_settings_title_backends)
                            RuntimeSettingsPage.ENGINE -> stringResource(TextR.string.runtime_settings_title_engine)
                            RuntimeSettingsPage.HEALTH -> stringResource(TextR.string.runtime_settings_title_health)
                        }
                    )
                },
                navigationIcon = {
                    PageBackButton(onBack = ::navigateBack)
                },
            )
        },
    ) { padding ->
        when (page) {
            RuntimeSettingsPage.OVERVIEW -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MacroPalette.State.copy(alpha = .10f))) {
                        Column(Modifier.padding(14.dp)) {
                            Text(stringResource(TextR.string.runtime_settings_capability_center), fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(TextR.string.runtime_settings_capability_center_description),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                item {
                    LanguageCard(
                        selectedTag = currentLanguageTag,
                        onSelect = { tag ->
                            (context as? android.app.Activity)?.let { AppLanguageManager.set(it, tag) }
                        },
                    )
                }
                item {
                    MacroItemRow(
                        stringResource(TextR.string.runtime_settings_android_permissions),
                        stringResource(TextR.string.runtime_settings_permission_count_format, grantedCount, 10),
                        MacroPalette.Constraint,
                        onClick = { navigateTo(RuntimeSettingsPage.PERMISSIONS) },
                    )
                }
                item {
                    MacroItemRow(
                        stringResource(TextR.string.runtime_settings_privileged_backends),
                        stringResource(TextR.string.runtime_settings_privileged_backends_subtitle),
                        MacroPalette.Flow,
                        onClick = { navigateTo(RuntimeSettingsPage.BACKENDS) },
                    )
                }
                item {
                    MacroItemRow(
                        stringResource(TextR.string.runtime_settings_engine),
                        stringResource(TextR.string.runtime_settings_engine_subtitle),
                        MacroPalette.Action,
                        onClick = { navigateTo(RuntimeSettingsPage.ENGINE) },
                    )
                }
                item {
                    val scanning by graph.featureHealth.scanning.collectAsState()
                    val autoScan by graph.featureHealth.autoScanEnabled.collectAsState()
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                stringResource(TextR.string.feature_health_quick_title),
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                stringResource(TextR.string.feature_health_auto_scan_detail),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    stringResource(TextR.string.feature_health_auto_scan),
                                    Modifier.weight(1f),
                                )
                                Switch(
                                    checked = autoScan,
                                    onCheckedChange = graph.featureHealth::setAutoScanEnabled,
                                )
                            }
                            Button(
                                onClick = { graph.featureHealth.requestScan(force = true) },
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
                        }
                    }
                }
                item {
                    val snapshot by graph.featureHealth.snapshot.collectAsState()
                    val scanning by graph.featureHealth.scanning.collectAsState()
                    val autoScan by graph.featureHealth.autoScanEnabled.collectAsState()
                    val subtitle = when {
                        scanning -> stringResource(TextR.string.feature_health_scanning)
                        snapshot != null -> stringResource(
                            TextR.string.feature_health_summary_format,
                            snapshot!!.items.size,
                            snapshot!!.readyCount,
                            snapshot!!.blockedCount,
                            snapshot!!.brokenCount,
                            snapshot!!.unsupportedCount,
                        )
                        autoScan -> stringResource(TextR.string.feature_health_auto_on_not_scanned)
                        else -> stringResource(TextR.string.feature_health_not_scanned)
                    }
                    MacroItemRow(
                        stringResource(TextR.string.runtime_settings_feature_health),
                        subtitle,
                        MacroPalette.Diagnostics,
                        onClick = { navigateTo(RuntimeSettingsPage.HEALTH) },
                    )
                }
            }

            RuntimeSettingsPage.PERMISSIONS -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    PermissionCard(
                        stringResource(TextR.string.permission_accessibility_title), accessibility,
                        stringResource(TextR.string.permission_accessibility_detail),
                        stringResource(TextR.string.permission_accessibility_action),
                    ) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                }
                item {
                    PermissionCard(
                        stringResource(TextR.string.permission_usage_stats_title), usageStats,
                        stringResource(TextR.string.permission_usage_stats_detail),
                        stringResource(TextR.string.permission_usage_stats_action),
                    ) { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                }
                item {
                    PermissionCard(
                        stringResource(TextR.string.permission_write_settings_title), writeSettings,
                        stringResource(TextR.string.permission_write_settings_detail),
                        stringResource(TextR.string.permission_write_settings_action),
                    ) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
                    }
                }
                item {
                    PermissionCard(
                        stringResource(TextR.string.permission_notification_listener_title), notificationAccess,
                        stringResource(TextR.string.permission_notification_listener_detail),
                        stringResource(TextR.string.permission_notification_listener_action),
                    ) { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                }
                item {
                    PermissionCard(
                        stringResource(TextR.string.permission_notifications_title), notifications,
                        stringResource(TextR.string.permission_notifications_detail),
                        stringResource(TextR.string.permission_notifications_action),
                    ) {
                        if (!notifications && Build.VERSION.SDK_INT >= 33) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }
                item {
                    PermissionCard(
                        stringResource(TextR.string.permission_camera_title), camera,
                        stringResource(TextR.string.permission_camera_detail),
                        stringResource(TextR.string.permission_camera_action),
                    ) { if (!camera) permissionLauncher.launch(Manifest.permission.CAMERA) }
                }
                item {
                    PermissionCard(
                        stringResource(TextR.string.permission_location_title), location,
                        stringResource(TextR.string.permission_location_detail),
                        stringResource(TextR.string.permission_location_action),
                    ) { if (!location) permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
                }
                item {
                    PermissionCard(
                        stringResource(TextR.string.permission_bluetooth_title), bluetooth,
                        stringResource(TextR.string.permission_bluetooth_detail),
                        stringResource(TextR.string.permission_bluetooth_action),
                    ) {
                        if (!bluetooth && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        }
                    }
                }
                item {
                    PermissionCard(
                        stringResource(TextR.string.permission_overlay_title), overlay,
                        stringResource(TextR.string.permission_overlay_detail),
                        stringResource(TextR.string.permission_overlay_action),
                    ) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                    }
                }
                item {
                    PermissionCard(
                        stringResource(TextR.string.permission_dnd_title), dndPolicy,
                        stringResource(TextR.string.permission_dnd_detail),
                        stringResource(TextR.string.permission_dnd_action),
                    ) { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
                }
            }

            RuntimeSettingsPage.BACKENDS -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    val rootAvailable = stringResource(TextR.string.backend_root_available)
                    val rootUnavailable = stringResource(TextR.string.backend_root_unavailable)
                    BackendCard(
                        stringResource(TextR.string.backend_root_title),
                        stringResource(TextR.string.backend_root_detail),
                        backendMessage,
                    ) {
                        backendMessage = checking
                        scope.launch {
                            backendMessage = if (graph.rootShell.isAvailable()) rootAvailable else rootUnavailable
                        }
                    }
                }
                item {
                    val returnHint = stringResource(TextR.string.backend_shizuku_return_hint)
                    BackendCard(
                        stringResource(TextR.string.backend_shizuku_title),
                        stringResource(TextR.string.backend_shizuku_detail),
                        if (graph.shizuku.hasPermission()) {
                            stringResource(TextR.string.backend_shizuku_ready)
                        } else {
                            stringResource(TextR.string.backend_shizuku_unavailable)
                        },
                        authorize = true,
                    ) {
                        backendMessage = runCatching {
                            graph.shizuku.requestPermission()
                            returnHint
                        }.getOrElse { it.message.orEmpty() }
                        refresh++
                    }
                }
                item {
                    BackendCard(
                        stringResource(TextR.string.backend_lsposed_title),
                        stringResource(TextR.string.backend_lsposed_detail),
                        backendMessage,
                    ) {
                        scope.launch {
                            backendMessage = graph.xposed.status().message.orEmpty()
                            refresh++
                        }
                    }
                }
                item {
                    val scopeState by graph.lsposedScopes.state.collectAsState()
                    val workspaceSnapshot = graph.workspace.snapshotOrNull()
                    val recommendedScopes = remember(refresh, workspaceSnapshot) {
                        workspaceSnapshot?.let { recommendedLsposedScopes(it, graph.features) }
                            ?: LsposedScopeManager.FIXED_SCOPES
                    }
                    val missingScopes = remember(scopeState.currentScope, recommendedScopes) {
                        recommendedScopes.filterNot(scopeState.currentScope.toSet()::contains)
                    }
                    val requestedMessage = stringResource(TextR.string.backend_lsposed_scope_requested)
                    LsposedScopeCard(
                        state = scopeState,
                        recommended = recommendedScopes,
                        missing = missingScopes,
                        message = lsposedScopeMessage,
                        onRefresh = {
                            graph.lsposedScopes.refresh()
                            refresh++
                        },
                        onRequest = {
                            graph.lsposedScopes.requestScopes(recommendedScopes) { result ->
                                scope.launch {
                                    lsposedScopeMessage = result.fold(
                                        onSuccess = { requestedMessage },
                                        onFailure = { error ->
                                            context.getString(
                                                TextR.string.backend_lsposed_scope_request_failed_format,
                                                error.message ?: error.javaClass.simpleName,
                                            )
                                        },
                                    )
                                    refresh++
                                }
                            }
                        },
                    )
                }
            }

            RuntimeSettingsPage.ENGINE -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { EngineCard(stringResource(TextR.string.engine_capability_broker_title), stringResource(TextR.string.engine_capability_broker_detail)) }
                item { EngineCard(stringResource(TextR.string.engine_fallback_title), stringResource(TextR.string.engine_fallback_detail)) }
                item { EngineCard(stringResource(TextR.string.engine_backend_selection_title), stringResource(TextR.string.engine_backend_selection_detail)) }
                item { EngineCard(stringResource(TextR.string.engine_execution_safety_title), stringResource(TextR.string.engine_execution_safety_detail)) }
                item { EngineCard(stringResource(TextR.string.engine_execution_trace_title), stringResource(TextR.string.engine_execution_trace_detail)) }
            }

            RuntimeSettingsPage.HEALTH -> FeatureHealthScreen(
                modifier = Modifier.padding(padding),
                scanner = graph.featureHealth,
            )
        }
    }
}

@Composable
private fun LanguageCard(selectedTag: String, onSelect: (String) -> Unit) {
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
private fun PermissionCard(
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
private fun BackendCard(
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
private fun LsposedScopeCard(
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
private fun EngineCard(title: String, detail: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
