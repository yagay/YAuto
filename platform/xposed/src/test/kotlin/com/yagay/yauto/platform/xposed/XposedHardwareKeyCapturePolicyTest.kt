package com.yagay.yauto.platform.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XposedHardwareKeyCapturePolicyTest {
    @Test fun acceptsModernOemAndroidKeyCodes() {
        assertTrue(XposedHardwareKeyCapturePolicy.accepts(780, 0))
        assertTrue(XposedHardwareKeyCapturePolicy.accepts(780, 1))
    }

    @Test fun ignoresUnknownCodesWithoutCompletingTheWaiter() {
        assertFalse(XposedHardwareKeyCapturePolicy.accepts(0, 0))
        assertFalse(XposedHardwareKeyCapturePolicy.accepts(-1, 1))
        assertFalse(XposedHardwareKeyCapturePolicy.accepts(735, -1))
        assertFalse(XposedHardwareKeyCapturePolicy.accepts(780, 2))
    }
}
