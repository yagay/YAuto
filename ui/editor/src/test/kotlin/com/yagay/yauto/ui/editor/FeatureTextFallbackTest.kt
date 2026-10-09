package com.yagay.yauto.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FeatureTextFallbackTest {
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
