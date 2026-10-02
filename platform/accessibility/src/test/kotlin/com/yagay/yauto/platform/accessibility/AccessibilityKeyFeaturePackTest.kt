package com.yagay.yauto.platform.accessibility

import com.yagay.yauto.core.model.ConfigValue
import org.junit.Assert.*
import org.junit.Test

class AccessibilityKeyFeaturePackTest {
    @Test fun `hardware key matcher filters key action and repeat`() {
        val payload = mapOf(
            "keyCode" to ConfigValue.NumberValue(24.0),
            "action" to ConfigValue.StringValue("down"),
            "repeatCount" to ConfigValue.NumberValue(0.0),
        )
        assertTrue(matchesHardwareKey(mapOf(
            "keyCode" to ConfigValue.NumberValue(24.0),
            "action" to ConfigValue.StringValue("down"),
            "initialOnly" to ConfigValue.BooleanValue(true),
        ), payload))
        assertFalse(matchesHardwareKey(mapOf(
            "keyCode" to ConfigValue.NumberValue(25.0),
        ), payload))
        assertFalse(matchesHardwareKey(mapOf(
            "initialOnly" to ConfigValue.BooleanValue(true),
        ), payload + ("repeatCount" to ConfigValue.NumberValue(2.0))))
    }
}
