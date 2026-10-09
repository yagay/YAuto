package com.yagay.yauto.platform.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidScreenOnTimeFeaturePackTest {
    @Test fun `interactive screen interval includes leading and trailing portions`() {
        val timeline = listOf(
            ScreenInteractionTransition(2_000, false),
            ScreenInteractionTransition(4_000, true),
            ScreenInteractionTransition(7_000, false),
            ScreenInteractionTransition(9_000, true),
        )
        assertEquals(6_000L, calculateScreenOnTimeMs(0, 10_000, timeline))
    }

    @Test fun `first screen-on transition implies initial screen-off state`() {
        assertEquals(
            3_000L,
            calculateScreenOnTimeMs(0, 10_000, listOf(
                ScreenInteractionTransition(3_000, true),
                ScreenInteractionTransition(6_000, false),
            )),
        )
    }

    @Test fun `empty or invalid history remains unavailable`() {
        assertNull(calculateScreenOnTimeMs(0, 10_000, emptyList()))
        assertNull(calculateScreenOnTimeMs(10_000, 10_000, listOf(ScreenInteractionTransition(9_000, true))))
        assertNull(calculateScreenOnTimeMs(0, 10_000, listOf(ScreenInteractionTransition(11_000, false))))
    }

    @Test fun `duplicate screen events do not inflate the duration`() {
        val timeline = listOf(
            ScreenInteractionTransition(5_000, true),
            ScreenInteractionTransition(6_000, true),
            ScreenInteractionTransition(8_000, false),
        )
        assertEquals(3_000L, calculateScreenOnTimeMs(0, 10_000, timeline))
    }
}
