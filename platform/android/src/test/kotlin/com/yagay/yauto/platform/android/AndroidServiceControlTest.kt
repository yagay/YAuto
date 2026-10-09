package com.yagay.yauto.platform.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidServiceControlTest {
    @Test fun `service operation determines the correct activity manager verb`() {
        assertEquals("am startservice --user 0 -n 'com.example.app/.SyncService'",
            serviceControlCommand("com.example.app/.SyncService", 0, "start"))
        assertEquals("am start-foreground-service --user 10 -n 'com.example.app/.SyncService'",
            serviceControlCommand("com.example.app/.SyncService", 10, "start_foreground"))
        assertEquals("am stopservice --user 0 -n 'com.example.app/.SyncService'",
            serviceControlCommand("com.example.app/.SyncService", 0, "stop"))
    }

    @Test fun `invalid privileged component or user is rejected before shell`() {
        assertNull(serviceControlCommand("com.example/.Service; reboot", 0, "start"))
        assertNull(serviceControlCommand("com.example/.Service", -1, "start"))
        assertNull(serviceControlCommand("com.example/.Service", 1000, "start"))
        assertNull(serviceControlCommand("com.example/.Service", 0, "reboot"))
        assertNull(serviceControlCommand("com.example", 0, "start"))
        assertNull(serviceControlCommand("com.example/.Service", 0, "start", "android.intent.action;rm"))
        assertNull(serviceControlCommand("com.example/.Service", 0, "stop", "android.intent.action.MAIN"))
        assertNull(serviceControlCommand("com.example/.Service", 0, "start", dataUri = "not-a-uri"))
    }

    @Test fun `service intents support explicit action and data with safe quoting`() {
        assertEquals(
            "am startservice --user 0 -n 'com.example/.Service' -a 'android.intent.action.VIEW' -d 'example://open?a=b'",
            serviceControlCommand("com.example/.Service", 0, "start", "android.intent.action.VIEW", "example://open?a=b"),
        )
    }

    @Test fun `am service text errors do not masquerade as successful operations`() {
        assertTrue(serviceControlFailed("Error: Not found; no service started."))
        assertTrue(serviceControlFailed("java.lang.SecurityException: Permission Denial"))
        assertTrue(serviceControlFailed("Service does not exist"))
        assertFalse(serviceControlFailed("Starting service: Intent { cmp=com.example.app/.SyncService }"))
        assertFalse(serviceControlFailed("Service stopped: Intent { cmp=com.example.app/.SyncService }"))
    }
}
