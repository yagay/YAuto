package com.yagay.yauto.core.runtime

import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class ExecutionJobRegistryTest {
    @Test fun tracksAndRemovesExecutionsIndependently() {
        val registry = ExecutionJobRegistry()
        val first = Job()
        val second = Job()
        registry.track("alpha", first)
        registry.track("alpha", second)
        assertTrue(registry.isRunning("alpha"))
        registry.untrack("alpha", first)
        assertTrue(registry.isRunning("alpha"))
        registry.untrack("alpha", second)
        assertFalse(registry.isRunning("alpha"))
    }

    @Test fun cancellationOnlyTouchesMatchingAutomation() {
        val registry = ExecutionJobRegistry()
        val first = Job()
        val second = Job()
        registry.track("alpha", first)
        registry.track("beta", second)
        assertTrue(registry.cancel("alpha"))
        assertFalse(first.isActive)
        assertTrue(second.isActive)
        assertFalse(registry.cancel("alpha"))
        registry.cancelAll()
        assertFalse(second.isActive)
    }
}
