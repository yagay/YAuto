package com.yagay.yauto.platform.android

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidNativeEnvironmentFeaturePackTest {
    @Test
    fun boundedNumberAcceptsOpenAndClosedRanges() {
        assertTrue(matchesBoundedNumber(10.0, null, null, 0.0, 100.0))
        assertTrue(matchesBoundedNumber(50.0, 25.0, 75.0, 0.0, 100.0))
        assertTrue(matchesBoundedNumber(-2500.0, -5000.0, 0.0, -20_000_000.0, 20_000_000.0))
        assertTrue(matchesBoundedNumber(0.0, 0.0, 0.0, 0.0, 100.0))
        assertTrue(matchesBoundedNumber(100.0, 100.0, 100.0, 0.0, 100.0))
    }

    @Test
    fun boundedNumberRejectsInvalidRangesAndUnsupportedValues() {
        assertFalse(matchesBoundedNumber(50.0, 75.0, 25.0, 0.0, 100.0))
        assertFalse(matchesBoundedNumber(101.0, null, null, 0.0, 100.0))
        assertFalse(matchesBoundedNumber(Double.NaN, null, null, 0.0, 100.0))
        assertFalse(matchesBoundedNumber(50.0, -1.0, 75.0, 0.0, 100.0))
        assertFalse(matchesBoundedNumber(Double.POSITIVE_INFINITY, null, null, 0.0, 100.0))
    }
}
