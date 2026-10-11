package com.yagay.yauto.platform.accessibility

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccessibilityWindowContentTransitionTest {
    @After fun cleanup() {
        AccessibilityRuntimeBridge.setListener(null)
        AccessibilityRuntimeBridge.clearIfServiceStops()
    }

    @Test fun contentUpdatesDoNotTurnViewClassesIntoForegroundActivities() {
        AccessibilityRuntimeBridge.clearIfServiceStops()
        AccessibilityRuntimeBridge.update("com.example.one", "com.example.one.MainActivity")
        var transitions = 0
        AccessibilityRuntimeBridge.setListener { _, _ -> transitions++ }

        AccessibilityRuntimeBridge.updateFromContent("com.example.one")
        assertEquals("com.example.one.MainActivity",
            AccessibilityRuntimeBridge.currentWindow()?.className)
        assertEquals(0, transitions)

        AccessibilityRuntimeBridge.updateFromContent("com.example.two")
        assertEquals("com.example.two",
            AccessibilityRuntimeBridge.currentWindow()?.packageName)
        assertNull(AccessibilityRuntimeBridge.currentWindow()?.className)
        assertEquals(1, transitions)
    }

    @Test fun emptyContentPackagesDoNotEraseForegroundWindow() {
        AccessibilityRuntimeBridge.clearIfServiceStops()
        AccessibilityRuntimeBridge.update("com.example.one", "A")
        AccessibilityRuntimeBridge.updateFromContent(null)
        AccessibilityRuntimeBridge.updateFromContent("")
        assertEquals("A", AccessibilityRuntimeBridge.currentWindow()?.className)
    }
}
