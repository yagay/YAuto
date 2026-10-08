package com.yagay.yauto.platform.android

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSolarFeaturePackTest {
    @Test
    fun `London summer solstice solar times are plausible`() {
        val times = calculateSolarTimes(LocalDate.of(2026, 6, 21), 51.5074, -0.1278)
            ?: error("Solar times unavailable")
        val sunriseHour = Instant.ofEpochMilli(times.sunriseEpochMs).atZone(ZoneOffset.UTC).hour
        val sunsetHour = Instant.ofEpochMilli(times.sunsetEpochMs).atZone(ZoneOffset.UTC).hour
        assertTrue(sunriseHour in 3..5)
        assertTrue(sunsetHour in 19..21)
        assertTrue(times.sunriseEpochMs < times.sunsetEpochMs)
    }

    @Test
    fun `polar day or night can have no normal rise set pair`() {
        assertNull(calculateSolarTimes(LocalDate.of(2026, 6, 21), 89.0, 0.0))
    }
}