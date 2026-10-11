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


    @Test fun automaticRequestsTryRootFirstAndSkipUnnecessaryChecks() = runBlocking {
        val calls = mutableListOf<String>()
        fun fake(idValue: String, ok: Boolean) = object : CapabilityBackend {
            override val id = idValue
            override val priority = if (id == "root") 80 else 70
            override fun supports(request: CapabilityRequest, environment: RuntimeEnvironment) = true
            override suspend fun isAvailable(environment: RuntimeEnvironment): Boolean {
                calls += "check:$id"
                return true
            }
            override suspend fun execute(request: CapabilityRequest, environment: RuntimeEnvironment): CapabilityResult {
                calls += "exec:$id"
                return CapabilityResult(ok)
            }
        }
        val broker = CapabilityBroker({ environment }, listOf(fake("shizuku", true), fake("root", true)))
        assertEquals("root", broker.execute(CapabilityRequest(CapabilityIds.PRIVILEGED_SHELL, "test")).backendId)
        assertEquals(listOf("check:root", "exec:root"), calls)
    }

    @Test fun failedRootDoesNotFallBackToShizukuWhileRootGrantIsValid() = runBlocking {
        val calls = mutableListOf<String>()
        fun fake(idValue: String, success: Boolean) = object : CapabilityBackend {
            override val id = idValue
            override val priority = if (id == "root") 80 else 70
            override fun supports(request: CapabilityRequest, environment: RuntimeEnvironment) = true
            override suspend fun isAvailable(environment: RuntimeEnvironment) = true
            override suspend fun execute(request: CapabilityRequest, environment: RuntimeEnvironment): CapabilityResult {
                calls += id
                return CapabilityResult(success,
                    failureKind = if (success) null else CapabilityFailureKind.BACKEND_UNAVAILABLE)
            }
        }
        val root = fake("root", false)
        val shizuku = fake("shizuku", true)
        val request = CapabilityRequest(CapabilityIds.PRIVILEGED_SHELL, "test")
        val broker = CapabilityBroker({ environment }, listOf(root, shizuku))
        val result = broker.execute(request)
        assertEquals("root", result.backendId)
        assertEquals(listOf("root"), result.attempts.map { it.backendId })
        calls.clear()
        val pinned = broker.execute(request.copy(preferredBackendId = "shizuku"))
        assertFalse(pinned.success)
        assertEquals(CapabilityFailureKind.BACKEND_UNAVAILABLE, pinned.failureKind)
        assertTrue(calls.isEmpty())
        calls.clear()
        CapabilityBroker({ environment }, listOf(root, shizuku), { false }).execute(request)
        assertEquals(listOf("root"), calls)
    }

    @Test fun lsposedPrecedesRootAndShizukuForAutomaticSystemActions() = runBlocking {
        val calls = mutableListOf<String>()
        fun backend(name: String, works: Boolean, supported: Boolean = true) = object : CapabilityBackend {
            override val id = name
            override val priority = when (name) {
                "lsposed" -> 90
                "root" -> 80
                "shizuku" -> 70
                else -> 40
            }
            override fun supports(request: CapabilityRequest, environment: RuntimeEnvironment) = supported
            override suspend fun isAvailable(environment: RuntimeEnvironment): Boolean {
                calls += "check:$name"
                return true
            }
            override suspend fun execute(request: CapabilityRequest, environment: RuntimeEnvironment): CapabilityResult {
                calls += "execute:$name"
                return CapabilityResult(works)
            }
        }
        val broker = CapabilityBroker({ environment }, listOf(
            backend("root", true), backend("accessibility", true),
            backend("lsposed", true), backend("shizuku", true),
        ))
        val request = CapabilityRequest(CapabilityIds.SYSTEM_UI, SystemOperations.SLEEP)
        assertEquals("lsposed", broker.execute(request).backendId)
        assertEquals(listOf("check:lsposed", "execute:lsposed"), calls)
        calls.clear()
        assertEquals("root", broker.execute(request.copy(preferredBackendId = "root")).backendId)
        assertEquals(listOf("check:root", "execute:root"), calls)
    }

    @Test fun lsposedFailureFallsBackToRootThenAccessibilityWithoutShizuku() = runBlocking {
        val executed = mutableListOf<String>()
        fun backend(name: String, works: Boolean) = object : CapabilityBackend {
            override val id = name
            override val priority = 100
            override fun supports(request: CapabilityRequest, environment: RuntimeEnvironment) = true
            override suspend fun isAvailable(environment: RuntimeEnvironment) = true
            override suspend fun execute(request: CapabilityRequest, environment: RuntimeEnvironment): CapabilityResult {
                executed += name
                return CapabilityResult(works,
                    failureKind = if (works) null else CapabilityFailureKind.BACKEND_UNAVAILABLE)
            }
        }
        val broker = CapabilityBroker({ environment }, listOf(
            backend("accessibility", true), backend("shizuku", false),
            backend("root", false), backend("lsposed", false),
        ))
        val outcome = broker.execute(CapabilityRequest(CapabilityIds.ACCESSIBILITY, "test"))
        assertEquals("accessibility", outcome.backendId)
        assertEquals(listOf("lsposed", "root", "accessibility"), executed)
        assertEquals(executed, outcome.attempts.map { it.backendId })
    }

    @Test fun sharedPriorityRanksAllFourBackendsInRequestedOrder() {
        val order = listOf("accessibility", "root", "shizuku", "lsposed").sortedBy {
            CapabilityBackendPriority.rank(it, privilegedFirst = true)
        }
        assertEquals(listOf("lsposed", "root", "accessibility", "shizuku"), order)
        val withoutPrivilegedPreference = order.sortedBy {
            CapabilityBackendPriority.rank(it, privilegedFirst = false)
        }
        assertEquals(listOf("accessibility", "lsposed", "root", "shizuku"), withoutPrivilegedPreference)
    }


    @Test fun grantedRootSuppressesShizukuEvenWhenOnlyShizukuSupportsOperation() = runBlocking {
        val calls = mutableListOf<String>()
        fun mock(idValue: String, supported: Boolean = true, available: Boolean = true) =
            object : CapabilityBackend {
                override val id = idValue
                override val priority = 50
                override fun supports(request: CapabilityRequest, environment: RuntimeEnvironment) = supported
                override suspend fun isAvailable(environment: RuntimeEnvironment): Boolean {
                    calls += "probe:$id"
                    return available
                }
                override suspend fun execute(request: CapabilityRequest, environment: RuntimeEnvironment): CapabilityResult {
                    calls += "execute:$id"
                    return CapabilityResult(true)
                }
            }
        val broker = CapabilityBroker({ environment }, listOf(
            mock("root", supported = false), mock("shizuku")
        ))
        val result = broker.execute(CapabilityRequest(CapabilityIds.SYSTEM_UI, SystemOperations.SLEEP))
        assertFalse(result.success)
        assertEquals(CapabilityFailureKind.BACKEND_UNAVAILABLE, result.failureKind)
        assertEquals(listOf("probe:root"), calls)
    }

    @Test fun rootlessShizukuWorksOnlyAfterAvailableNativeBackendsAreExhausted() = runBlocking {
        val seen = mutableListOf<String>()
        fun mock(idValue: String, available: Boolean) = object : CapabilityBackend {
            override val id = idValue
            override val priority = 50
            override fun supports(request: CapabilityRequest, environment: RuntimeEnvironment) = true
            override suspend fun isAvailable(environment: RuntimeEnvironment): Boolean {
                seen += "probe:$id"
                return available
            }
            override suspend fun execute(request: CapabilityRequest, environment: RuntimeEnvironment): CapabilityResult {
                seen += "execute:$id"
                return CapabilityResult(true)
            }
        }
        val result = CapabilityBroker({ environment }, listOf(
            mock("root", false), mock("accessibility", false), mock("shizuku", true)
        )).execute(CapabilityRequest(CapabilityIds.SYSTEM_UI, SystemOperations.SLEEP))
        assertTrue(result.success)
        assertEquals("shizuku", result.backendId)
        assertEquals(listOf("probe:root", "probe:accessibility", "probe:root", "probe:shizuku", "execute:shizuku"), seen)
    }

    @Test fun explicitShizukuPinCannotOverrideRootGrant() = runBlocking {
        val executed = mutableListOf<String>()
        fun mock(idValue: String) = object : CapabilityBackend {
            override val id = idValue
            override val priority = 50
            override fun supports(request: CapabilityRequest, environment: RuntimeEnvironment) = true
            override suspend fun isAvailable(environment: RuntimeEnvironment) = true
            override suspend fun execute(request: CapabilityRequest, environment: RuntimeEnvironment): CapabilityResult {
                executed += id
                return CapabilityResult(true)
            }
        }
        val result = CapabilityBroker({ environment }, listOf(mock("root"), mock("shizuku")))
            .execute(CapabilityRequest(CapabilityIds.PRIVILEGED_SHELL, "test", preferredBackendId = "shizuku"))
        assertFalse(result.success)
        assertEquals(CapabilityFailureKind.BACKEND_UNAVAILABLE, result.failureKind)
        assertTrue(executed.isEmpty())
    }

    @Test fun noRootAndAccessibilityAreNotProbedAfterSuccessfulLsposed() = runBlocking {
        val seen = mutableListOf<String>()
        fun fake(name: String): CapabilityBackend = object : CapabilityBackend {
            override val id = name
            override val priority = 10
            override fun supports(request: CapabilityRequest, environment: RuntimeEnvironment) = true
            override suspend fun isAvailable(environment: RuntimeEnvironment): Boolean {
                seen += "available:$id"
                return true
            }
            override suspend fun execute(request: CapabilityRequest, environment: RuntimeEnvironment): CapabilityResult {
                seen += "execute:$id"
                return CapabilityResult(true)
            }
        }
        val request = CapabilityRequest(CapabilityIds.SYSTEM_UI, SystemOperations.EXPAND_NOTIFICATIONS)
        val broker = CapabilityBroker({ environment }, listOf(
            fake("accessibility"), fake("shizuku"), fake("root"), fake("lsposed"),
        ))
        assertEquals("lsposed", broker.execute(request).backendId)
        assertEquals(listOf("available:lsposed", "execute:lsposed"), seen)
    }
    @Test fun failedSideEffectDoesNotRunSecondBackend() = runBlocking {
        val seen = mutableListOf<String>()
        fun backend(name: String, succeeds: Boolean) = object : CapabilityBackend {
            override val id = name
            override val priority = 10
            override fun supports(request: CapabilityRequest, environment: RuntimeEnvironment) = true
            override suspend fun isAvailable(environment: RuntimeEnvironment) = true
            override suspend fun execute(request: CapabilityRequest, environment: RuntimeEnvironment): CapabilityResult {
                seen += name
                return CapabilityResult(succeeds)
            }
        }
        val broker = CapabilityBroker({ environment }, listOf(backend("root", false), backend("shizuku", true)))
        val result = broker.execute(CapabilityRequest(CapabilityIds.SYSTEM_UI, SystemOperations.SLEEP))
        assertFalse(result.success)
        assertEquals("root", result.backendId)
        assertEquals(CapabilityFailureKind.EXECUTION_FAILED, result.failureKind)
        assertEquals(listOf("root"), seen)
    }

    @Test fun readOnlyFailuresDoNotInvokeShizukuWhileRootIsGranted() = runBlocking {
        val seen = mutableListOf<String>()
        fun backend(name: String, succeeds: Boolean) = object : CapabilityBackend {
            override val id = name
            override val priority = 10
            override fun supports(request: CapabilityRequest, environment: RuntimeEnvironment) = true
            override suspend fun isAvailable(environment: RuntimeEnvironment) = true
            override suspend fun execute(request: CapabilityRequest, environment: RuntimeEnvironment): CapabilityResult {
                seen += name
                return CapabilityResult(succeeds)
            }
        }
        val broker = CapabilityBroker({ environment }, listOf(backend("root", false), backend("shizuku", true)))
        val result = broker.execute(CapabilityRequest(CapabilityIds.PRIVILEGED_SHELL, "android.system.property.get"))
        assertFalse(result.success)
        assertEquals("root", result.backendId)
        assertEquals(listOf("root"), seen)
    }

}
