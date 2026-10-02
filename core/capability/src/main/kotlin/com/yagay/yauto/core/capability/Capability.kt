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
}

/** Shared stable operation IDs for system controls; platform backends own their implementation. */
object SystemOperations {
    const val SLEEP = "system.screen.sleep"
    const val EXPAND_NOTIFICATIONS = "system.notifications.expand"
    const val COLLAPSE_PANELS = "system.notifications.collapse"
    fun shellCommand(operation: String): String? = when (operation) {
        SLEEP -> "input keyevent 223"
        EXPAND_NOTIFICATIONS -> "cmd statusbar expand-notifications"
        COLLAPSE_PANELS -> "cmd statusbar collapse"
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
    private val backends = backends.toMutableList()

    fun register(backend: CapabilityBackend) {
        backends.removeAll { it.id == backend.id }
        backends += backend
        backends.sortByDescending { it.priority }
    }

    fun unregister(backendId: String) {
        backends.removeAll { it.id == backendId }
    }

    override suspend fun execute(request: CapabilityRequest): CapabilityResult {
        val environment = environmentProvider()
        val supported = backends.filter { backend ->
            (request.preferredBackendId == null || backend.id == request.preferredBackendId) &&
                backend.supports(request, environment)
        }
        val candidates = supported.filter { it.isAvailable(environment) }
        if (candidates.isEmpty()) {
            val message = request.preferredBackendId?.let {
                userText("capability.selected_backend_unavailable", "Selected backend '%s' is unavailable or does not support %s", it, request.capability.value)
            } ?: userText("capability.no_backend_available", "No backend is available for %s", request.capability.value)
            return CapabilityResult(false, message = message)
        }

        val attempts = mutableListOf<CapabilityAttempt>()
        for (backend in candidates) {
            val result = try { backend.execute(request, environment) } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                CapabilityResult(false, message = userText("capability.backend_error", "Backend error: %s", error.message ?: error::class.simpleName.orEmpty()))
            }
            attempts += CapabilityAttempt(backend.id, result.success, result.message)
            if (result.success || !request.allowFallback || request.preferredBackendId != null) {
                return result.copy(backendId = backend.id, attempts = attempts.toList())
            }
        }
        return CapabilityResult(
            success = false,
            message = userText("capability.all_backends_failed", "All backends failed for %s", request.operationId),
            attempts = attempts,
        )
    }
}
