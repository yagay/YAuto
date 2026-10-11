package com.yagay.yauto.core.capability

import com.yagay.yauto.core.model.ConfigMap
import com.yagay.yauto.core.model.ConfigValue
import kotlinx.serialization.Serializable
import kotlinx.coroutines.CancellationException
import com.yagay.yauto.core.model.userText

@Serializable @JvmInline
value class CapabilityId(val value: String)

object CapabilityIds {
    val PRIVILEGED_SHELL = CapabilityId("privileged.shell")
    val APP_LAUNCH = CapabilityId("android.app.launch")
    val TOAST = CapabilityId("android.toast")
    val ACCESSIBILITY = CapabilityId("android.accessibility")
    val NOTIFICATION_LISTENER = CapabilityId("android.notification_listener")
    val SYSTEM_UI = CapabilityId("android.systemui")
    val LSPOSED = CapabilityId("android.lsposed")
    val LSPOSED_HOOK = CapabilityId("android.lsposed.hook")
}

/** Shared stable operation IDs for system controls; platform backends own their implementation. */
object SystemOperations {
    const val SLEEP = "system.screen.sleep"
    const val EXPAND_NOTIFICATIONS = "system.notifications.expand"
    const val COLLAPSE_PANELS = "system.notifications.collapse"
    const val EXPAND_QUICK_SETTINGS = "system.quick_settings.expand"
    const val WAKE = "system.screen.wake"
    const val REBOOT = "system.device.reboot"
    const val REBOOT_RECOVERY = "system.device.reboot.recovery"
    const val REBOOT_BOOTLOADER = "system.device.reboot.bootloader"
    const val SHUTDOWN = "system.device.shutdown"
    const val SENSORS_OFF_ENABLE = "system.sensors_off.enable"
    const val SENSORS_OFF_DISABLE = "system.sensors_off.disable"
    const val SENSORS_OFF_QUERY = "system.sensors_off.query"
    fun shellCommand(operation: String): String? = when (operation) {
        SLEEP -> "input keyevent 223"
        EXPAND_NOTIFICATIONS -> "cmd statusbar expand-notifications"
        COLLAPSE_PANELS -> "cmd statusbar collapse"
        EXPAND_QUICK_SETTINGS -> "cmd statusbar expand-settings"
        WAKE -> "input keyevent 224"
        REBOOT -> "svc power reboot"
        REBOOT_RECOVERY -> "svc power reboot recovery"
        REBOOT_BOOTLOADER -> "svc power reboot bootloader"
        SHUTDOWN -> "svc power shutdown"
        else -> null
    }
}

@Serializable
data class RuntimeEnvironment(
    val sdkInt: Int,
    val manufacturer: String = "unknown",
    val brand: String = "unknown",
    val model: String = "unknown",
    val rom: String = "unknown",
    val rootAvailable: Boolean = false,
    val shizukuAvailable: Boolean = false,
    val lsposedAvailable: Boolean = false,
)

@Serializable
data class CapabilityRequest(
    val capability: CapabilityId,
    val operationId: String,
    val payload: ConfigMap = emptyMap(),
    val allowFallback: Boolean = true,
    /** Null means automatic backend selection. A non-null value makes the request use only that backend. */
    val preferredBackendId: String? = null,
)

@Serializable
data class CapabilityAttempt(
    val backendId: String,
    val success: Boolean,
    val message: String? = null,
)

/** Machine-readable backend failure categories; localized copy stays in the message field. */
@Serializable
enum class CapabilityFailureKind {
    UNSUPPORTED, BACKEND_UNAVAILABLE, EXECUTION_FAILED,
}

@Serializable
data class CapabilityResult(
    val success: Boolean,
    val backendId: String? = null,
    val value: ConfigValue = ConfigValue.NullValue,
    val message: String? = null,
    val attempts: List<CapabilityAttempt> = emptyList(),
    val failureKind: CapabilityFailureKind? = null,
)

fun interface CapabilityClient {
    suspend fun execute(request: CapabilityRequest): CapabilityResult
}

/**
 * A failed reply from an action does not prove it did not execute. In particular,
 * a binder timeout or shell exit error can arrive AFTER a side effect. Only
 * failures known to be pre-execution, or verified read-only operations, permit
 * a different backend to retry the operation automatically.
 */
object CapabilityFallbackPolicy {
    private val READ_ONLY_OPERATIONS = setOf(
        "android.settings.value.get",
        "android.system.property.get",
        "android.app.appop.get",
        "android.process.list",
        SystemOperations.SENSORS_OFF_QUERY,
    )

    fun canRetryAfterFailure(request: CapabilityRequest, result: CapabilityResult): Boolean =
        result.failureKind == CapabilityFailureKind.UNSUPPORTED ||
            result.failureKind == CapabilityFailureKind.BACKEND_UNAVAILABLE ||
            request.operationId in READ_ONLY_OPERATIONS

    fun canRetryUnknownOperation(result: CapabilityResult): Boolean =
        result.failureKind == CapabilityFailureKind.UNSUPPORTED ||
            result.failureKind == CapabilityFailureKind.BACKEND_UNAVAILABLE
}

