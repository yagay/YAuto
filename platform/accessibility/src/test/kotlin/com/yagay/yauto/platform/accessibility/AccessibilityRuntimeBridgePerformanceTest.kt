package com.yagay.yauto.platform.accessibility

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityRuntimeBridgePerformanceTest {
    @After
    fun reset() {
        AccessibilityRuntimeBridge.configureUiRuntimeEvents(emptySet())
    }

    @Test
    fun `ui events are disabled unless an automation subscribes`() {
        AccessibilityRuntimeBridge.configureUiRuntimeEvents(emptySet())

        assertFalse(AccessibilityRuntimeBridge.shouldDispatchUiEvent("scrolled"))
        assertFalse(AccessibilityRuntimeBridge.shouldDispatchUiEvent("content_changed"))
        assertFalse(AccessibilityRuntimeBridge.shouldCaptureScreenText())
    }

    @Test
    fun `screen content subscription enables content capture only`() {
        AccessibilityRuntimeBridge.configureUiRuntimeEvents(
            setOf("android.event.screen_content_changed")
        )

        assertTrue(AccessibilityRuntimeBridge.shouldDispatchUiEvent("content_changed"))
        assertTrue(AccessibilityRuntimeBridge.shouldCaptureScreenText())
        assertFalse(AccessibilityRuntimeBridge.shouldDispatchUiEvent("scrolled"))
    }

    @Test
    fun `scroll subscription does not enable expensive full screen capture`() {
        AccessibilityRuntimeBridge.configureUiRuntimeEvents(
            setOf("android.event.ui_scrolled")
        )

        assertTrue(AccessibilityRuntimeBridge.shouldDispatchUiEvent("scrolled"))
        assertFalse(AccessibilityRuntimeBridge.shouldCaptureScreenText())
    }
}
