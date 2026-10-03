package com.yagay.yauto.platform.android

import android.telephony.ServiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidTelephonyStateExpansionFeaturePackTest {
    @Test
    fun `cellular service availability only accepts in-service state`() {
        assertTrue(isCellularServiceAvailable(ServiceState.STATE_IN_SERVICE))
        assertFalse(isCellularServiceAvailable(ServiceState.STATE_OUT_OF_SERVICE))
        assertFalse(isCellularServiceAvailable(ServiceState.STATE_EMERGENCY_ONLY))
        assertFalse(isCellularServiceAvailable(ServiceState.STATE_POWER_OFF))
        assertFalse(isCellularServiceAvailable(null))
    }

    @Test
    fun `cellular service state names are stable`() {
        assertEquals("in_service", cellularServiceStateName(ServiceState.STATE_IN_SERVICE))
        assertEquals("out_of_service", cellularServiceStateName(ServiceState.STATE_OUT_OF_SERVICE))
        assertEquals("emergency_only", cellularServiceStateName(ServiceState.STATE_EMERGENCY_ONLY))
        assertEquals("power_off", cellularServiceStateName(ServiceState.STATE_POWER_OFF))
        assertEquals("unknown", cellularServiceStateName(null))
    }
}
