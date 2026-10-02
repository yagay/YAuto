package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.ConfigValue
import org.junit.Assert.*
import org.junit.Test

class ExtendedEventMatchersTest {
    @Test fun `network profile event filters connected transport validation and metered`() {
        val payload = mapOf(
            "connected" to ConfigValue.BooleanValue(true),
            "wifi" to ConfigValue.BooleanValue(true),
            "cellular" to ConfigValue.BooleanValue(false),
            "ethernet" to ConfigValue.BooleanValue(false),
            "vpn" to ConfigValue.BooleanValue(false),
            "validated" to ConfigValue.BooleanValue(true),
            "metered" to ConfigValue.BooleanValue(false),
        )
        assertTrue(matchesNetworkProfileEvent(mapOf(
            "connected" to ConfigValue.StringValue("connected"),
            "transport" to ConfigValue.StringValue("wifi"),
            "validated" to ConfigValue.StringValue("yes"),
            "metered" to ConfigValue.StringValue("no"),
        ), payload))
        assertFalse(matchesNetworkProfileEvent(mapOf("transport" to ConfigValue.StringValue("cellular")), payload))
        assertTrue(matchesNetworkProfileEvent(
            mapOf("connected" to ConfigValue.StringValue("disconnected")),
            mapOf("connected" to ConfigValue.BooleanValue(false)),
        ))
    }

    @Test fun `bluetooth audio event matches name address and connection edge`() {
        val payload = mapOf(
            "connected" to ConfigValue.BooleanValue(true),
            "name" to ConfigValue.StringValue("Buds Pro"),
            "address" to ConfigValue.StringValue("AA:BB:CC:DD:EE:FF"),
        )
        assertTrue(matchesBluetoothAudioEvent(mapOf(
            "state" to ConfigValue.StringValue("connected"),
            "nameContains" to ConfigValue.StringValue("buds"),
            "address" to ConfigValue.StringValue("aa:bb:cc:dd:ee:ff"),
        ), payload))
        assertFalse(matchesBluetoothAudioEvent(
            mapOf("state" to ConfigValue.StringValue("disconnected")), payload,
        ))
    }
}
