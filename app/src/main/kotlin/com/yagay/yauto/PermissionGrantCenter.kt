package com.yagay.yauto

import android.Manifest
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.yagay.yauto.ui.design.R as TextR

/** Requestable permissions (multiple grants in one group, not one launcher per row). */
internal data class PermissionGrantGroup(
    val id: String,
    val title: Int,
    val detail: Int,
    val permissions: List<String> = emptyList(),
    val settingsAction: String? = null,
    val packageUri: Boolean = false,
    val requiresForegroundLocation: Boolean = false,
    val deviceAdmin: Boolean = false,
)

internal fun permissionGrantGroups(sdk: Int): List<PermissionGrantGroup> = buildList {
    fun runtime(id: String, title: Int, detail: Int, vararg names: String) =
        add(PermissionGrantGroup(id, title, detail, names.toList()))
    fun special(id: String, title: Int, detail: Int, action: String, packageUri: Boolean = false) =
        add(PermissionGrantGroup(id, title, detail, settingsAction = action, packageUri = packageUri))

    special("accessibility", TextR.string.permission_accessibility_title,
        TextR.string.permission_accessibility_detail, Settings.ACTION_ACCESSIBILITY_SETTINGS)
    special("usage", TextR.string.permission_usage_stats_title,
        TextR.string.permission_usage_stats_detail, Settings.ACTION_USAGE_ACCESS_SETTINGS)
    special("write", TextR.string.permission_write_settings_title,
        TextR.string.permission_write_settings_detail, Settings.ACTION_MANAGE_WRITE_SETTINGS, true)
    special("listener", TextR.string.permission_notification_listener_title,
        TextR.string.permission_notification_listener_detail, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    if (sdk >= 33) runtime("notifications", TextR.string.permission_notifications_title,
        TextR.string.permission_notifications_detail, Manifest.permission.POST_NOTIFICATIONS)
    runtime("camera", TextR.string.permission_camera_title,
        TextR.string.permission_camera_detail, Manifest.permission.CAMERA)
    runtime("location", TextR.string.permission_location_title,
        TextR.string.permission_location_detail,
        Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    add(PermissionGrantGroup("location_background", TextR.string.permission_background_location_title,
        TextR.string.permission_background_location_detail,
        permissions = listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        requiresForegroundLocation = true))
    if (sdk >= 31) runtime("bluetooth", TextR.string.permission_bluetooth_title,
        TextR.string.permission_bluetooth_detail,
        Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    if (sdk >= 33) runtime("wifi", TextR.string.permission_nearby_wifi_title,
        TextR.string.permission_nearby_wifi_detail, Manifest.permission.NEARBY_WIFI_DEVICES)
    runtime("microphone", TextR.string.permission_microphone_title,
        TextR.string.permission_microphone_detail, Manifest.permission.RECORD_AUDIO)
    runtime("contacts", TextR.string.permission_contacts_title,
        TextR.string.permission_contacts_detail, Manifest.permission.READ_CONTACTS)
    runtime("calendar", TextR.string.permission_calendar_title,
        TextR.string.permission_calendar_detail,
        Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    runtime("phone", TextR.string.permission_phone_title,
        TextR.string.permission_phone_detail,
        Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS,
        Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS)
    runtime("sms", TextR.string.permission_sms_title,
        TextR.string.permission_sms_detail,
        Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS)
    runtime("call_log", TextR.string.permission_call_log_title,
        TextR.string.permission_call_log_detail, Manifest.permission.READ_CALL_LOG)
    if (sdk >= 33) {
        val media = mutableListOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        if (sdk >= 34) media.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        add(PermissionGrantGroup("media", TextR.string.permission_media_title,
            TextR.string.permission_media_detail, permissions = media))
    } else runtime("media", TextR.string.permission_media_title,
        TextR.string.permission_media_detail, Manifest.permission.READ_EXTERNAL_STORAGE)
    if (sdk >= 29) runtime("activity", TextR.string.permission_activity_title,
        TextR.string.permission_activity_detail, Manifest.permission.ACTIVITY_RECOGNITION)
    special("overlay", TextR.string.permission_overlay_title,
        TextR.string.permission_overlay_detail, Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true)
    special("dnd", TextR.string.permission_dnd_title,
        TextR.string.permission_dnd_detail, Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
    add(PermissionGrantGroup("device_admin", TextR.string.permission_device_admin_title,
        TextR.string.permission_device_admin_detail, deviceAdmin = true))
}

/** The app itself must declare a runtime permission before the system can grant it. */
internal fun missingRuntimePermissions(
    group: PermissionGrantGroup,
    isGranted: (String) -> Boolean,
): List<String> = group.permissions.filterNot(isGranted)

private fun applicationDetailsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:" + context.packageName))

/** OEM settings screens can reject even documented actions: fall back to app info. */
internal fun openPermissionSettings(
    context: Context, primary: Intent, notify: (String) -> Unit,
): Boolean {
    val activity = context as? android.app.Activity
    fun tryOpen(intent: Intent): Boolean = try {
        val resolved = if (activity == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else intent
        context.startActivity(resolved)
        true
    } catch (_: Exception) { false }
    if (tryOpen(primary)) return true
    if (tryOpen(applicationDetailsIntent(context))) {
        notify(context.getString(TextR.string.permission_settings_fallback))
        return true
    }
    notify(context.getString(TextR.string.permission_settings_unavailable))
    return false
}

internal fun grantedPermissionGroupCount(context: Context): Int =
    permissionGrantGroups(Build.VERSION.SDK_INT).count { permissionStatus(context, it) }

private fun permissionStatus(context: Context, group: PermissionGrantGroup): Boolean {
    val granted: (String) -> Boolean = {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    return when (group.id) {
        "accessibility" -> {
            val target = ComponentName(context, YAutoAccessibilityService::class.java)
                .flattenToString()
            Settings.Secure.getString(context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.split(':')?.any { it.equals(target, ignoreCase = true) } == true
        }
        "usage" -> isUsageStatsAccessGranted(context)
        "write" -> Settings.System.canWrite(context)
        "listener" -> context.packageName in
            NotificationManagerCompat.getEnabledListenerPackages(context)
        "overlay" -> Settings.canDrawOverlays(context)
        "dnd" -> context.getSystemService(NotificationManager::class.java)
            .isNotificationPolicyAccessGranted
        "device_admin" -> context.getSystemService(DevicePolicyManager::class.java)
            .isAdminActive(ComponentName(context, YAutoDeviceAdminReceiver::class.java))
        "media" -> if (Build.VERSION.SDK_INT >= 34) {
            (granted(Manifest.permission.READ_MEDIA_IMAGES) &&
                granted(Manifest.permission.READ_MEDIA_VIDEO)) ||
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        } else group.permissions.all(granted)
        else -> group.permissions.all(granted)
    }
}

private fun permissionSettingsIntent(context: Context, group: PermissionGrantGroup): Intent =
    if (group.deviceAdmin) {
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                ComponentName(context, YAutoDeviceAdminReceiver::class.java))
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                context.getString(group.detail))
        }
    } else {
        Intent(group.settingsAction ?: Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            if (group.packageUri || group.settingsAction == null) {
                data = Uri.parse("package:" + context.packageName)
            }
        }
    }

/** Shared fully actionable permission center: no inert grant buttons. */
@Composable
internal fun PermissionGrantCenter(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var refresh by remember { mutableIntStateOf(0) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val deniedMessage = stringResource(TextR.string.permission_request_denied)
    val groups = remember { permissionGrantGroups(Build.VERSION.SDK_INT) }

    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        refresh++
        feedback = if (results.isEmpty() || results.values.any { !it }) deniedMessage else null
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val statuses = remember(refresh, context) { groups.associate { it.id to permissionStatus(context, it) } }

    fun manageAppPermissions() {
        openPermissionSettings(context, applicationDetailsIntent(context)) { feedback = it }
    }
    fun authorize(group: PermissionGrantGroup) {
        val hasForeground = ContextCompat.checkSelfPermission(context,
            Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (group.requiresForegroundLocation) {
            // Android 11+ grants background location in the app's settings page, not
            // from a simultaneous foreground/background runtime request.
            feedback = context.getString(
                if (hasForeground) TextR.string.permission_background_location_settings
                else TextR.string.permission_background_location_first)
            if (hasForeground) manageAppPermissions()
            return
        }
        if (group.settingsAction != null || group.deviceAdmin) {
            openPermissionSettings(context, permissionSettingsIntent(context, group)) { feedback = it }
            return
        }
        val pending = missingRuntimePermissions(group) {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (pending.isEmpty()) {
            manageAppPermissions()
            return
        }
        try {
            request.launch(pending.toTypedArray())
        } catch (_: Exception) {
            feedback = context.getString(TextR.string.permission_settings_fallback)
            manageAppPermissions()
        }
    }

    LazyColumn(modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(TextR.string.permission_center_help),
                        style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(TextR.string.permission_accessibility_restricted_hint),
                        style = MaterialTheme.typography.bodySmall)
                    feedback?.let {
                        Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = ::manageAppPermissions,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(TextR.string.permission_open_app_settings))
                    }
                }
            }
        }
        items(groups, key = { it.id }) { group ->
            val granted = statuses[group.id] == true
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(group.title), Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold)
                        Text(stringResource(if (granted) TextR.string.common_granted
                            else TextR.string.common_not_granted),
                            color = if (granted) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium)
                    }
                    Text(stringResource(group.detail),
                        style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { authorize(group) }, Modifier.fillMaxWidth()) {
                        Text(stringResource(if (granted) TextR.string.permission_open_app_settings
                            else TextR.string.permission_request_now))
                    }
                }
            }
        }
    }
}
