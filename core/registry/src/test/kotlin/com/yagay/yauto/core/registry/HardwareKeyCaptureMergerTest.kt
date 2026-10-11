package com.yagay.yauto.core.registry

import org.junit.Assert.*
import org.junit.Test

class HardwareKeyCaptureMergerTest {
    private fun snapshot(
        backend: String, key: Int, linux: Int = 0, device: Int = -1,
        descriptor: String = "", scan: Int = 0,
    ) = HardwareKeyCaptureResult(
        keyCode = key, scanCode = scan, deviceId = device, action = 1,
        linuxEvKey = linux, deviceDescriptor = descriptor,
        sources = setOf(backend),
    )

    @Test fun differentButtonsInsideWindowNeverMixAndroidAndLinuxIdentities() {
        val result = mergeHardwareKeyCaptures(listOf(
            snapshot("root.input", 25, 114, descriptor = "/dev/input/event3"),
            snapshot("lsposed", 24, device = 8),
            snapshot("accessibility", 24, device = 8),
        ))!!
        assertEquals(24, result.keyCode)
        assertEquals(0, result.linuxEvKey)
        assertFalse("root.input" in result.sources)
        assertEquals(setOf("lsposed", "accessibility"), result.sources)
    }

    @Test fun correctlyMappedRawObservationAugmentsPrimary() {
        val result = mergeHardwareKeyCaptures(listOf(
            snapshot("root.input", 24, 115, descriptor = "/dev/input/event2"),
            snapshot("lsposed", 24, device = 8),
        ))!!
        assertEquals(24, result.keyCode)
        assertEquals(115, result.linuxEvKey)
        assertEquals("/dev/input/event2", result.deviceDescriptor)
        assertEquals(setOf("lsposed", "root.input"), result.sources)
    }

    @Test fun independentDevicesWithSameAndroidKeyAreNotMerged() {
        val result = mergeHardwareKeyCaptures(listOf(
            snapshot("lsposed", 24, device = 7),
            snapshot("accessibility", 24, device = 9),
        ))!!
        assertEquals(setOf("lsposed"), result.sources)
    }

    @Test fun oemOnlyRawKeyStillUsable() {
        val result = mergeHardwareKeyCaptures(listOf(
            snapshot("root.input", 0, 780, descriptor = "/dev/input/event7"),
        ))!!
        assertEquals(780, result.linuxEvKey)
        assertEquals(0, result.keyCode)
    }
}