/** Applies a user-selected backend to requests without forcing every feature executor to know about UI preferences. */
class PreferredBackendCapabilityClient(
    private val delegate: CapabilityClient,
    private val preferredBackendId: String?,
) : CapabilityClient {
    override suspend fun execute(request: CapabilityRequest): CapabilityResult {
        val preferred = request.preferredBackendId ?: preferredBackendId?.takeIf { it.isNotBlank() && it != "auto" }
        return delegate.execute(request.copy(preferredBackendId = preferred))
    }
}

fun CapabilityClient.preferBackend(backendId: String?): CapabilityClient =
    if (backendId.isNullOrBlank() || backendId == "auto") this else PreferredBackendCapabilityClient(this, backendId)

interface CapabilityBackend {
    val id: String
    val priority: Int
    suspend fun isAvailable(environment: RuntimeEnvironment): Boolean
    fun supports(request: CapabilityRequest, environment: RuntimeEnvironment): Boolean
    suspend fun execute(request: CapabilityRequest, environment: RuntimeEnvironment): CapabilityResult
}

/**
 * Single backend ordering contract used by the runtime, permission guidance,
 * and feature diagnostics. Unsupported backends are filtered before ranking.
 */
object CapabilityBackendPriority {
    fun rank(backendId: String?, privilegedFirst: Boolean): Int = when (backendId) {
        "lsposed" -> if (privilegedFirst) 0 else 3
        "root" -> if (privilegedFirst) 1 else 4
        "shizuku" -> if (privilegedFirst) 2 else 0
        "accessibility" -> if (privilegedFirst) 3 else 1
        "android" -> if (privilegedFirst) 4 else 2
        else -> 5
    }
}

class CapabilityBroker(
    private val environmentProvider: () -> RuntimeEnvironment,
    backends: List<CapabilityBackend> = emptyList(),
    /** Evaluated for each automatic request; saved backend selections remain authoritative. */
    private val preferRootWhenAvailable: () -> Boolean = { true },
) : CapabilityClient {
    @Volatile private var backendSnapshot: List<CapabilityBackend> = backends.sortedByDescending { it.priority }

    @Synchronized
    fun register(backend: CapabilityBackend) {
        backendSnapshot = (backendSnapshot.filterNot { it.id == backend.id } + backend)
            .sortedByDescending { it.priority }
    }

    @Synchronized
    fun unregister(backendId: String) {
        backendSnapshot = backendSnapshot.filterNot { it.id == backendId }
    }

    override suspend fun execute(request: CapabilityRequest): CapabilityResult {
        val environment = environmentProvider()
        val supported = backendSnapshot.filter { backend ->
            (request.preferredBackendId == null || backend.id == request.preferredBackendId) &&
                try { backend.supports(request, environment) } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { false }
        }.let { applicable ->
            if (request.preferredBackendId != null) applicable
            else {
                // Kept under the original preference key for existing installations.
                // An enabled preference orders supported implementations:
                // LSPosed > Root > Shizuku > Accessibility.
                val privilegedFirst = preferRootWhenAvailable()
                applicable.sortedWith(
                    compareBy<CapabilityBackend> {
                        CapabilityBackendPriority.rank(it.id, privilegedFirst)
                    }.thenByDescending { it.priority },
                )
            }
        }
        if (supported.isEmpty()) {
            return CapabilityResult(
                false,
                message = request.preferredBackendId?.let {
                    userText("capability.selected_backend_unavailable", it, request.capability.value)
                } ?: userText("capability.no_backend_available", request.capability.value),
                failureKind = CapabilityFailureKind.UNSUPPORTED,
            )
        }

        val attempts = mutableListOf<CapabilityAttempt>()
        var anyAvailable = false
        // Probe only the next supported backend when an earlier one is unavailable or fails.
        for (backend in supported) {
            val available = try {
                backend.isAvailable(environment)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { false }
            if (!available) continue
            anyAvailable = true
            val result = try { backend.execute(request, environment) } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                CapabilityResult(false, message = userText("capability.backend_error", error.message ?: error::class.simpleName.orEmpty()),
                    failureKind = CapabilityFailureKind.EXECUTION_FAILED)
            }
            attempts += CapabilityAttempt(backend.id, result.success, result.message)
            if (result.success || !request.allowFallback || request.preferredBackendId != null ||
                !CapabilityFallbackPolicy.canRetryAfterFailure(request, result)
            ) {
                return result.copy(
                    backendId = backend.id,
                    attempts = attempts.toList(),
                    failureKind = if (!result.success) result.failureKind ?: CapabilityFailureKind.EXECUTION_FAILED else null,
                )
            }
        }
        return CapabilityResult(
            success = false,
            message = if (anyAvailable) userText("capability.all_backends_failed", request.operationId)
                else request.preferredBackendId?.let {
                    userText("capability.selected_backend_unavailable", it, request.capability.value)
                } ?: userText("capability.no_backend_available", request.capability.value),
            attempts = attempts,
            failureKind = if (anyAvailable) CapabilityFailureKind.EXECUTION_FAILED
                else CapabilityFailureKind.BACKEND_UNAVAILABLE,
        )
    }
}
