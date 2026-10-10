package com.yagay.yauto.platform.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test

class XposedRuntimeInitHookPolicyTest {
    private class FakeRuntimeHandler {
        fun uncaughtException(thread: Thread, failure: Throwable) = Unit
        fun uncaughtException(reason: String) = Unit
        fun unrelated(failure: Throwable) = Unit
    }

    @Test fun selectsOnlyCrashHandlerMethods() {
        val methods = FakeRuntimeHandler::class.java.declaredMethods
        val chosen = methods.filter(XposedRuntimeInitHookPolicy::isCrashHandler)
        assertTrue(chosen.size == 1)
        assertTrue(chosen.single().parameterTypes.contains(Throwable::class.java))
        assertFalse(methods.filter { it.name == "unrelated" }.any(XposedRuntimeInitHookPolicy::isCrashHandler))
    }

    @Test fun installationKeyIsPerPackageAndMethod() {
        val method = FakeRuntimeHandler::class.java.getDeclaredMethod(
            "uncaughtException", Thread::class.java, Throwable::class.java,
        )
        val first = XposedRuntimeInitHookPolicy.installationKey("a", method)
        assertTrue(first.startsWith("shortx-runtime-init|a|"))
        assertNotEquals(first, XposedRuntimeInitHookPolicy.installationKey("b", method))
    }
}
