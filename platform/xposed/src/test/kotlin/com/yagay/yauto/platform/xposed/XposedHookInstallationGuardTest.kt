package com.yagay.yauto.platform.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class XposedHookInstallationGuardTest {
    @Test fun failedInstallCanRetryWithoutStaleDedupKey() {
        val keys = ConcurrentHashMap.newKeySet<String>()
        val failure = XposedHookInstallationGuard.install(keys, "method") { error("failed") }
        assertTrue(failure.isFailure)
        assertFalse(keys.contains("method"))
        assertEquals(true, XposedHookInstallationGuard.install(keys, "method") {}.getOrThrow())
        assertEquals(false, XposedHookInstallationGuard.install(keys, "method") {}.getOrThrow())
    }

    @Test fun failedOneMethodDoesNotForgetPreviouslyInstalledMethod() {
        val keys = ConcurrentHashMap.newKeySet<String>()
        XposedHookInstallationGuard.install(keys, "healthy") {}.getOrThrow()
        XposedHookInstallationGuard.install(keys, "broken") { error("failed") }
        assertTrue(keys.contains("healthy"))
        assertFalse(keys.contains("broken"))
    }
}
