package com.yagay.yauto.platform.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPrivilegedUtilityFeaturePackTest {
    @Test fun `settings commands validate namespace and key and quote values`() {
        assertEquals("settings get secure enabled_accessibility_services", settingsGetCommand("secure", "enabled_accessibility_services"))
        assertEquals("settings put global test_key 'hello world'", settingsPutCommand("global", "test_key", "hello world"))
        assertEquals("settings put global test_key 'it'\\''s safe'", settingsPutCommand("global", "test_key", "it's safe"))
        assertEquals("settings delete system screen_brightness", settingsDeleteCommand("system", "screen_brightness"))
        assertNull(settingsGetCommand("unknown", "key"))
        assertNull(settingsGetCommand("secure", "key;reboot"))
    }

    @Test fun `appops validation blocks command injection`() {
        assertEquals(
            "cmd appops get --user current com.example.app CAMERA",
            appOpGetCommand("com.example.app", "CAMERA"),
        )
        assertEquals(
            "cmd appops set --user current com.example.app CAMERA foreground",
            appOpSetCommand("com.example.app", "CAMERA", "foreground"),
        )
        assertNull(appOpSetCommand("com.example.app;reboot", "CAMERA", "allow"))
        assertNull(appOpSetCommand("com.example.app", "CAMERA;reboot", "allow"))
        assertNull(appOpSetCommand("com.example.app", "CAMERA", "super_allow"))
    }

    @Test fun `component commands accept short relative and fully qualified classes`() {
        assertEquals(
            "pm disable-user --user current com.example.app/com.example.app.MainActivity",
            componentEnabledCommand("com.example.app", ".MainActivity", false),
        )
        assertEquals(
            "pm enable --user current com.example.app/com.example.app.MainActivity",
            componentEnabledCommand("com.example.app", "MainActivity", true),
        )
        assertEquals(
            "pm enable --user current com.example.app/com.other.Component",
            componentEnabledCommand("com.example.app", "com.other.Component", true),
        )
        assertNull(componentEnabledCommand("com.example.app", "Main;reboot", true))
    }

    @Test fun `structured key validators are restrictive`() {
        assertTrue(isValidSettingKey("private_dns_mode"))
        assertFalse(isValidSettingKey("private dns mode"))
        assertTrue(isValidPropertyName("ro.build.version.release"))
        assertFalse(isValidPropertyName("ro.build;reboot"))
        assertTrue(isValidAppOp("POST_NOTIFICATION"))
        assertFalse(isValidAppOp("POST-NOTIFICATION"))
    }
}
