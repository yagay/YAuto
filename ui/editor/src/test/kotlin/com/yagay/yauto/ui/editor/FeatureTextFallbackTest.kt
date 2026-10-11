package com.yagay.yauto.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FeatureTextFallbackTest {
    @Test fun `state and condition share approved counterpart only for matching IDs`() {
        assertEquals(
            "android.condition.battery_level",
            stateConditionCounterpartId("android.state.battery_level")
        )
        assertEquals(
            "android.state.battery_level",
            stateConditionCounterpartId("android.condition.battery_level")
        )
        assertEquals(null, stateConditionCounterpartId("android.event.battery_changed"))
        assertEquals(null, stateConditionCounterpartId("android.app.launch"))
    }

    @Test fun `feature titles stay distinct when untranslated`() {
        val first = descriptiveFallbackName("Send message", "android.message.send")
        val second = descriptiveFallbackName("Clear notification", "android.notification.cancel")
        assertNotEquals(first, second)
        assertEquals("Send message", first)
        assertEquals("Clear notification", second)
    }

    @Test fun `blank source title falls back to readable id not category`() {
        assertEquals("android event wifi changed",
            descriptiveFallbackName("", "android.event.wifi_changed"))
    }
}
