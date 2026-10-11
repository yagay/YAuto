package com.yagay.yauto.core.capability

import com.yagay.yauto.core.model.ConfigValue
import org.junit.Assert.*
import org.junit.Test

class ShellAccessibilityOperationsTest {
    private fun num(value: Double) = ConfigValue.NumberValue(value)
    private fun request(id: String, payload: Map<String, ConfigValue>) =
        CapabilityRequest(CapabilityIds.ACCESSIBILITY, id, payload)

    @Test fun globalActionMappingRespectsUnsupportedCommands() {
        val back = request(ShellAccessibilityOperations.GLOBAL_ACTION,
            mapOf("action" to ConfigValue.StringValue("back")))
        assertEquals("input keyevent 4", ShellAccessibilityOperations.command(back))
        assertEquals("input keyevent 187", ShellAccessibilityOperations.command(back.copy(
            payload = mapOf("action" to ConfigValue.StringValue("recents")))))
        assertNull(ShellAccessibilityOperations.command(back.copy(
            payload = mapOf("action" to ConfigValue.StringValue("power_dialog")))))
    }

    @Test fun coordinateShellRoutesRejectUnsafeOrInvalidData() {
        assertEquals("input swipe 100 200 100 200 40",
            ShellAccessibilityOperations.command(request(ShellAccessibilityOperations.TAP,
                mapOf("x" to num(100.0), "y" to num(200.0)))))
        assertEquals("input swipe 1 2 50 60 300",
            ShellAccessibilityOperations.command(request(ShellAccessibilityOperations.SWIPE,
                mapOf("x1" to num(1.0), "y1" to num(2.0), "x2" to num(50.0), "y2" to num(60.0)))))
        assertNull(ShellAccessibilityOperations.command(request(ShellAccessibilityOperations.TAP,
            mapOf("x" to num(Double.NaN), "y" to num(2.0)))))
        assertNull(ShellAccessibilityOperations.command(request(ShellAccessibilityOperations.TAP,
            mapOf("x" to num(-1.0), "y" to num(2.0)))))
        assertNull(ShellAccessibilityOperations.command(request("accessibility.click_text", emptyMap())))
        val global = request(ShellAccessibilityOperations.GLOBAL_ACTION,
            mapOf("action" to ConfigValue.StringValue("notifications")))
        assertEquals(SystemOperations.EXPAND_NOTIFICATIONS,
            ShellAccessibilityOperations.systemUiOperation(global))
        assertEquals(SystemOperations.EXPAND_QUICK_SETTINGS,
            ShellAccessibilityOperations.systemUiOperation(global.copy(
                payload = mapOf("action" to ConfigValue.StringValue("quick_settings")))))
        assertEquals(SystemOperations.SLEEP,
            ShellAccessibilityOperations.systemUiOperation(global.copy(
                payload = mapOf("action" to ConfigValue.StringValue("lock_screen")))))
        assertNull(ShellAccessibilityOperations.systemUiOperation(global.copy(
            payload = mapOf("action" to ConfigValue.StringValue("back")))))
        assertNull(ShellAccessibilityOperations.systemUiOperation(
            request(ShellAccessibilityOperations.TAP,
                mapOf("x" to num(1.0), "y" to num(1.0)))))
    }
}
