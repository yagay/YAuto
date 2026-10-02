package com.yagay.yauto.platform.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAdvancedSystemFeaturePackTest {
    @Test fun `rotation maps only known angles`() {
        assertEquals("settings put system accelerometer_rotation 0; settings put system user_rotation 0", rotationCommand("0"))
        assertEquals("settings put system accelerometer_rotation 0; settings put system user_rotation 1", rotationCommand("90"))
        assertEquals("settings put system accelerometer_rotation 0; settings put system user_rotation 2", rotationCommand("180"))
        assertEquals("settings put system accelerometer_rotation 0; settings put system user_rotation 3", rotationCommand("270"))
        assertNull(rotationCommand("45"))
    }

    @Test fun `font and animation scales enforce bounds`() {
        assertEquals("settings put system font_scale 1.25", fontScaleCommand(1.25))
        assertNull(fontScaleCommand(0.1))
        assertEquals(
            "settings put global window_animation_scale 0.5; settings put global transition_animation_scale 0.5; settings put global animator_duration_scale 0.5",
            animationScaleCommand(0.5),
        )
        assertNull(animationScaleCommand(11.0))
    }

    @Test fun `display overrides validate size and density`() {
        assertEquals("wm size reset", displaySizeCommand(true, null, null))
        assertEquals("wm size 1080x2400", displaySizeCommand(false, 1080, 2400))
        assertNull(displaySizeCommand(false, 100, 2400))
        assertEquals("wm density reset", displayDensityCommand(true, null))
        assertEquals("wm density 420", displayDensityCommand(false, 420))
        assertNull(displayDensityCommand(false, 50))
    }

    @Test fun `private dns hostname validation rejects shell input`() {
        assertTrue(isValidDnsHostname("dns.example.com"))
        assertTrue(isValidDnsHostname("one.one.one.one"))
        assertFalse(isValidDnsHostname("dns.example.com;reboot"))
        assertFalse(isValidDnsHostname("bad..host"))
        assertEquals(
            "settings put global private_dns_mode hostname; settings put global private_dns_specifier dns.example.com",
            privateDnsCommand("hostname", "dns.example.com"),
        )
        assertNull(privateDnsCommand("hostname", "dns.example.com;reboot"))
    }

    @Test fun `boolean system commands are deterministic`() {
        assertEquals("cmd netpolicy set restrict-background true", dataSaverCommand(true))
        assertEquals("cmd netpolicy set restrict-background false", dataSaverCommand(false))
        assertEquals("settings put global auto_time 1; settings put global auto_time_zone 0", automaticTimeCommand(true, false))
        assertEquals("cmd deviceidle force-idle", deviceIdleCommand(true))
        assertEquals("cmd deviceidle unforce", deviceIdleCommand(false))
    }
}
