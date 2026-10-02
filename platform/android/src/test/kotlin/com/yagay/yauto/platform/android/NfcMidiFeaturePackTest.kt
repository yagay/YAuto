package com.yagay.yauto.platform.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcMidiFeaturePackTest {
    @Test fun `NFC UID normalization ignores separators and case`() {
        assertTrue("04:a1-b2 c3".normalizeUid() == "04A1B2C3")
        assertTrue("04A1B2C3".normalizeUid().startsWith("04:a1".normalizeUid()))
    }

    @Test fun `MIDI hex parser accepts common separators`() {
        assertArrayEquals(byteArrayOf(0x90.toByte(), 0x3C, 0x7F), parseMidiHex("90 3c:7F"))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x3C, 0x00), parseMidiHex("80-3C-00"))
        assertNull(parseMidiHex("90 3"))
        assertNull(parseMidiHex("90 GG"))
    }

    @Test fun `MIDI identity filters are independent and case insensitive`() {
        assertTrue(matchesMidiSnapshot("key", "yam", "piano", "Keyboard", "Yamaha", "Digital Piano"))
        assertFalse(matchesMidiSnapshot("drum", "", "", "Keyboard", "Yamaha", "Digital Piano"))
        assertTrue(matchesMidiSnapshot("", "", "", "Anything", "Any", "Any"))
    }

    @Test fun `Wi-Fi snapshot matching validates signal and frequency range`() {
        assertTrue(matchesWifiScanSnapshot("Home", "", -70.0, 5000.0, 6000.0, "Home WiFi", "AA:BB", -55.0, 5180.0))
        assertFalse(matchesWifiScanSnapshot("Home", "", -50.0, null, null, "Home WiFi", "AA:BB", -55.0, 5180.0))
        assertFalse(matchesWifiScanSnapshot("", "", null, 6000.0, 5000.0, "Any", "AA:BB", -55.0, 5180.0))
    }

    @Test fun `Bluetooth event matching supports name and address`() {
        assertTrue(eventDeviceMatches("buds", "AA:BB", "My Buds", "aa:bb"))
        assertFalse(eventDeviceMatches("watch", "", "My Buds", "aa:bb"))
    }
}
