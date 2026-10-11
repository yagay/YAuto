package com.yagay.yauto.platform.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XposedEventTransportHealthTest {
    @Test fun acceptedBroadcastIsNotMislabeledAsRuntimeDelivery() {
        val before = XposedEventTransportHealth.snapshot()
        XposedEventTransportHealth.onAccepted("android.event.hardware_key")
        val acceptedOnly = XposedEventTransportHealth.snapshot()
        assertEquals(before.accepted + 1L, acceptedOnly.accepted)
        assertEquals(before.forwarded, acceptedOnly.forwarded)
        assertEquals("android.event.hardware_key", acceptedOnly.lastAcceptedType)

        XposedEventTransportHealth.onForwarded("android.event.hardware_key")
        val forwarded = XposedEventTransportHealth.snapshot()
        assertEquals(before.forwarded + 1L, forwarded.forwarded)
        assertEquals("android.event.hardware_key", forwarded.lastForwardedType)
    }

    @Test fun rejectedPackageEventBecomesVisibleWithoutAnAuthenticationBypass() {
        val before = XposedEventTransportHealth.snapshot()
        XposedEventTransportHealth.onRejected(
            "android.event.package", "lsposed.package", -1, "sender UID not authenticated",
        )
        val after = XposedEventTransportHealth.snapshot()
        assertEquals(before.rejected + 1L, after.rejected)
        assertTrue(after.lastRejectedReason.contains("uid=-1"))
        assertTrue(after.lastRejectedReason.contains("lsposed.package"))
        assertEquals(before.accepted, after.accepted)
    }

    @Test fun callbackFailureCannotMasqueradeAsAuthenticatedDelivery() {
        val before = XposedEventTransportHealth.snapshot()
        XposedEventTransportHealth.onRuntimeFailure(
            "android.event.test", IllegalStateException("do-not-log-credentials"),
        )
        val after = XposedEventTransportHealth.snapshot()
        assertEquals(before.runtimeFailures + 1L, after.runtimeFailures)
        assertEquals(before.forwarded, after.forwarded)
        assertEquals(before.rejected, after.rejected)
        assertTrue(after.lastRuntimeFailureEpochMs > 0L)
    }

    @Test fun rejectReasonIsBoundedToAvoidUntrustedLogFlood() {
        XposedEventTransportHealth.onRejected("a".repeat(1000), "s".repeat(1000), -1, "r".repeat(1000))
        val reason = XposedEventTransportHealth.snapshot().lastRejectedReason
        assertTrue(reason.length < 300)
    }
}
