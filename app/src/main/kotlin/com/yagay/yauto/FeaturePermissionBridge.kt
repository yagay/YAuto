package com.yagay.yauto

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yagay.yauto.core.registry.AccessRequirement
import com.yagay.yauto.ui.editor.FeaturePermissionGateway
import com.yagay.yauto.ui.editor.PermissionAvailability
import com.yagay.yauto.ui.design.R as TextR
import kotlinx.coroutines.launch

/**
 * The feature editor delegates authorization to the app rather than embedding
 * another permission implementation in the UI library.
 */
internal fun permissionGroupId(requirement: AccessRequirement): String? = when (requirement) {
    AccessRequirement.ACCESSIBILITY -> "accessibility"
    AccessRequirement.USAGE_STATS -> "usage"
    AccessRequirement.NOTIFICATION_LISTENER -> "listener"
    AccessRequirement.POST_NOTIFICATIONS -> "notifications"
    AccessRequirement.OVERLAY -> "overlay"
    AccessRequirement.WRITE_SETTINGS -> "write"
    AccessRequirement.CAMERA -> "camera"
    AccessRequirement.LOCATION -> "location"
    AccessRequirement.BLUETOOTH_CONNECT -> "bluetooth"
    AccessRequirement.DND_POLICY -> "dnd"
    AccessRequirement.DEVICE_ADMIN -> "device_admin"
    AccessRequirement.CALENDAR -> "calendar"
    AccessRequirement.CONTACTS -> "contacts"
    AccessRequirement.CALL_LOG -> "call_log"
    AccessRequirement.SMS -> "sms"
    AccessRequirement.PHONE -> "phone"
    AccessRequirement.RECORD_AUDIO -> "microphone"
    AccessRequirement.ACTIVITY_RECOGNITION -> "activity"
    AccessRequirement.ROOT, AccessRequirement.LSPOSED,
    AccessRequirement.SHIZUKU, AccessRequirement.ZYGISK -> null
}

@Composable
internal fun rememberFeaturePermissionGateway(graph: AppGraph): FeaturePermissionGateway {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var permissionVersion by remember { mutableIntStateOf(0) }
    var rootPermission by remember { mutableStateOf<Boolean?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val groups = remember { permissionGrantGroups(Build.VERSION.SDK_INT).associateBy { it.id } }

    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        permissionVersion++
        message = if (results.isEmpty() || results.values.any { !it }) {
            context.getString(TextR.string.permission_request_denied)
        } else null
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionVersion++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Recreate the gateway when a returned Settings screen changes the grants.
    return remember(context, graph, permissionVersion, rootPermission, message, runtimeLauncher) {
        object : FeaturePermissionGateway {
            override val feedback: String? get() = message

            override fun availability(permission: AccessRequirement): PermissionAvailability {
                val granted: Boolean? = when (permission) {
                    AccessRequirement.ROOT -> rootPermission
                    AccessRequirement.SHIZUKU ->
                        runCatching { graph.shizuku.hasPermission() }.getOrDefault(false)
                    AccessRequirement.LSPOSED -> graph.lsposedScopes.state.value.connected &&
                        graph.lsposedScopes.state.value.currentScope.containsAll(
                            LsposedScopeManager.FIXED_SCOPES)
                    // Zygisk cannot be enabled through an Android permission request.
                    AccessRequirement.ZYGISK -> null
                    AccessRequirement.POST_NOTIFICATIONS -> if (Build.VERSION.SDK_INT < 33) true
                        else groups["notifications"]?.let { permissionStatus(context, it) }
                    AccessRequirement.BLUETOOTH_CONNECT -> if (Build.VERSION.SDK_INT < 31) true
                        else ContextCompat.checkSelfPermission(context,
                            Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    AccessRequirement.ACTIVITY_RECOGNITION -> if (Build.VERSION.SDK_INT < 29) true
                        else groups["activity"]?.let { permissionStatus(context, it) }
                    AccessRequirement.LOCATION -> listOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ).any {
                        ContextCompat.checkSelfPermission(context, it) ==
                            PackageManager.PERMISSION_GRANTED
                    }
                    AccessRequirement.SMS -> listOf(Manifest.permission.READ_SMS,
                        Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS).any {
                        ContextCompat.checkSelfPermission(context, it) ==
                            PackageManager.PERMISSION_GRANTED
                    }
                    AccessRequirement.PHONE -> ContextCompat.checkSelfPermission(context,
                        Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
                    else -> permissionGroupId(permission)?.let(groups::get)?.let {
                        permissionStatus(context, it)
                    }
                }
                return when (granted) {
                    true -> PermissionAvailability.GRANTED
                    false -> PermissionAvailability.NOT_GRANTED
                    null -> PermissionAvailability.UNKNOWN
                }
            }

            override fun request(permission: AccessRequirement) {
                fun inform(text: String) { message = text }
                when (permission) {
                    AccessRequirement.ROOT -> scope.launch {
                        val result = runCatching { graph.rootShell.isAvailable() }.getOrDefault(false)
                        rootPermission = result
                        inform(context.getString(if (result) TextR.string.backend_root_available
                            else TextR.string.backend_root_unavailable))
                    }
                    AccessRequirement.SHIZUKU -> {
                        runCatching {
                            graph.shizuku.requestPermission { granted ->
                                inform(context.getString(if (granted)
                                    TextR.string.backend_shizuku_ready
                                else TextR.string.permission_request_denied))
                                permissionVersion++
                            }
                        }.onFailure { error ->
                            inform(error.message ?: context.getString(
                                TextR.string.backend_shizuku_unavailable))
                        }
                    }
                    AccessRequirement.LSPOSED -> {
                        runCatching { graph.lsposedScopes.start() }
                        val recommended = graph.workspace.snapshotOrNull()?.let {
                            recommendedLsposedScopes(it, graph.features)
                        } ?: LsposedScopeManager.FIXED_SCOPES
                        graph.lsposedScopes.requestScopes(recommended) { result ->
                            inform(result.fold(
                                onSuccess = { context.getString(TextR.string.backend_lsposed_scope_requested) },
                                onFailure = { it.message ?: context.getString(
                                    TextR.string.feature_permission_lsposed_hint) },
                            ))
                            permissionVersion++
                        }
                    }
                    AccessRequirement.ZYGISK ->
                        inform(context.getString(TextR.string.feature_permission_zygisk_hint))
                    else -> {
                        val group = permissionGroupId(permission)?.let(groups::get)
                        if (group == null) {
                            openPermissionSettings(context, applicationDetailsIntent(context), ::inform)
                            return
                        }
                        if (group.settingsAction != null || group.deviceAdmin) {
                            openPermissionSettings(context, permissionSettingsIntent(context, group), ::inform)
                            return
                        }
                        if (availability(permission) == PermissionAvailability.GRANTED) {
                            openPermissionSettings(context, applicationDetailsIntent(context), ::inform)
                            return
                        }
                        val missing = missingRuntimePermissions(group) {
                            ContextCompat.checkSelfPermission(context, it) ==
                                PackageManager.PERMISSION_GRANTED
                        }
                        if (missing.isEmpty()) {
                            openPermissionSettings(context, applicationDetailsIntent(context), ::inform)
                        } else {
                            runCatching { runtimeLauncher.launch(missing.toTypedArray()) }
                                .onFailure {
                                    openPermissionSettings(context,
                                        applicationDetailsIntent(context), ::inform)
                                }
                        }
                    }
                }
            }
        }
    }
}
