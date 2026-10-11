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
    @Test fun unavailableOemSenderUidRequiresAuthenticatedSystemSession() {
        // OxygenOS/Android 16 has been observed reporting senderUid=-1 for
        // the real system_server KeyEvent broadcast. Token-gated fallback
        // must restore that path without trusting an arbitrary unknown sender.
        assertFalse(XposedEventSenderVerifier.accept(
            -1, "lsposed.system_server", "", emptyList()
        ))
        assertTrue(XposedEventSenderVerifier.accept(
            -1, "lsposed.system_server", "", emptyList(),
            authenticatedUnknownSystemSender = true,
        ))
        assertFalse(XposedEventSenderVerifier.accept(
            -1, "lsposed.package", "com.example.automation",
            listOf("com.example.automation"), authenticatedUnknownSystemSender = true,
        ))
        assertFalse(XposedEventSenderVerifier.accept(
            11000, "lsposed.system_server", "", emptyList(),
            authenticatedUnknownSystemSender = true,
        ))
        assertFalse(XposedEventSenderVerifier.accept(
            -2, "lsposed.system_server", "", emptyList(),
            authenticatedUnknownSystemSender = true,
        ))
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
