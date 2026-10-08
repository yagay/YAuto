package com.yagay.yauto.platform.android

import android.media.RingtoneManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidReferenceUtilityExpansionFeaturePackTest {
    @Test
    fun `status bar commands map to AOSP shell operations`() {
        assertEquals("cmd statusbar expand-notifications", statusBarCommand("notifications"))
        assertEquals("cmd statusbar expand-settings", statusBarCommand("quick_settings"))
        assertEquals("cmd statusbar collapse", statusBarCommand("collapse"))
        assertNull(statusBarCommand("unknown"))
    }

    @Test
    fun `connectivity URLs accept websites and reject unsupported schemes`() {
        assertEquals("https://example.com", normalizeConnectivityUrl("example.com"))
        assertEquals("https://example.com/path", normalizeConnectivityUrl("https://example.com/path"))
        assertNull(normalizeConnectivityUrl(""))
        assertNull(normalizeConnectivityUrl("ftp://example.com/file"))
        assertNull(normalizeConnectivityUrl("not a valid host"))
    }

    @Test
    fun `default sound type names map to Android ringtone types`() {
        assertEquals(RingtoneManager.TYPE_RINGTONE, ringtoneType("ringtone"))
        assertEquals(RingtoneManager.TYPE_NOTIFICATION, ringtoneType("notification"))
        assertEquals(RingtoneManager.TYPE_ALARM, ringtoneType("alarm"))
        assertNull(ringtoneType("media"))
    }
}
