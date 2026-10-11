package com.yagay.yauto.platform.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MethodHookSessionRegistryTest {
    @Test fun installedMethodCanBeDisabledWithoutInvokingReplacement() {
        val sessions = MethodHookSessionRegistry()
        assertTrue(sessions.activate("session-1", "secret-A"))
        assertTrue(sessions.isActive("session-1", "secret-A"))
        assertTrue(sessions.disable("session-1"))
        assertFalse(sessions.isActive("session-1", "secret-A"))
        assertFalse(sessions.disable("session-1"))
    }

    @Test fun differentTokensCannotReplaceAnActiveSession() {
        val sessions = MethodHookSessionRegistry()
        assertTrue(sessions.activate("session-1", "secret-A"))
        assertTrue(sessions.canActivate("session-1", "secret-A"))
        assertFalse(sessions.canActivate("session-1", "secret-B"))
        assertFalse(sessions.activate("session-1", "secret-B"))
        assertTrue(sessions.isActive("session-1", "secret-A"))
        assertFalse(sessions.isActive("session-1", "secret-B"))
    }

    @Test fun localReceiverCredentialsCannotBeReplacedByFailedReinstall() {
        val id = "reinstall-test"
        val original = "a".repeat(72)
        val replacement = "b".repeat(72)
        XposedHookRuntimeBridge.unregisterSession(id)
        try {
            assertEquals(true, XposedHookRuntimeBridge.registerSession(id, original))
            assertEquals(false, XposedHookRuntimeBridge.registerSession(id, original))
            assertNull(XposedHookRuntimeBridge.registerSession(id, replacement))
            XposedHookRuntimeBridge.unregisterSessionIfMatching(id, replacement)
            assertEquals(false, XposedHookRuntimeBridge.registerSession(id, original))
            XposedHookRuntimeBridge.unregisterSessionIfMatching(id, original)
            assertEquals(true, XposedHookRuntimeBridge.registerSession(id, replacement))
        } finally {
            XposedHookRuntimeBridge.unregisterSession(id)
        }
    }

    @Test fun differentSessionsAreIndependent() {
        val sessions = MethodHookSessionRegistry()
        assertTrue(sessions.activate("first", "one"))
        assertTrue(sessions.activate("second", "two"))
        assertTrue(sessions.disable("first"))
        assertTrue(sessions.isActive("second", "two"))
    }
}
