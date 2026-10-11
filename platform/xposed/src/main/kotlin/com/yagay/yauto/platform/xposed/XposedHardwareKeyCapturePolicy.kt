package com.yagay.yauto.platform.xposed

/** A one-shot learner must not complete on KEYCODE_UNKNOWN or malformed actions. */
internal object XposedHardwareKeyCapturePolicy {
    fun accepts(keyCode: Int, action: Int): Boolean =
        keyCode > 0 && (action == 0 || action == 1) // KeyEvent.ACTION_DOWN / ACTION_UP
}
