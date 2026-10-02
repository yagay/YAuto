package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.ConfigValue
import org.junit.Assert.*
import org.junit.Test

class AndroidLocationRadiusFeaturePackTest {
    @Test fun `distance calculation is stable for nearby London points`() {
        val meters = distanceMeters(51.5074, -0.1278, 51.5084, -0.1278)
        assertTrue(meters in 110.0..112.5)
    }

    @Test fun `radius matcher supports inside outside and stale location`() {
        val now = 1_000_000L
        val current = LocationSnapshot(51.5074, -0.1278, now - 60_000L)
        val base = mapOf(
            "latitude" to ConfigValue.NumberValue(51.5084),
            "longitude" to ConfigValue.NumberValue(-0.1278),
            "radiusMeters" to ConfigValue.NumberValue(150.0),
        )
        assertTrue(matchesLocationRadius(base, current, now))
        assertTrue(matchesLocationRadius(base + ("inside" to ConfigValue.BooleanValue(false)) +
            ("radiusMeters" to ConfigValue.NumberValue(50.0)), current, now))
        assertFalse(matchesLocationRadius(base + ("maxAgeMinutes" to ConfigValue.NumberValue(0.5)), current, now))
        assertTrue(matchesLocationRadius(base + ("maxAgeMinutes" to ConfigValue.NumberValue(2.0)), current, now))
    }

    @Test fun `invalid coordinates and radius fail closed`() {
        val current = LocationSnapshot(0.0, 0.0, 0L)
        assertFalse(matchesLocationRadius(mapOf(
            "latitude" to ConfigValue.NumberValue(91.0),
            "longitude" to ConfigValue.NumberValue(0.0),
            "radiusMeters" to ConfigValue.NumberValue(100.0),
        ), current, 0L))
        assertFalse(matchesLocationRadius(mapOf(
            "latitude" to ConfigValue.NumberValue(0.0),
            "longitude" to ConfigValue.NumberValue(0.0),
            "radiusMeters" to ConfigValue.NumberValue(0.0),
        ), current, 0L))
    }
}
