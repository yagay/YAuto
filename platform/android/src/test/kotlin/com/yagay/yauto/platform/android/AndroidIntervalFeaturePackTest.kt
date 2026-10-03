package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AndroidIntervalFeaturePackTest {
    @Test fun `interval duration is clamped to safe runtime bounds`() {
        assertEquals(MIN_INTERVAL_MS, intervalDurationMs(feature(1.0, false)))
        assertEquals(30_000L, intervalDurationMs(feature(30_000.0, false)))
        assertEquals(MAX_INTERVAL_MS, intervalDurationMs(feature(Double.MAX_VALUE, false)))
    }

    @Test fun `subscription identity includes interval and immediate mode`() {
        assertEquals(
            "android.event.interval|30000|false",
            intervalSubscriptionKey(feature(30_000.0, false)),
        )
        assertNotEquals(
            intervalSubscriptionKey(feature(30_000.0, false)),
            intervalSubscriptionKey(feature(30_000.0, true)),
        )
    }

    private fun feature(interval: Double, immediate: Boolean) = FeatureRef(
        "android.event.interval",
        mapOf(
            "intervalMs" to ConfigValue.NumberValue(interval),
            "fireImmediately" to ConfigValue.BooleanValue(immediate),
        ),
    )
}
