package com.yagay.yauto.core.registry

/**
 * Quality of a *learned identity*, not capability-backend preference.
 *
 * A genuine Android KeyEvent.keyCode outranks an early Linux EV_KEY sample.
 * For example, a vendor button may be reported as raw Linux 735 while its
 * framework KeyEvent identifies it as Android keyCode 780. Choosing the Root
 * result merely because Root has higher privilege would lose that mapping.
 *
 * Within equal identity quality prefer LSPosed, then Root, then Accessibility.
 */
fun hardwareKeyCapturePriority(capture: HardwareKeyCaptureResult): Int {
    val identityQuality = when {
        capture.keyCode > 0 -> 300
        capture.scanCode > 0 -> 200
        capture.linuxEvKey > 0 || capture.mscScan != 0L -> 100
        else -> 0
    }
    val sourceQuality = when {
        "lsposed" in capture.sources -> 30
        "root.input" in capture.sources -> 20
        "accessibility" in capture.sources -> 10
        else -> 0
    }
    return identityQuality + sourceQuality
}
