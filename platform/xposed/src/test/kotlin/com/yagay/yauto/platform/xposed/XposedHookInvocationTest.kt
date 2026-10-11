package com.yagay.yauto.platform.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class XposedHookInvocationTest {
    @Test fun interceptionBoundaryPreservesReceiverArgsAndProceedResult() {
        val receiver = Any()
        var calls = 0
        val invocation = XposedHookInvocation(receiver, listOf("text", 3)) {
            calls++
            "original"
        }
        assertSame(receiver, invocation.thisObject)
        assertEquals(listOf("text", 3), invocation.args)
        assertEquals("original", invocation.proceed())
        assertEquals(1, calls)
    }
}
