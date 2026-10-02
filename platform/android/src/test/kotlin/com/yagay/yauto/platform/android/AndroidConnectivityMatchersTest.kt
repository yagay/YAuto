package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.ConfigValue
import org.junit.Assert.*
import org.junit.Test

class AndroidConnectivityMatchersTest {
    @Test fun `wifi matcher handles ssid bssid and inclusive rssi range`() {
        val current = WifiSnapshot(true, "Home WiFi", "AA:BB:CC:DD:EE:FF", -55)
        assertTrue(matchesWifi(mapOf(
            "connected" to ConfigValue.StringValue("connected"),
            "ssid" to ConfigValue.StringValue("home"),
            "bssid" to ConfigValue.StringValue("aa:bb:cc:dd:ee:ff"),
            "minRssi" to ConfigValue.NumberValue(-60.0),
            "maxRssi" to ConfigValue.NumberValue(-50.0),
        ), current))
        assertFalse(matchesWifi(mapOf("minRssi" to ConfigValue.NumberValue(-50.0)), current))
        assertFalse(matchesWifi(mapOf(
            "minRssi" to ConfigValue.NumberValue(-40.0),
            "maxRssi" to ConfigValue.NumberValue(-80.0),
        ), current))
    }

    @Test fun `wifi matcher fails signal filtering when disconnected or signal unavailable`() {
        assertTrue(matchesWifi(
            mapOf("connected" to ConfigValue.StringValue("disconnected")),
            WifiSnapshot(false, "", "", Int.MIN_VALUE),
        ))
        assertFalse(matchesWifi(
            mapOf("minRssi" to ConfigValue.NumberValue(-100.0)),
            WifiSnapshot(false, "", "", Int.MIN_VALUE),
        ))
    }

    @Test fun `network profile combines transport validation and metered state`() {
        val network = NetworkSnapshot(
            connected = true,
            wifi = true,
            vpn = true,
            validated = true,
            metered = false,
        )
        assertTrue(matchesNetworkProfile(mapOf(
            "transport" to ConfigValue.StringValue("wifi"),
            "validated" to ConfigValue.StringValue("yes"),
            "metered" to ConfigValue.StringValue("no"),
        ), network))
        assertTrue(matchesNetworkProfile(mapOf("transport" to ConfigValue.StringValue("vpn")), network))
        assertFalse(matchesNetworkProfile(mapOf("transport" to ConfigValue.StringValue("cellular")), network))
        assertTrue(matchesNetworkProfile(
            mapOf("connected" to ConfigValue.BooleanValue(false)),
            NetworkSnapshot(false),
        ))
    }

    @Test fun `bluetooth audio matcher supports name address and disconnected expectation`() {
        val devices = listOf(
            BluetoothAudioDeviceSnapshot("Living Room Speaker", "AA:BB:CC:DD:EE:01"),
            BluetoothAudioDeviceSnapshot("Buds Pro", "AA:BB:CC:DD:EE:02"),
        )
        assertTrue(matchesBluetoothAudioDevice(
            mapOf("nameContains" to ConfigValue.StringValue("buds")), devices,
        ))
        assertTrue(matchesBluetoothAudioDevice(
            mapOf("address" to ConfigValue.StringValue("aa:bb:cc:dd:ee:01")), devices,
        ))
        assertFalse(matchesBluetoothAudioDevice(
            mapOf("nameContains" to ConfigValue.StringValue("car")), devices,
        ))
        assertTrue(matchesBluetoothAudioDevice(
            mapOf(
                "connected" to ConfigValue.BooleanValue(false),
                "nameContains" to ConfigValue.StringValue("car"),
            ), devices,
        ))
    }
}
