package com.yagay.yauto.platform.xposed

import android.view.InputEvent
import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XposedInputHookMethodPolicyTest {
    private class Callbacks {
        fun explicit(event: InputEvent) = Unit
        fun manufacturerGesture(event: KeyEvent) = Unit
        fun unrelated(value: String) = Unit
    }

    @Test fun explicitlyNamedInputCallbackIsMatched() {
        val method = Callbacks::class.java.getDeclaredMethod("explicit", InputEvent::class.java)
        assertTrue(XposedInputHookMethodPolicy.matches(method, setOf("explicit"), false))
        assertFalse(XposedInputHookMethodPolicy.matches(method, setOf("other"), false))
    }

    @Test fun manufacturerCallbackMatchesOnKeyEventRatherThanMethodName() {
        val key = Callbacks::class.java.getDeclaredMethod("manufacturerGesture", KeyEvent::class.java)
        val unrelated = Callbacks::class.java.getDeclaredMethod("unrelated", String::class.java)
        assertTrue(XposedInputHookMethodPolicy.matches(key, emptySet(), true))
        assertFalse(XposedInputHookMethodPolicy.matches(unrelated, emptySet(), true))
    }
}
