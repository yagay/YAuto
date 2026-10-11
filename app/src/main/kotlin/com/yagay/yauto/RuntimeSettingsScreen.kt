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
    OVERVIEW, PERMISSIONS, BACKENDS, RUNTIME, EDITOR, LOGGING, ENGINE, LIBRARIES, APPEARANCE, RECORDS, AUDIO_RECORDS, GESTURES, DANMU, WEBDAV, WORKDAY, HEALTH
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimeSettingsScreen(graph: AppGraph, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val notChecked = stringResource(TextR.string.common_not_checked)
    val checking = stringResource(TextR.string.common_checking)
    var refresh by remember { mutableIntStateOf(0) }
    var showRootExclusive by remember(context) {
        mutableStateOf(FeatureVisibilityPreferences.showRootExclusive(context))
    }
    val liveAccess by graph.accessStatus.status.collectAsState()
    val rootDetected: Boolean? = when (liveAccess[com.yagay.yauto.core.registry.AccessRequirement.ROOT]) {
        com.yagay.yauto.ui.editor.PermissionAvailability.GRANTED -> true
        com.yagay.yauto.ui.editor.PermissionAvailability.NOT_GRANTED -> false
        else -> null
    }
    var preferRoot by remember(context) {
        mutableStateOf(RuntimeSettingsPreferences.preferRootWhenAvailable(context))
    }
    var shizukuMessage by remember(notChecked) { mutableStateOf(notChecked) }
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
    val currentLanguageTag = remember(refresh) { AppLanguageManager.currentTag(context) }

    LaunchedEffect(page) {
        if (page == RuntimeSettingsPage.HEALTH) graph.featureHealth.requestScan(force = true)
    }

    LaunchedEffect(page, refresh) {
        if (page == RuntimeSettingsPage.BACKENDS ||
            page == RuntimeSettingsPage.PERMISSIONS
        ) {
            // Never probe Shizuku before verifying Root, even from settings.
            graph.accessStatus.refresh(force = true)
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
                            RuntimeSettingsPage.RUNTIME -> stringResource(TextR.string.settings_runtime_background)
                            RuntimeSettingsPage.EDITOR -> stringResource(TextR.string.settings_editor_defaults)
                            RuntimeSettingsPage.LOGGING -> stringResource(TextR.string.settings_logs_title)
                            RuntimeSettingsPage.ENGINE -> stringResource(TextR.string.runtime_settings_title_engine)
                            RuntimeSettingsPage.LIBRARIES -> stringResource(TextR.string.sx_libraries)
                            RuntimeSettingsPage.APPEARANCE -> stringResource(TextR.string.sx_appearance)
                            RuntimeSettingsPage.RECORDS -> stringResource(TextR.string.sx_records)
                            RuntimeSettingsPage.AUDIO_RECORDS -> stringResource(TextR.string.sx_audio_records)
                            RuntimeSettingsPage.GESTURES -> stringResource(TextR.string.sx_gesture_library)
                            RuntimeSettingsPage.DANMU -> stringResource(TextR.string.sx_danmu)
                            RuntimeSettingsPage.WEBDAV -> stringResource(TextR.string.sx_webdav)
                            RuntimeSettingsPage.WORKDAY -> stringResource(TextR.string.sx_workday)
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
                        stringResource(TextR.string.runtime_settings_permission_count_format,
                            grantedPermissionGroupCount(context), permissionGrantGroups(Build.VERSION.SDK_INT).size),
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
                        stringResource(TextR.string.settings_runtime_background),
                        stringResource(TextR.string.settings_runtime_background_description),
                        MacroPalette.State,
                        onClick = { navigateTo(RuntimeSettingsPage.RUNTIME) },
                    )
                }
                item {
                    MacroItemRow(
                        stringResource(TextR.string.settings_editor_defaults),
                        stringResource(TextR.string.settings_editor_defaults_description),
                        MacroPalette.Variable,
                        onClick = { navigateTo(RuntimeSettingsPage.EDITOR) },
                    )
                }
                item {
                    MacroItemRow(
                        stringResource(TextR.string.settings_logs_title),
                        stringResource(TextR.string.settings_logs_description),
                        MacroPalette.Diagnostics,
                        onClick = { navigateTo(RuntimeSettingsPage.LOGGING) },
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
                    MacroItemRow(
                        stringResource(TextR.string.sx_libraries),
                        stringResource(TextR.string.sx_library_hint),
                        MacroPalette.Utility,
                        onClick = { navigateTo(RuntimeSettingsPage.LIBRARIES) },
                    )
                }
                item {
                    MacroItemRow(
                        stringResource(TextR.string.sx_appearance),
                        stringResource(TextR.string.sx_appearance_hint),
                        MacroPalette.State,
                        onClick = { navigateTo(RuntimeSettingsPage.APPEARANCE) },
                    )
                }
                item {
                    MacroItemRow(
                        stringResource(TextR.string.sx_records),
                        stringResource(TextR.string.sx_records_hint),
                        MacroPalette.Diagnostics,
                        onClick = { navigateTo(RuntimeSettingsPage.RECORDS) },
                    )
                }
                item {
                    MacroItemRow(
                        stringResource(TextR.string.sx_audio_records),
                        stringResource(TextR.string.sx_audio_records_hint),
                        MacroPalette.Diagnostics,
                        onClick = { navigateTo(RuntimeSettingsPage.AUDIO_RECORDS) },
                    )
                }
                item {
                    MacroItemRow(
                        stringResource(TextR.string.sx_gesture_library),
                        stringResource(TextR.string.sx_gesture_hint),
                        MacroPalette.Action,
                        onClick = { navigateTo(RuntimeSettingsPage.GESTURES) },
                    )
                }
                item {
                    MacroItemRow(
                        stringResource(TextR.string.sx_danmu),
                        stringResource(TextR.string.sx_danmu_hint),
                        MacroPalette.Utility,
                        onClick = { navigateTo(RuntimeSettingsPage.DANMU) },
                    )
                }
                item {
                    MacroItemRow(
                        stringResource(TextR.string.sx_webdav),
                        stringResource(TextR.string.sx_webdav_hint),
                        MacroPalette.Utility,
                        onClick = { navigateTo(RuntimeSettingsPage.WEBDAV) },
                    )
                }
                item {
                    MacroItemRow(
                        stringResource(TextR.string.sx_workday),
                        stringResource(TextR.string.sx_workday_hint),
                        MacroPalette.State,
                        onClick = { navigateTo(RuntimeSettingsPage.WORKDAY) },
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

            RuntimeSettingsPage.PERMISSIONS -> PermissionGrantCenter(
                modifier = Modifier.padding(padding), graph = graph,
            )

            RuntimeSettingsPage.RUNTIME -> RuntimeBackgroundPreferencesPage(
                context = context, refresh = refresh,
            )
            RuntimeSettingsPage.EDITOR -> EditorPreferencesPage(context)
            RuntimeSettingsPage.LOGGING -> LogPreferencesPage(context, graph)

            RuntimeSettingsPage.BACKENDS -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    SettingsToggleCard(
                        stringResource(TextR.string.backend_prefer_root_title),
                        stringResource(TextR.string.backend_prefer_root_description),
                        preferRoot,
                    ) { value ->
                        RuntimeSettingsPreferences.setPreferRootWhenAvailable(context, value)
                        preferRoot = value
                    }
                }
                item {
                    val rootAvailable = stringResource(TextR.string.backend_root_available)
                    val rootUnavailable = stringResource(TextR.string.backend_root_unavailable)
                    BackendCard(
                        stringResource(TextR.string.backend_root_title),
                        stringResource(TextR.string.backend_root_detail),
                        when (rootDetected) {
                            true -> rootAvailable
                            false -> rootUnavailable
                            null -> stringResource(TextR.string.runtime_access_unknown)
                        },
                    ) {
                        scope.launch {
                            graph.accessStatus.refresh(force = true)
                            refresh++
                        }
                    }
                }
                item {
                    val returnHint = stringResource(TextR.string.backend_shizuku_return_hint)
                    val grantedText = stringResource(TextR.string.backend_shizuku_ready)
                    val deniedText = stringResource(TextR.string.permission_request_denied)
                    val missingText = stringResource(TextR.string.backend_shizuku_unavailable)
                    when (rootDetected) {
                        true -> EngineCard(
                            stringResource(TextR.string.backend_shizuku_title),
                            stringResource(TextR.string.sx_shizuku_root_blocked),
                        )
                        null -> EngineCard(
                            stringResource(TextR.string.backend_shizuku_title), checking,
                        )
                        false -> BackendCard(
                            stringResource(TextR.string.backend_shizuku_title),
                            stringResource(TextR.string.backend_shizuku_detail),
                            when (liveAccess[com.yagay.yauto.core.registry.AccessRequirement.SHIZUKU]) {
                                com.yagay.yauto.ui.editor.PermissionAvailability.GRANTED -> grantedText
                                com.yagay.yauto.ui.editor.PermissionAvailability.NOT_GRANTED -> missingText
                                else -> stringResource(TextR.string.runtime_access_unknown)
                            },
                            authorize = true,
                        ) {
                            scope.launch {
                                val actual = graph.accessStatus.refresh(
                                    setOf(
                                        com.yagay.yauto.core.registry.AccessRequirement.ROOT,
                                        com.yagay.yauto.core.registry.AccessRequirement.SHIZUKU,
                                    ), force = true,
                                )
                                when (actual[com.yagay.yauto.core.registry.AccessRequirement.ROOT]) {
                                    com.yagay.yauto.ui.editor.PermissionAvailability.GRANTED -> {
                                        shizukuMessage = context.getString(TextR.string.sx_shizuku_root_blocked)
                                    }
                                    com.yagay.yauto.ui.editor.PermissionAvailability.UNKNOWN, null -> {
                                        shizukuMessage = context.getString(TextR.string.runtime_access_unknown)
                                    }
                                    com.yagay.yauto.ui.editor.PermissionAvailability.NOT_GRANTED -> {
                                        shizukuMessage = runCatching {
                                        graph.shizuku.requestPermission { granted ->
                                            shizukuMessage = if (granted) grantedText else deniedText
                                            refresh++
                                            scope.launch { graph.accessStatus.refresh(force = true) }
                                        }
                                        returnHint
                                        }.getOrElse { it.message ?: missingText }
                                    }
                                }
                                refresh++
                            }
                        }
                    }
                }
                item {
                    BackendCard(
                        stringResource(TextR.string.backend_lsposed_title),
                        stringResource(TextR.string.backend_lsposed_detail),
                        when (liveAccess[com.yagay.yauto.core.registry.AccessRequirement.LSPOSED]) {
                            com.yagay.yauto.ui.editor.PermissionAvailability.GRANTED ->
                                stringResource(TextR.string.common_granted)
                            com.yagay.yauto.ui.editor.PermissionAvailability.NOT_GRANTED ->
                                stringResource(TextR.string.common_not_granted)
                            else -> stringResource(TextR.string.runtime_access_unknown)
                        },
                    ) {
                        scope.launch {
                            graph.accessStatus.refresh(force = true)
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

            RuntimeSettingsPage.ENGINE -> Column(Modifier.padding(padding)) {
                ShortXEngineSettingsScreen(context, graph) {
                    navigateTo(RuntimeSettingsPage.LIBRARIES)
                }
            }
            RuntimeSettingsPage.LIBRARIES -> Column(Modifier.padding(padding)) {
                ShortXLibrarySettingsScreen(context)
            }
            RuntimeSettingsPage.APPEARANCE -> Column(Modifier.padding(padding)) {
                ShortXAppearanceSettingsScreen(context)
            }
            RuntimeSettingsPage.RECORDS -> Column(Modifier.padding(padding)) {
                ShortXRecordsScreen(context, graph)
            }

            RuntimeSettingsPage.AUDIO_RECORDS -> Column(Modifier.padding(padding)) {
                ShortXAudioRecordsScreen(context)
            }
            RuntimeSettingsPage.GESTURES -> Column(Modifier.padding(padding)) {
                ShortXGestureLibraryScreen(context, graph)
            }
            RuntimeSettingsPage.DANMU -> Column(Modifier.padding(padding)) {
                ShortXDanmuSettingsScreen(context, graph)
            }
            RuntimeSettingsPage.WEBDAV -> Column(Modifier.padding(padding)) {
                ShortXWebDavScreen(context, graph)
            }
            RuntimeSettingsPage.WORKDAY -> Column(Modifier.padding(padding)) {
                ShortXWorkdayScreen(context)
            }
            RuntimeSettingsPage.HEALTH -> FeatureHealthScreen(
                modifier = Modifier.padding(padding),
                scanner = graph.featureHealth,
            )
        }
    }
}
