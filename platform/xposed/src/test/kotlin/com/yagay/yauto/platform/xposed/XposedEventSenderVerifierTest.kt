package com.yagay.yauto.platform.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XposedEventSenderVerifierTest {
    @Test fun allowsSystemServer() {
        assertTrue(XposedEventSenderVerifier.accept(1000, "lsposed.system_server", "", emptyList()))
    }
    @Test fun rejectsSpoofedSystemServerEvents() {
        assertFalse(XposedEventSenderVerifier.accept(11000, "lsposed.system_server",
            "android", listOf("com.evil.app")))
    }
    @Test fun permitsOwnScopedPackage() {
        assertTrue(XposedEventSenderVerifier.accept(10105, "lsposed.package",
            "com.example.automation", listOf("com.example.automation")))
    }
    @Test fun blocksOtherAppIdentity() {
        assertFalse(XposedEventSenderVerifier.accept(10105, "lsposed.package",
            "com.android.systemui", listOf("com.example.automation")))
        assertFalse(XposedEventSenderVerifier.accept(-1, "lsposed.package",
            "com.example.automation", listOf("com.example.automation")))
    }
}
