package com.yagay.yauto.platform.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class XposedHardwareKeyIdentityTest {
    @Test fun identityIsStableAcrossInputHookEntrypoints() {
        assertEquals("1:780:735:1:555", XposedHardwareKeyIdentity.from(1, 780, 735, 1, 555L))
        assertEquals(
            XposedHardwareKeyIdentity.from(1, 780, 735, 1, 555L),
            XposedHardwareKeyIdentity.from(1, 780, 735, 1, 555L),
        )
    }

    @Test fun identityDistinguishesDevicesAndEventTimes() {
        val original = XposedHardwareKeyIdentity.from(1, 780, 735, 1, 555L)
        assertNotEquals(original, XposedHardwareKeyIdentity.from(2, 780, 735, 1, 555L))
        assertNotEquals(original, XposedHardwareKeyIdentity.from(1, 780, 735, 1, 556L))
        assertNotEquals(original, XposedHardwareKeyIdentity.from(1, 780, 735, 0, 555L))
    }
}
