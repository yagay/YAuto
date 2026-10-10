package com.yagay.yauto.platform.xposed

import org.junit.Assert.assertEquals
import org.junit.Test

class XposedUncaughtExceptionSnapshotTest {
    @Test fun snapshotRetainsCrashIdentityAndBoundsMessage() {
        val error = IllegalStateException("x".repeat(2000))
        val actual = XposedUncaughtExceptionSnapshot.create(
            "com.example.app", Thread.currentThread(), error, "uncaughtException",
        )
        assertEquals("com.example.app", actual["package"])
        assertEquals("java.lang.IllegalStateException", actual["exceptionClass"])
        assertEquals("uncaughtException", actual["method"])
        assertEquals(1024, actual["message"]?.length)
    }

    @Test fun snapshotHandlesMissingCrashDetails() {
        val actual = XposedUncaughtExceptionSnapshot.create("com.example.app", null, null, "uncaughtException")
        assertEquals("", actual["thread"])
        assertEquals("", actual["exceptionClass"])
        assertEquals("", actual["message"])
    }
}
