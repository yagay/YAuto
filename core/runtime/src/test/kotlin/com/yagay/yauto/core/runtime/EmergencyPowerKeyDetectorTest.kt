package com.yagay.yauto.core.runtime

import org.junit.Assert.*
import org.junit.Test

class EmergencyPowerKeyDetectorTest {
    @Test fun triggersAfterTwelvePowerReleasesWithinWindow() {
        val d = EmergencyPowerKeyDetector()
        repeat(11) { assertFalse(d.accept(26, "up", 0, it * 500L)) }
        assertTrue(d.accept(26, "up", 0, 5_500L))
        assertFalse(d.accept(26, "up", 0, 6_000L))
    }
    @Test fun otherKeysAndAutoRepeatNeverAdvanceCounter() {
        val d = EmergencyPowerKeyDetector()
        repeat(20) {
            assertFalse(d.accept(24, "up", 0, it * 100L))
            assertFalse(d.accept(26, "down", 0, it * 100L))
            assertFalse(d.accept(26, "up", 1, it * 100L))
        }
        assertFalse(d.accept(26, "up", 0, 2_100L))
    }
    @Test fun oldEventsExpireBeforeThreshold() {
        val d = EmergencyPowerKeyDetector()
        repeat(11) { assertFalse(d.accept(26, "up", 0, it * 100L)) }
        assertFalse(d.accept(26, "up", 0, 20_000L))
    }
}
