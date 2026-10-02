package com.yagay.yauto.platform.android

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class MediaOrganizerFeatureHelpersTest {
    @Test fun `media transport commands map to Android media keys`() {
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, mediaKeyCode("play_pause"))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY, mediaKeyCode("play"))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PAUSE, mediaKeyCode("pause"))
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, mediaKeyCode("next"))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, mediaKeyCode("previous"))
        assertEquals(KeyEvent.KEYCODE_MEDIA_STOP, mediaKeyCode("stop"))
        assertEquals(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, mediaKeyCode("fast_forward"))
        assertEquals(KeyEvent.KEYCODE_MEDIA_REWIND, mediaKeyCode("rewind"))
        assertNull(mediaKeyCode("unknown"))
    }

    @Test fun `alarm time accepts only whole valid clock values`() {
        assertEquals(0 to 0, validatedAlarmTime(0.0, 0.0))
        assertEquals(23 to 59, validatedAlarmTime(23.0, 59.0))
        assertNull(validatedAlarmTime(24.0, 0.0))
        assertNull(validatedAlarmTime(12.0, 60.0))
        assertNull(validatedAlarmTime(12.5, 30.0))
        assertNull(validatedAlarmTime(Double.NaN, 30.0))
    }

    @Test fun `timer requires bounded whole seconds`() {
        assertEquals(1, validatedTimerSeconds(1.0))
        assertEquals(604800, validatedTimerSeconds(604800.0))
        assertNull(validatedTimerSeconds(0.0))
        assertNull(validatedTimerSeconds(604801.0))
        assertNull(validatedTimerSeconds(1.5))
        assertNull(validatedTimerSeconds(Double.POSITIVE_INFINITY))
    }

    @Test fun `calendar window uses relative minutes and validates bounds`() {
        assertEquals(160_000L to 280_000L, calendarWindow(100_000L, 1.0, 2.0))
        assertNull(calendarWindow(100_000L, -1.0, 10.0))
        assertNull(calendarWindow(100_000L, 0.0, 0.0))
        assertNull(calendarWindow(100_000L, 525601.0, 10.0))
        assertNull(calendarWindow(Long.MAX_VALUE - 10L, 1.0, 1.0))
    }
}
