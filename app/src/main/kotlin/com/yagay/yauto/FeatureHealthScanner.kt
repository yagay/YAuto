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
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.resolvedAccessRequirements
import com.yagay.yauto.core.registry.resolvedImplementationOptions
import com.yagay.yauto.platform.accessibility.AccessibilityBackend
import com.yagay.yauto.platform.android.isUsageStatsAccessGranted
import com.yagay.yauto.platform.android.runtimePermissionsForFeature
import com.yagay.yauto.platform.root.RootShell
import com.yagay.yauto.platform.shizuku.ShizukuBackend
import com.yagay.yauto.platform.xposed.XposedBackend
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class FeatureHealthStatus { READY, BLOCKED, BROKEN, UNSUPPORTED }

data class FeatureHealthItem(
    val featureId: String,
    val title: String,
    val kind: FeatureKind,
    val status: FeatureHealthStatus,
    val backendId: String? = null,
    val missingRequirements: Set<AccessRequirement> = emptySet(),
    val detail: String? = null,
)

data class FeatureHealthSnapshot(
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    val items: List<FeatureHealthItem>,
    val accessState: Map<AccessRequirement, Boolean> = emptyMap(),
) {
    val readyCount: Int get() = items.count { it.status == FeatureHealthStatus.READY }
    val blockedCount: Int get() = items.count { it.status == FeatureHealthStatus.BLOCKED }
    val brokenCount: Int get() = items.count { it.status == FeatureHealthStatus.BROKEN }
    val unsupportedCount: Int get() = items.count { it.status == FeatureHealthStatus.UNSUPPORTED }
}

/**
 * Safe feature health scanner.
 *
 * It validates SDK support, Android access requirements and privileged backend connectivity for
 * every registered feature. It never executes the feature itself, so background scans cannot send
 * messages, delete files, toggle radios, reboot, perform gestures or otherwise change device state.
 */
