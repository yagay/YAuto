package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCoverageFeaturePackTest {
    @Test fun `file watch identity changes with meaningful configuration`() {
        val base = fileFeature("/tmp/yauto", "modify", false, "")
        assertNotEquals(fileWatchSubscriptionKey(base), fileWatchSubscriptionKey(fileFeature("/tmp/yauto", "delete", false, "")))
        assertNotEquals(fileWatchSubscriptionKey(base), fileWatchSubscriptionKey(fileFeature("/tmp/yauto", "modify", true, "")))
        assertNotEquals(fileWatchSubscriptionKey(base), fileWatchSubscriptionKey(fileFeature("/tmp/yauto", "modify", false, "photo")))
    }

    @Test fun `ime command validates component syntax`() {
        assertEquals("ime set 'com.example.ime/.ImeService'", imeSetCommand("com.example.ime/.ImeService"))
        assertNull(imeSetCommand("com.example.ime;reboot/.ImeService"))
        assertNull(imeSetCommand("not-a-component"))
    }

    @Test fun `screenshot command requires png and quotes the destination`() {
        assertEquals("screencap -p '/sdcard/Pictures/test.png'", screenshotCommand("/sdcard/Pictures/test.png"))
        assertTrue(screenshotCommand("/sdcard/Pictures/a'b.png")!!.contains("'\\''"))
        assertNull(screenshotCommand("/sdcard/Pictures/test.jpg"))
    }

    @Test fun `key event command enforces a bounded numeric key code`() {
        assertEquals("input keyevent 224", keyEventCommand(224))
        assertNull(keyEventCommand(-1))
        assertNull(keyEventCommand(1001))
        assertNull(keyEventCommand(null))
    }

    @Test fun `process running command rejects shell injection`() {
        assertEquals(
            "if pidof com.example.app >/dev/null 2>&1; then echo true; else echo false; fi",
            processRunningCommand("com.example.app"),
        )
        assertNull(processRunningCommand("com.example.app;reboot"))
        assertNull(processRunningCommand("invalid"))
    }

    private fun fileFeature(path: String, event: String, recursive: Boolean, nameContains: String) = FeatureRef(
        typeId = "android.event.file_changed",
        config = mapOf(
            "path" to ConfigValue.StringValue(path),
            "event" to ConfigValue.StringValue(event),
            "recursive" to ConfigValue.BooleanValue(recursive),
            "nameContains" to ConfigValue.StringValue(nameContains),
        ),
    )
}
