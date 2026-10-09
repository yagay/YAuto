package com.yagay.yauto.platform.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidStatusIconFeaturePackTest {
    @Test fun `icon slot cannot replace built in Android status bar icons`() {
        assertTrue(statusSlotValid("private"))
        assertTrue(statusSlotValid("yauto_indicator_1"))
        assertFalse(statusSlotValid("wifi.icon"))
        assertFalse(statusSlotValid("1wifi"))
        assertFalse(statusSlotValid("a".repeat(25)))
        assertFalse(statusSlotValid("status;reboot"))
        assertFalse(statusSlotValid(""))
    }
}
