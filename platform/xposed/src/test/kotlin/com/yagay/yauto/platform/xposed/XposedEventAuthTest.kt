package com.yagay.yauto.platform.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XposedEventAuthTest {
    @Test fun legacyBroadcastRequiresCurrentSessionSecret() {
        XposedEventAuth.expect("0123456789abcdef0123456789abcdef")
        assertFalse(XposedEventAuth.acceptLegacy(""))
        assertFalse(XposedEventAuth.acceptLegacy("0123456789abcdef0123456789abcdee"))
        assertTrue(XposedEventAuth.acceptLegacy("0123456789abcdef0123456789abcdef"))
        XposedEventAuth.expect("abcdef0123456789abcdef0123456789")
        assertFalse(XposedEventAuth.acceptLegacy("0123456789abcdef0123456789abcdef"))
    }
}
