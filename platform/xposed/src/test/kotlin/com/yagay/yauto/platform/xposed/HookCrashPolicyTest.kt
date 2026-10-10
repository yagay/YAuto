package com.yagay.yauto.platform.xposed

import org.junit.Assert.*
import org.junit.Test

class HookCrashPolicyTest {
    @Test fun quarantinesAfterThreeFatalCrashesInWindow() {
        val one = HookCrashPolicy.record(HookCrashWindow(), 100_000)
        val two = HookCrashPolicy.record(one, 150_000)
        val three = HookCrashPolicy.record(two, 195_000)
        assertFalse(one.quarantined)
        assertFalse(two.quarantined)
        assertTrue(three.quarantined)
        assertEquals(3, three.strikes)
    }

    @Test fun resetsOnExpiredWindow() {
        val one = HookCrashPolicy.record(HookCrashWindow(), 1_000)
        val two = HookCrashPolicy.record(one, 121_001)
        assertEquals(1, two.strikes)
        assertEquals(121_001L, two.startedAtMs)
    }

    @Test fun resetsIfClockMovesBackwards() {
        assertEquals(1, HookCrashPolicy.record(HookCrashWindow(1000, 2), 500).strikes)
    }

    @Test fun quarantineIsStickyUntilUserClears() {
        val stopped = HookCrashWindow(1000, 3, true)
        assertEquals(stopped, HookCrashPolicy.record(stopped, 4000))
    }
}
