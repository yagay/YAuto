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

@Serializable
data class CapabilityResult(
    val success: Boolean,
    val backendId: String? = null,
    val value: ConfigValue = ConfigValue.NullValue,
    val message: String? = null,
    val attempts: List<CapabilityAttempt> = emptyList(),
)

fun interface CapabilityClient {
    suspend fun execute(request: CapabilityRequest): CapabilityResult
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

class CapabilityBroker(
    private val environmentProvider: () -> RuntimeEnvironment,
    backends: List<CapabilityBackend> = emptyList(),
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
                backend.supports(request, environment)
        }.let { applicable ->
            // Automatic requests prefer a non-root supported route. Explicit saved
            // backend selections are left untouched for backwards compatibility.
            if (request.preferredBackendId != null) applicable else applicable.sortedWith(
                compareBy<CapabilityBackend> { it.id == "root" || it.id == "lsposed" }
                    .thenByDescending { it.priority },
            )
        }
        val candidates = supported.filter { it.isAvailable(environment) }
        if (candidates.isEmpty()) {
            val message = request.preferredBackendId?.let {
                userText("capability.selected_backend_unavailable", it, request.capability.value)
            } ?: userText("capability.no_backend_available", request.capability.value)
            return CapabilityResult(false, message = message)
        }

        val attempts = mutableListOf<CapabilityAttempt>()
        for (backend in candidates) {
            val result = try { backend.execute(request, environment) } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                CapabilityResult(false, message = userText("capability.backend_error", error.message ?: error::class.simpleName.orEmpty()))
            }
            attempts += CapabilityAttempt(backend.id, result.success, result.message)
            if (result.success || !request.allowFallback || request.preferredBackendId != null) {
                return result.copy(backendId = backend.id, attempts = attempts.toList())
            }
        }
        return CapabilityResult(
            success = false,
            message = userText("capability.all_backends_failed", request.operationId),
            attempts = attempts,
        )
    }
}
