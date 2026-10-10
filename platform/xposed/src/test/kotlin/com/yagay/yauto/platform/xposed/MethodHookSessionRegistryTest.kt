package com.yagay.yauto.platform.xposed

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

    @Test fun differentSessionsAreIndependent() {
        val sessions = MethodHookSessionRegistry()
        assertTrue(sessions.activate("first", "one"))
        assertTrue(sessions.activate("second", "two"))
        assertTrue(sessions.disable("first"))
        assertTrue(sessions.isActive("second", "two"))
    }
}
