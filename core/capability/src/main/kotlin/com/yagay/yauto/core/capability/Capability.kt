package com.yagay.yauto.core.capability

import com.yagay.yauto.core.model.ConfigMap
import com.yagay.yauto.core.model.ConfigValue
import kotlinx.serialization.Serializable
import kotlinx.coroutines.CancellationException

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
        val candidates = backends.filter { it.supports(request, environment) && it.isAvailable(environment) }
        if (candidates.isEmpty()) {
            return CapabilityResult(false, message = "No backend available for ${request.capability.value}")
        }

        val attempts = mutableListOf<CapabilityAttempt>()
        for (backend in candidates) {
            val result = try { backend.execute(request, environment) } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                CapabilityResult(false, message = error.message ?: error::class.simpleName)
            }
            attempts += CapabilityAttempt(backend.id, result.success, result.message)
            if (result.success || !request.allowFallback) {
                return result.copy(backendId = backend.id, attempts = attempts.toList())
            }
        }
        return CapabilityResult(
            success = false,
            message = "All backends failed for ${request.operationId}",
            attempts = attempts,
        )
    }
}
