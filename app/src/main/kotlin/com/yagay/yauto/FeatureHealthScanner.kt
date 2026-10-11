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
import com.yagay.yauto.core.capability.CapabilityBackendPriority
import com.yagay.yauto.core.registry.AccessRequirement
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.resolvedAccessRequirements
import com.yagay.yauto.core.registry.resolvedImplementationOptions
import com.yagay.yauto.core.registry.FeatureImplementationOption
import com.yagay.yauto.platform.accessibility.AccessibilityBackend
import com.yagay.yauto.platform.android.isUsageStatsAccessGranted
import com.yagay.yauto.platform.android.runtimePermissionsForFeature
import com.yagay.yauto.platform.root.RootShell
import com.yagay.yauto.platform.shizuku.ShizukuBackend
import com.yagay.yauto.platform.xposed.XposedBackend
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class FeatureHealthStatus { READY, BLOCKED, UNKNOWN, BROKEN, UNSUPPORTED }

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
    val accessAvailability: Map<AccessRequirement, com.yagay.yauto.ui.editor.PermissionAvailability> = emptyMap(),
) {
    val readyCount: Int get() = items.count { it.status == FeatureHealthStatus.READY }
    val unknownCount: Int get() = items.count { it.status == FeatureHealthStatus.UNKNOWN }
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
    private val implementationAvailable: (FeatureDescriptor) -> Boolean,
    private val accessStatus: RuntimeAccessStatusRepository,
) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val scanLock = Mutex()
    private val lastRequestAt = AtomicLong(0)
    private val scanRequests = Channel<Unit>(Channel.CONFLATED)

    private val _snapshot = MutableStateFlow<FeatureHealthSnapshot?>(null)
    val snapshot: StateFlow<FeatureHealthSnapshot?> = _snapshot.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _autoScanEnabled = MutableStateFlow(preferences.getBoolean(KEY_AUTO_SCAN, false))
    val autoScanEnabled: StateFlow<Boolean> = _autoScanEnabled.asStateFlow()

    init {
        scope.launch {
            for (request in scanRequests) {
                _scanning.value = true
                try {
                    scan()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // A single failed backend probe or provider error must
                    // not permanently terminate the refresh actor.
                    android.util.Log.e("YAutoFeatureHealth", "Feature scan failed", error)
                    _snapshot.value = null // stale permission advice is worse than no advice
                } finally {
                    _scanning.value = false
                }
            }
        }
    }

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
        // A request arriving mid-scan is coalesced and processed afterwards.
        scanRequests.trySend(Unit)
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
        val access = accessStatus.refresh(needed, force = true)
        val items = featureList.map { descriptor -> evaluate(descriptor, access) }
            .sortedWith(
                compareBy<FeatureHealthItem> {
                    when (it.status) {
                        FeatureHealthStatus.BROKEN -> 0
                        FeatureHealthStatus.BLOCKED -> 1
                        FeatureHealthStatus.UNKNOWN -> 2
                        FeatureHealthStatus.UNSUPPORTED -> 3
                        FeatureHealthStatus.READY -> 4
                    }
                }.thenBy { it.title.lowercase() }.thenBy { it.featureId }
            )
        return FeatureHealthSnapshot(
            started, System.currentTimeMillis(), items,
            access.mapValues { it.value == com.yagay.yauto.ui.editor.PermissionAvailability.GRANTED },
            access,
        ).also { _snapshot.value = it }
    }

    private fun evaluate(
        descriptor: FeatureDescriptor,
        access: Map<AccessRequirement, com.yagay.yauto.ui.editor.PermissionAvailability>,
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
            if (requirement == AccessRequirement.SMS ||
                requirement == AccessRequirement.CALENDAR ||
                requirement == AccessRequirement.PHONE
            ) {
                val group = when (requirement) {
                    AccessRequirement.SMS -> "sms"
                    AccessRequirement.CALENDAR -> "calendar"
                    else -> "phone"
                }
                runtimePermissionsForFeature(group, descriptor.id.value).let { permissions ->
                    permissions.isNotEmpty() && permissions.all(::granted)
                }
            } else access[requirement] == com.yagay.yauto.ui.editor.PermissionAvailability.GRANTED
        }
        val explicitMissing = descriptor.accessRequirements.filterNot(available).toSet()
        if (explicitMissing.isNotEmpty()) {
            return FeatureHealthItem(
                descriptor.id.value,
                descriptor.title,
                descriptor.kind,
                if (explicitMissing.any {
                        access[it] == null || access[it] == com.yagay.yauto.ui.editor.PermissionAvailability.UNKNOWN
                    }) FeatureHealthStatus.UNKNOWN else FeatureHealthStatus.BLOCKED,
                missingRequirements = explicitMissing,
                detail = "Required access is not available",
            )
        }

        val options = descriptor.resolvedImplementationOptions().filterNot {
            it.backendId == "shizuku" &&
                access[AccessRequirement.ROOT] == com.yagay.yauto.ui.editor.PermissionAvailability.GRANTED
        }
        if (options.isNotEmpty()) {
            val rootFirst = RuntimeSettingsPreferences.preferRootWhenAvailable(context)
            val readyOption = options
                .filter { option -> option.requirements.all(available) }
                .minWithOrNull(compareBy<FeatureImplementationOption> {
                    CapabilityBackendPriority.rank(it.backendId, rootFirst)
                })
            if (readyOption != null) {
                return FeatureHealthItem(
                    descriptor.id.value,
                    descriptor.title,
                    descriptor.kind,
                    FeatureHealthStatus.READY,
                    backendId = readyOption.backendId,
                    detail = "A declared backend is authorized; this feature's specific operation is checked during execution",
                )
            }
            // A transient Binder/Root/LSPosed probe failure is UNKNOWN, not denied.
            // If a backend could work but its state cannot be established, never
            // show a red "missing permission" or claim a successful execution.
            val uncertain = options.filter { option ->
                option.requirements.all { requirement ->
                    access[requirement] != com.yagay.yauto.ui.editor.PermissionAvailability.NOT_GRANTED
                }
            }.minWithOrNull(compareBy<FeatureImplementationOption> {
                CapabilityBackendPriority.rank(it.backendId, rootFirst)
            })
            if (uncertain != null) {
                return FeatureHealthItem(
                    descriptor.id.value, descriptor.title, descriptor.kind,
                    FeatureHealthStatus.UNKNOWN, backendId = uncertain.backendId,
                    detail = "Backend authorization could not be verified during this scan",
                )
            }
            // Alternative backends are OR options, never cumulative grants:
            // report only the closest single implementation to authorize.
            val nextOption = options.minWithOrNull(
                compareBy<FeatureImplementationOption> { option ->
                    option.requirements.count { !available(it) }
                }.thenBy { option ->
                    CapabilityBackendPriority.rank(option.backendId, rootFirst)
                },
            )
            val missing = nextOption?.requirements?.filterNot(available).orEmpty().toSet()
            return FeatureHealthItem(
                descriptor.id.value,
                descriptor.title,
                descriptor.kind,
                FeatureHealthStatus.BLOCKED,
                backendId = nextOption?.backendId,
                missingRequirements = missing,
                detail = "One declared implementation needs authorization; operation support depends on its parameters",
            )
        }

        val resolvedMissing = descriptor.resolvedAccessRequirements()
            .filterNot(available)
            .toSet()
        return FeatureHealthItem(
            descriptor.id.value,
            descriptor.title,
            descriptor.kind,
            when {
                resolvedMissing.isEmpty() -> FeatureHealthStatus.READY
                resolvedMissing.any {
                    access[it] == null || access[it] == com.yagay.yauto.ui.editor.PermissionAvailability.UNKNOWN
                } -> FeatureHealthStatus.UNKNOWN
                else -> FeatureHealthStatus.BLOCKED
            },
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
