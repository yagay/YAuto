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

    @Test fun `permission validation rejects shell syntax`() {
        assertTrue(isValidPermissionName("android.permission.POST_NOTIFICATIONS"))
        assertTrue(isValidPermissionName("com.example.permission.CUSTOM_2"))
        assertFalse(isValidPermissionName("android.permission.CAMERA;reboot"))
        assertFalse(isValidPermissionName("CAMERA"))
    }

    @Test fun `enable suspend uninstall and permission commands target current user`() {
        assertEquals("pm enable --user current com.example.app", appEnabledCommand("com.example.app", true))
        assertEquals("pm disable-user --user current com.example.app", appEnabledCommand("com.example.app", false))
        assertEquals("pm suspend --user current com.example.app", appSuspendedCommand("com.example.app", true))
        assertEquals("pm unsuspend --user current com.example.app", appSuspendedCommand("com.example.app", false))
        assertEquals("pm uninstall --user current com.example.app", uninstallForUserCommand("com.example.app", false))
        assertEquals("pm uninstall -k --user current com.example.app", uninstallForUserCommand("com.example.app", true))
        assertEquals(
            "pm grant --user current com.example.app android.permission.CAMERA",
            permissionCommand("com.example.app", "android.permission.CAMERA", true),
        )
        assertEquals(
            "pm revoke --user current com.example.app android.permission.CAMERA",
            permissionCommand("com.example.app", "android.permission.CAMERA", false),
        )
    }

    @Test fun `standby buckets accept only known values`() {
        assertEquals("am set-standby-bucket --user current com.example.app active", standbyBucketCommand("com.example.app", "active"))
        assertEquals("am set-standby-bucket --user current com.example.app restricted", standbyBucketCommand("com.example.app", "restricted"))
        assertNull(standbyBucketCommand("com.example.app", "never"))
    }

    @Test fun `reboot modes map only to supported commands`() {
        assertEquals("svc power reboot", rebootCommand("normal"))
        assertEquals("svc power reboot recovery", rebootCommand("recovery"))
        assertEquals("svc power reboot bootloader", rebootCommand("bootloader"))
        assertNull(rebootCommand("fastbootd"))
        assertNull(rebootCommand(""))
    }
}
