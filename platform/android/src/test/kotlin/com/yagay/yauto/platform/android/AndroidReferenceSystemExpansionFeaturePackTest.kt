package com.yagay.yauto.platform.android

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidReferenceSystemExpansionFeaturePackTest {
    @Test
    fun `reference system toggle commands are deterministic`() {
        assertEquals("cmd uimode car yes", carModeCommand(true))
        assertEquals("cmd uimode car no", carModeCommand(false))
        assertEquals(
            "settings put secure accessibility_display_inversion_enabled 1",
            colorInversionCommand(true),
        )
        assertEquals(
            "settings put secure accessibility_display_inversion_enabled 0",
            colorInversionCommand(false),
        )
        assertEquals("settings put secure doze_always_on 1", ambientDisplayCommand("always_on", true))
        assertEquals("settings put secure doze_always_on 0", ambientDisplayCommand("always_on", false))
        assertEquals("settings put secure doze_enabled 1", ambientDisplayCommand("wake_for_notifications", true))
        assertEquals(null, ambientDisplayCommand("unknown", true))
        assertEquals("settings put global heads_up_notifications_enabled 1", headsUpCommand(true))
        assertEquals("settings put global heads_up_notifications_enabled 0", headsUpCommand(false))
        assertEquals("settings put global data_roaming 1", dataRoamingCommand(true))
        assertEquals("settings put global data_roaming 0", dataRoamingCommand(false))
    }
}
