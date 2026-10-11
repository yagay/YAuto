package com.yagay.yauto

import android.Manifest
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yagay.yauto.core.capability.RuntimeEnvironment
import com.yagay.yauto.core.registry.AccessRequirement
import com.yagay.yauto.platform.accessibility.AccessibilityBackend
import com.yagay.yauto.platform.root.RootShell
import com.yagay.yauto.platform.shizuku.ShizukuBackend
import com.yagay.yauto.platform.xposed.XposedBackend
import com.yagay.yauto.ui.editor.PermissionAvailability
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One device-facing authorization snapshot for the editor, picker, settings
 * and the feature-health scanner. UNKNOWN means not probed or probe failed;
 * it must never be rendered as NOT_GRANTED or working functionality.
 *
 * A granted permission only authorizes a candidate route. It does not prove
 * the OEM implements a specific hook or that an action will succeed.
 */
class RuntimeAccessStatusRepository(
    context: Context,
    private val rootShell: RootShell,
    private val shizuku: ShizukuBackend,
    private val xposed: XposedBackend,
    private val accessibility: AccessibilityBackend,
) {
    private val context = context.applicationContext
    private val refreshLock = Mutex()
    private val _status = MutableStateFlow<Map<AccessRequirement, PermissionAvailability>>(emptyMap())
    val status: StateFlow<Map<AccessRequirement, PermissionAvailability>> = _status.asStateFlow()
    private val lastProbeElapsed = AtomicLong(0L)

    fun availability(requirement: AccessRequirement): PermissionAvailability =
        _status.value[requirement] ?: PermissionAvailability.UNKNOWN

    /** Small shared cache; resumes and manual refreshes invalidate it. */
    suspend fun refresh(
        needed: Set<AccessRequirement> = AccessRequirement.entries.toSet(),
        force: Boolean = false,
    ): Map<AccessRequirement, PermissionAvailability> = refreshLock.withLock {
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = _status.value
        if (!force && lastProbeElapsed.get() > 0L &&
            now - lastProbeElapsed.get() < 3_000L && previous.keys.containsAll(needed)
        ) return@withLock previous

        val result = withContext(Dispatchers.IO) {
            val environment = RuntimeEnvironment(Build.VERSION.SDK_INT)
            fun check(block: () -> Boolean): PermissionAvailability =
                try {
                    if (block()) PermissionAvailability.GRANTED else PermissionAvailability.NOT_GRANTED
                } catch (_: Exception) { PermissionAvailability.UNKNOWN }
            suspend fun checkSuspend(block: suspend () -> Boolean): PermissionAvailability =
                try {
                    if (block()) PermissionAvailability.GRANTED else PermissionAvailability.NOT_GRANTED
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) { PermissionAvailability.UNKNOWN }

            // A Root grant changes execution priority, not Shizuku permission.
            // Probe only the requested permission here.
            val rootNeeded = AccessRequirement.ROOT in needed
            val root = if (rootNeeded) checkSuspend { rootShell.isAvailable() }
                else previous[AccessRequirement.ROOT] ?: PermissionAvailability.UNKNOWN
            val lsposed = if (AccessRequirement.LSPOSED in needed) {
                checkSuspend { xposed.isAvailable(environment) }
            } else previous[AccessRequirement.LSPOSED] ?: PermissionAvailability.UNKNOWN
            // Per-feature runtime permission checks are performed separately
            // by the health scanner for SMS, PHONE and CALENDAR.
            val updated = linkedMapOf<AccessRequirement, PermissionAvailability>()
            for (requirement in needed) {
                updated[requirement] = when (requirement) {
                    AccessRequirement.ROOT -> root
                    AccessRequirement.LSPOSED -> lsposed
                    AccessRequirement.SHIZUKU -> check { shizuku.hasPermission() }
                    AccessRequirement.ZYGISK -> PermissionAvailability.UNKNOWN
                    AccessRequirement.ACCESSIBILITY ->
                        checkSuspend { accessibility.isAvailable(environment) }
                    AccessRequirement.USAGE_STATS ->
                        check { com.yagay.yauto.platform.android.isUsageStatsAccessGranted(context) }
                    AccessRequirement.NOTIFICATION_LISTENER -> check {
                        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
                    }
                    AccessRequirement.POST_NOTIFICATIONS -> check {
                        Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    AccessRequirement.OVERLAY -> check { Settings.canDrawOverlays(context) }
                    AccessRequirement.WRITE_SETTINGS -> check { Settings.System.canWrite(context) }
                    AccessRequirement.CAMERA -> check { granted(Manifest.permission.CAMERA) }
                    AccessRequirement.LOCATION -> check {
                        granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
                            granted(Manifest.permission.ACCESS_COARSE_LOCATION)
                    }
                    AccessRequirement.BLUETOOTH_CONNECT -> check {
                        Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                    AccessRequirement.DND_POLICY -> check {
                        context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
                    }
                    AccessRequirement.DEVICE_ADMIN -> check {
                        context.getSystemService(DevicePolicyManager::class.java)
                            .isAdminActive(ComponentName(context, YAutoDeviceAdminReceiver::class.java))
                    }
                    AccessRequirement.CALENDAR -> check {
                        granted(Manifest.permission.READ_CALENDAR) &&
                            granted(Manifest.permission.WRITE_CALENDAR)
                    }
                    AccessRequirement.CONTACTS -> check { granted(Manifest.permission.READ_CONTACTS) }
                    AccessRequirement.CALL_LOG -> check { granted(Manifest.permission.READ_CALL_LOG) }
                    AccessRequirement.SMS -> check {
                        granted(Manifest.permission.READ_SMS) ||
                            granted(Manifest.permission.RECEIVE_SMS) ||
                            granted(Manifest.permission.SEND_SMS)
                    }
                    AccessRequirement.PHONE -> check { granted(Manifest.permission.READ_PHONE_STATE) }
                    AccessRequirement.RECORD_AUDIO -> check { granted(Manifest.permission.RECORD_AUDIO) }
                    AccessRequirement.ACTIVITY_RECOGNITION -> check {
                        Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACTIVITY_RECOGNITION)
                    }
                }
            }
            updated
        }
        val merged = previous + result
        _status.value = merged
        lastProbeElapsed.set(now)
        merged
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