class FeatureHealthScanner(
    context: Context,
    private val descriptors: () -> List<FeatureDescriptor>,
    private val rootShell: RootShell,
    private val shizuku: ShizukuBackend,
    private val xposed: XposedBackend,
    private val accessibility: AccessibilityBackend,
    private val implementationAvailable: (FeatureDescriptor) -> Boolean,
) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val scanLock = Mutex()
    private val lastRequestAt = AtomicLong(0)
    private var scanJob: Job? = null

    private val _snapshot = MutableStateFlow<FeatureHealthSnapshot?>(null)
    val snapshot: StateFlow<FeatureHealthSnapshot?> = _snapshot.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _autoScanEnabled = MutableStateFlow(preferences.getBoolean(KEY_AUTO_SCAN, false))
    val autoScanEnabled: StateFlow<Boolean> = _autoScanEnabled.asStateFlow()

    fun setAutoScanEnabled(enabled: Boolean) {
        if (_autoScanEnabled.value == enabled) return
        _autoScanEnabled.value = enabled
        preferences.edit().putBoolean(KEY_AUTO_SCAN, enabled).apply()
        if (enabled) requestScan(force = true)
    }

    fun requestScan(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRequestAt.get() < MIN_RESCAN_INTERVAL_MS) return
        lastRequestAt.set(now)
        if (scanJob?.isActive == true) return
        scanJob = scope.launch {
            _scanning.value = true
            try {
                scan()
            } finally {
                _scanning.value = false
            }
        }
    }

    suspend fun scan(): FeatureHealthSnapshot = scanLock.withLock {
        val started = System.currentTimeMillis()
        val featureList = descriptors()
        val needed = featureList
            .flatMap { descriptor ->
                buildList {
                    addAll(descriptor.resolvedAccessRequirements())
                    descriptor.resolvedImplementationOptions().forEach { addAll(it.requirements) }
                }
            }
            .toSet()
        val access = probeAccess(needed)
        val items = featureList.map { descriptor -> evaluate(descriptor, access) }
            .sortedWith(
                compareBy<FeatureHealthItem> {
                    when (it.status) {
                        FeatureHealthStatus.BROKEN -> 0
                        FeatureHealthStatus.BLOCKED -> 1
                        FeatureHealthStatus.UNSUPPORTED -> 2
                        FeatureHealthStatus.READY -> 3
                    }
                }.thenBy { it.title.lowercase() }.thenBy { it.featureId }
            )
        return FeatureHealthSnapshot(started, System.currentTimeMillis(), items, access).also { _snapshot.value = it }
    }

    private suspend fun probeAccess(needed: Set<AccessRequirement>): Map<AccessRequirement, Boolean> {
        val environment = RuntimeEnvironment(Build.VERSION.SDK_INT)
        val root = if (AccessRequirement.ROOT in needed || AccessRequirement.ZYGISK in needed) {
            runCatching { rootShell.isAvailable() }.getOrDefault(false)
        } else false
        val lsposed = if (AccessRequirement.LSPOSED in needed) {
            runCatching { xposed.isAvailable(environment) }.getOrDefault(false)
        } else false
        val zygisk = if (AccessRequirement.ZYGISK in needed && root) {
            runCatching {
                rootShell.run(
                    "test -d /data/adb/modules/zygisksu || test -d /data/adb/modules/zygisk_lsposed || ps -A 2>/dev/null | grep -qi zygisk",
                    2_000,
                ).exitCode == 0
            }.getOrDefault(false)
        } else false

        // Probe only capabilities required by a registered feature. Checking every permission
        // on every scan adds unnecessary system calls and reports unrelated access as denied.
        return needed.associateWith { requirement ->
            when (requirement) {
                AccessRequirement.ROOT -> root
                AccessRequirement.SHIZUKU -> runCatching { shizuku.hasPermission() }.getOrDefault(false)
                AccessRequirement.LSPOSED -> lsposed
                AccessRequirement.ZYGISK -> zygisk
                AccessRequirement.ACCESSIBILITY ->
                    runCatching { accessibility.isAvailable(environment) }.getOrDefault(false)
                AccessRequirement.USAGE_STATS -> isUsageStatsAccessGranted(context)
                AccessRequirement.NOTIFICATION_LISTENER ->
                    context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
                AccessRequirement.POST_NOTIFICATIONS ->
                    Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)
                AccessRequirement.OVERLAY -> Settings.canDrawOverlays(context)
                AccessRequirement.WRITE_SETTINGS -> Settings.System.canWrite(context)
                AccessRequirement.CAMERA -> granted(Manifest.permission.CAMERA)
                AccessRequirement.LOCATION ->
                    granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
                AccessRequirement.BLUETOOTH_CONNECT ->
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(Manifest.permission.BLUETOOTH_CONNECT)
                AccessRequirement.DND_POLICY ->
                    context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
                AccessRequirement.DEVICE_ADMIN ->
                    context.getSystemService(DevicePolicyManager::class.java)
                        .isAdminActive(ComponentName(context, YAutoDeviceAdminReceiver::class.java))
                AccessRequirement.CALENDAR ->
                    granted(Manifest.permission.READ_CALENDAR) && granted(Manifest.permission.WRITE_CALENDAR)
                AccessRequirement.CONTACTS -> granted(Manifest.permission.READ_CONTACTS)
                AccessRequirement.CALL_LOG -> granted(Manifest.permission.READ_CALL_LOG)
                AccessRequirement.SMS ->
                    granted(Manifest.permission.READ_SMS) ||
                        granted(Manifest.permission.RECEIVE_SMS) ||
                        granted(Manifest.permission.SEND_SMS)
                AccessRequirement.PHONE -> granted(Manifest.permission.READ_PHONE_STATE)
                AccessRequirement.RECORD_AUDIO -> granted(Manifest.permission.RECORD_AUDIO)
                AccessRequirement.ACTIVITY_RECOGNITION ->
                    Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }
    }

    private fun evaluate(
        descriptor: FeatureDescriptor,
        access: Map<AccessRequirement, Boolean>,
    ): FeatureHealthItem {
        if (!runCatching { implementationAvailable(descriptor) }.getOrDefault(false)) {
            return FeatureHealthItem(
                descriptor.id.value,
                descriptor.title,
                descriptor.kind,
                FeatureHealthStatus.BROKEN,
                detail = "Feature is registered but no executable implementation is installed for its kind",
            )
        }

        if (Build.VERSION.SDK_INT < descriptor.minSdk) {
            return FeatureHealthItem(
                descriptor.id.value,
                descriptor.title,
                descriptor.kind,
                FeatureHealthStatus.UNSUPPORTED,
                detail = "Requires Android API " + descriptor.minSdk,
            )
        }

        val available: (AccessRequirement) -> Boolean = { requirement ->
            if (requirement == AccessRequirement.SMS || requirement == AccessRequirement.CALENDAR) {
                val group = if (requirement == AccessRequirement.SMS) "sms" else "calendar"
                runtimePermissionsForFeature(group, descriptor.id.value).all(::granted)
            } else access[requirement] == true
        }
        val explicitMissing = descriptor.accessRequirements.filterNot(available).toSet()
        if (explicitMissing.isNotEmpty()) {
            return FeatureHealthItem(
                descriptor.id.value,
                descriptor.title,
                descriptor.kind,
                FeatureHealthStatus.BLOCKED,
                missingRequirements = explicitMissing,
                detail = "Required access is not available",
            )
        }

        val options = descriptor.resolvedImplementationOptions()
        if (options.isNotEmpty()) {
            val readyOption = options.firstOrNull { option ->
                option.requirements.all(available)
            }
            if (readyOption != null) {
                return FeatureHealthItem(
                    descriptor.id.value,
                    descriptor.title,
                    descriptor.kind,
                    FeatureHealthStatus.READY,
                    backendId = readyOption.backendId,
                    detail = "Implementation, backend and access checks passed",
                )
            }
            val missing = options.flatMap { it.requirements }.filterNot(available).toSet()
            return FeatureHealthItem(
                descriptor.id.value,
                descriptor.title,
                descriptor.kind,
                FeatureHealthStatus.BLOCKED,
                missingRequirements = missing,
                detail = "No declared implementation backend is currently ready",
            )
        }

        val resolvedMissing = descriptor.resolvedAccessRequirements()
            .filterNot(available)
            .toSet()
        return FeatureHealthItem(
            descriptor.id.value,
            descriptor.title,
            descriptor.kind,
            if (resolvedMissing.isEmpty()) FeatureHealthStatus.READY else FeatureHealthStatus.BLOCKED,
            missingRequirements = resolvedMissing,
            detail = if (resolvedMissing.isEmpty()) {
                "Implementation and environment checks passed; side-effect behavior is not executed during background scan"
            } else {
                "Required access is not available"
            },
        )
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val PREFERENCES_NAME = "feature_health"
        private const val KEY_AUTO_SCAN = "auto_scan"
        private const val MIN_RESCAN_INTERVAL_MS = 2_000L

        fun autoScanEnabled(context: Context): Boolean =
            context.applicationContext
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_SCAN, false)
    }
}
