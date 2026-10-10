package com.yagay.yauto.platform.xposed

import android.view.InputEvent
import android.view.KeyEvent
import java.lang.reflect.Method

/** Selects the explicit framework callbacks, plus OEM methods accepting a KeyEvent. */
internal object XposedInputHookMethodPolicy {
    fun matches(method: Method, methodNames: Set<String>, matchAnyKeyEventMethod: Boolean): Boolean {
        if (matchAnyKeyEventMethod) {
            // OEM callback names vary; capture only methods with a KeyEvent argument.
            return method.parameterTypes.any { KeyEvent::class.java.isAssignableFrom(it) }
        }
        return method.name in methodNames &&
            method.parameterTypes.any {
                KeyEvent::class.java.isAssignableFrom(it) ||
                    InputEvent::class.java.isAssignableFrom(it)
            }
    }
}
