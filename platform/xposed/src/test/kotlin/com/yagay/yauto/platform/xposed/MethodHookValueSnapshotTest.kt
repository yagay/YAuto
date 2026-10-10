package com.yagay.yauto.platform.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MethodHookValueSnapshotTest {
    @Test fun capsAndFiltersValues() {
        val values = MethodHookValueSnapshot.capture(listOf("x".repeat(400), 12, true, object {
            override fun toString(): String = error("Must not invoke arbitrary toString")
        }), false, true)
        assertEquals(256, values["arg0"]?.length)
        assertEquals("12", values["arg1"])
        assertEquals("true", values["arg2"])
        assertFalse("arg3" in values)
        assertEquals("false", values["result"])
    }

    @Test fun capsArgumentCountAndDefaultsToNoReturn() {
        val result = MethodHookValueSnapshot.capture((0..20).toList())
        assertEquals((0 until 8).map { "arg$it" }.toSet(), result.keys)
        assertFalse("result" in result)
    }
}
