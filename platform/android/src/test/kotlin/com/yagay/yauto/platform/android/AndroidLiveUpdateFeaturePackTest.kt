package com.yagay.yauto.platform.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidLiveUpdateFeaturePackTest {
    @Test fun `a Live Update ID is scoped and cannot inject unexpected notification tags`() {
        assertTrue(liveUpdateTagValid("ride_123"))
        assertTrue(liveUpdateTagValid("delivery.current"))
        assertFalse(liveUpdateTagValid(""))
        assertFalse(liveUpdateTagValid("../download"))
        assertFalse(liveUpdateTagValid("a".repeat(49)))
        assertFalse(liveUpdateTagValid("test;rm"))
    }
}
