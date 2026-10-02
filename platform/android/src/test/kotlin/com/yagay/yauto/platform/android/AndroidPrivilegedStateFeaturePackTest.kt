package com.yagay.yauto.platform.android

import org.junit.Assert.*
import org.junit.Test

class AndroidPrivilegedStateFeaturePackTest {
    @Test fun `text comparison supports literal and regex modes`() {
        assertTrue(compareText("hello world", "equals", "hello world"))
        assertTrue(compareText("hello world", "contains", "world"))
        assertTrue(compareText("hello world", "not_contains", "bye"))
        assertTrue(compareText("abc-123", "regex", "[a-z]+-\\d+"))
        assertFalse(compareText("abc", "regex", "["))
        assertFalse(compareText("same", "not_equals", "same"))
    }

    @Test fun `appops output parser recognizes common modes`() {
        assertEquals("allow", parseAppOpMode("android:camera: allow; time=+2m"))
        assertEquals("foreground", parseAppOpMode("android:fine_location: foreground"))
        assertEquals("default", parseAppOpMode("No operations."))
        assertNull(parseAppOpMode("unexpected output"))
    }
}
