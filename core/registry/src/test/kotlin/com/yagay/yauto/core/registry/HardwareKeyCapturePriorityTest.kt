package com.yagay.yauto.core.registry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareKeyCapturePriorityTest {
    private fun capture(source: String, keyCode: Int, scan: Int = 0, linux: Int = 0) =
        HardwareKeyCaptureResult(
            keyCode = keyCode,
            scanCode = scan,
            linuxEvKey = linux,
            deviceId = -1,
            action = 1,
            sources = setOf(source),
        )

    @Test fun realAndroidKeyCodeBeatsRawRootSampleFromSameOemButton() {
        val root = capture("root.input", keyCode = 0, linux = 735)
        val framework = capture("lsposed", keyCode = 780, scan = 735)
        assertTrue(hardwareKeyCapturePriority(framework) > hardwareKeyCapturePriority(root))
        assertEquals(framework, listOf(root, framework).maxByOrNull(::hardwareKeyCapturePriority))
    }

    @Test fun accessibilityAndroidCodeBeatsRootRawWhenSystemHookIsUnavailable() {
        val raw = capture("root.input", keyCode = 0, linux = 735)
        val android = capture("accessibility", keyCode = 780, scan = 735)
        assertTrue(hardwareKeyCapturePriority(android) > hardwareKeyCapturePriority(raw))
    }

    @Test fun lsposedWinsBetweenTwoActualAndroidKeyObservations() {
        val hook = capture("lsposed", keyCode = 780, scan = 735)
        val accessibility = capture("accessibility", keyCode = 780, scan = 735)
        assertTrue(hardwareKeyCapturePriority(hook) > hardwareKeyCapturePriority(accessibility))
    }

    @Test fun unconvertedScanDoesNotOutrankTrueAndroidKeyCode() {
        val root = capture("root.input", keyCode = 0, linux = 735)
        val hookUnknown = capture("lsposed", keyCode = 0, scan = 735)
        val other = capture("accessibility", keyCode = 780, scan = 735)
        assertTrue(hardwareKeyCapturePriority(other) > hardwareKeyCapturePriority(hookUnknown))
        assertTrue(hardwareKeyCapturePriority(hookUnknown) > hardwareKeyCapturePriority(root))
    }
}
