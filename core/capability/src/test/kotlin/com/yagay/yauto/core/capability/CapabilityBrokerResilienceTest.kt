package com.yagay.yauto.core.capability

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CapabilityBrokerResilienceTest {
    private val environment = RuntimeEnvironment(sdkInt = 35)

    @Test fun throwingAvailabilityCheckDoesNotBlockHealthyBackend() = runBlocking {
        val failing = object : CapabilityBackend {
            override val id = "throwing"
            override val priority = 100
            override suspend fun isAvailable(environment: RuntimeEnvironment): Boolean =
                throw IllegalStateException("broken service")
            override fun supports(request: CapabilityRequest, environment: RuntimeEnvironment) = true
            override suspend fun execute(request: CapabilityRequest, environment: RuntimeEnvironment) =
                CapabilityResult(true)
        }
        val healthy = object : CapabilityBackend {
            override val id = "healthy"
            override val priority = 50
            override suspend fun isAvailable(environment: RuntimeEnvironment) = true
            override fun supports(request: CapabilityRequest, environment: RuntimeEnvironment) = true
            override suspend fun execute(request: CapabilityRequest, environment: RuntimeEnvironment) =
                CapabilityResult(true)
        }
        val broker = CapabilityBroker({ environment }, listOf(failing, healthy))
        val result = broker.execute(CapabilityRequest(CapabilityIds.SYSTEM_UI, SystemOperations.SLEEP))
        assertTrue(result.success)
        assertEquals("healthy", result.backendId)
    }
    @Test fun throwingSupportCheckDoesNotBlockHealthyBackend() = runBlocking {
        val broken = object : CapabilityBackend {
            override val id = "broken"
            override val priority = 100
            override suspend fun isAvailable(environment: RuntimeEnvironment) = true
            override fun supports(request: CapabilityRequest, environment: RuntimeEnvironment): Boolean =
                throw IllegalStateException("unsupported vendor implementation")
            override suspend fun execute(request: CapabilityRequest, environment: RuntimeEnvironment) =
                CapabilityResult(false)
        }
        val healthy = object : CapabilityBackend {
            override val id = "healthy"
            override val priority = 50
            override suspend fun isAvailable(environment: RuntimeEnvironment) = true
            override fun supports(request: CapabilityRequest, environment: RuntimeEnvironment) = true
            override suspend fun execute(request: CapabilityRequest, environment: RuntimeEnvironment) =
                CapabilityResult(true)
        }
        val result = CapabilityBroker({ environment }, listOf(broken, healthy))
            .execute(CapabilityRequest(CapabilityIds.SYSTEM_UI, SystemOperations.SLEEP))
        assertTrue(result.success)
        assertEquals("healthy", result.backendId)
    }

    @Test fun distinguishesUnsupportedFromUnavailableBackend() = runBlocking {
        val none = CapabilityBroker({ environment })
            .execute(CapabilityRequest(CapabilityIds.SYSTEM_UI, "missing"))
        assertEquals(CapabilityFailureKind.UNSUPPORTED, none.failureKind)
        val unavailable = object : CapabilityBackend {
            override val id = "unsupported-rom"
            override val priority = 1
            override fun supports(request: CapabilityRequest, environment: RuntimeEnvironment) = true
            override suspend fun isAvailable(environment: RuntimeEnvironment) = false
            override suspend fun execute(request: CapabilityRequest, environment: RuntimeEnvironment) =
                CapabilityResult(false)
        }
        val failure = CapabilityBroker({ environment }, listOf(unavailable))
            .execute(CapabilityRequest(CapabilityIds.SYSTEM_UI, SystemOperations.SLEEP))
        assertEquals(CapabilityFailureKind.BACKEND_UNAVAILABLE, failure.failureKind)
    }

}
