package com.yagay.yauto.platform.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAppManagementFeaturePackTest {
    @Test fun `package validation rejects shell metacharacters and incomplete ids`() {
        assertTrue(isValidPackageName("com.example.app"))
        assertTrue(isValidPackageName("com.example_app.client2"))
        assertFalse(isValidPackageName("com.example.app;reboot"))
        assertFalse(isValidPackageName("com.example.app && reboot"))
        assertFalse(isValidPackageName("singleword"))
        assertFalse(isValidPackageName(""))
    }

    @Test fun `enable and disable commands target current user`() {
        assertEquals("pm enable --user current com.example.app", appEnabledCommand("com.example.app", true))
        assertEquals("pm disable-user --user current com.example.app", appEnabledCommand("com.example.app", false))
    }

    @Test fun `reboot modes map only to supported commands`() {
        assertEquals("svc power reboot", rebootCommand("normal"))
        assertEquals("svc power reboot recovery", rebootCommand("recovery"))
        assertEquals("svc power reboot bootloader", rebootCommand("bootloader"))
        assertNull(rebootCommand("fastbootd"))
        assertNull(rebootCommand(""))
    }
}
