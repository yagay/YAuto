package com.yagay.yauto.platform.android

import android.os.PowerManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidResourceStateFeaturePackTest {
    @Test fun `number range supports open ends and rejects inverted ranges`() {
        assertTrue(matchesNumberRange(5.0, null, null))
        assertTrue(matchesNumberRange(5.0, 5.0, 10.0))
        assertTrue(matchesNumberRange(5.0, 0.0, 5.0))
        assertFalse(matchesNumberRange(5.0, 6.0, null))
        assertFalse(matchesNumberRange(5.0, 10.0, 1.0))
        assertFalse(matchesNumberRange(Double.NaN, null, null))
    }

    @Test fun `thermal names map monotonically to Android levels`() {
        assertEquals(PowerManager.THERMAL_STATUS_NONE, thermalLevel("none"))
        assertEquals(PowerManager.THERMAL_STATUS_MODERATE, thermalLevel("moderate"))
        assertEquals(PowerManager.THERMAL_STATUS_CRITICAL, thermalLevel("critical"))
        assertEquals(PowerManager.THERMAL_STATUS_SHUTDOWN, thermalLevel("shutdown"))
        assertNull(thermalLevel("unknown"))
    }
}
