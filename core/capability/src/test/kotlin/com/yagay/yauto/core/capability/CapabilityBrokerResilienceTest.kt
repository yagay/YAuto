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
}
