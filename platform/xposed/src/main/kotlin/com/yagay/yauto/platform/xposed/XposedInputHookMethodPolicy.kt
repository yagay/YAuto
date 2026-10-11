package com.yagay.yauto.platform.xposed

import android.view.KeyEvent
import java.lang.reflect.Method

/** Selects the explicit framework callbacks, plus OEM methods accepting a KeyEvent. */
internal object XposedInputHookMethodPolicy {
    fun matches(method: Method, methodNames: Set<String>, matchAnyKeyEventMethod: Boolean): Boolean {
        if (matchAnyKeyEventMethod) {
            // OEM callback names vary; capture only methods with a KeyEvent argument.
            return method.parameterTypes.any { KeyEvent::class.java.isAssignableFrom(it) }
        }
        // ShortX hooks named input callbacks by method name (including OEM
        // overloads), then inspects actual invocation arguments. Some vendor
        // versions declare an InputEvent-compatible parameter as Object or a
        // private wrapper; a declaration-only KeyEvent filter skips them.
        // Restrict installation to the explicitly named input callbacks.
        return method.name in methodNames
    }
}
