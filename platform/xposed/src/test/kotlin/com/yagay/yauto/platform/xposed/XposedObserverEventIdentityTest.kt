package com.yagay.yauto.platform.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class XposedObserverEventIdentityTest {
    @Test fun identityRetainsLegacyKeyOrder() {
        val extras = mapOf<String, Any?>(
            "package" to "com.example",
            "activity" to ".MainActivity",
            "processName" to "com.example:remote",
            "state" to "active",
            "method" to "start",
        )
        assertEquals(
            "activity-hook:com.example:.MainActivity:com.example:remote:active",
            XposedObserverEventIdentity.from("activity-hook", extras),
        )
    }

    @Test fun unrelatedArgumentsDoNotChangeEventIdentity() {
        val base = mapOf<String, Any?>("package" to "com.example")
        assertEquals(
            XposedObserverEventIdentity.from("hook", base),
            XposedObserverEventIdentity.from("hook", base + ("arg0" to 123)),
        )
        assertNotEquals(
            XposedObserverEventIdentity.from("hook", base),
            XposedObserverEventIdentity.from("other-hook", base),
        )
    }
}
