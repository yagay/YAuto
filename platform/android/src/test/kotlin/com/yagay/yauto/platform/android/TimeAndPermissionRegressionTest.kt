package com.yagay.yauto.platform.android

import android.Manifest
import java.time.LocalTime
import java.time.ZonedDateTime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeAndPermissionRegressionTest {
    @Test fun `overnight window uses preceding day after midnight`() {
        val start = LocalTime.of(23, 0)
        val end = LocalTime.of(2, 0)
        val late = ZonedDateTime.parse("2026-10-04T23:30:00+01:00")
        val afterMidnight = ZonedDateTime.parse("2026-10-05T01:15:00+01:00")
        assertEquals(late.toLocalDate(), shortXWindowStartDate(late, start, end))
        assertEquals(late.toLocalDate(), shortXWindowStartDate(afterMidnight, start, end))
        assertEquals(late.dayOfWeek, shortXWindowStartDate(afterMidnight, start, end).dayOfWeek)
    }

    @Test fun `daytime window retains current date`() {
        val time = ZonedDateTime.parse("2026-10-05T01:30:00+01:00")
        assertEquals(
            time.toLocalDate(),
            shortXWindowStartDate(time, LocalTime.of(1, 0), LocalTime.of(5, 0)),
        )
    }

    @Test fun `sms read and send permissions are separate`() {
        assertArrayEquals(
            arrayOf(Manifest.permission.READ_SMS),
            runtimePermissionsForFeature("sms", "android.sms.query"),
        )
        assertArrayEquals(
            arrayOf(Manifest.permission.SEND_SMS),
            runtimePermissionsForFeature("sms", "android.sms.send"),
        )
        assertArrayEquals(
            arrayOf(Manifest.permission.READ_SMS),
            runtimePermissionsForFeature("sms", "android.event.sms_database_entry"),
        )
    }

    @Test fun `calendar read and write permissions are separate`() {
        assertArrayEquals(
            arrayOf(Manifest.permission.READ_CALENDAR),
            runtimePermissionsForFeature("calendar", "android.calendar.events.query"),
        )
        assertArrayEquals(
            arrayOf(Manifest.permission.WRITE_CALENDAR),
            runtimePermissionsForFeature("calendar", "android.calendar.event.insert"),
        )
        assertArrayEquals(
            arrayOf(Manifest.permission.READ_CALENDAR),
            runtimePermissionsForFeature("calendar", "android.state.calendar_event"),
        )
    }

    @Test fun `direct calling, call answering and subscription queries need distinct permissions`() {
        assertArrayEquals(
            arrayOf(Manifest.permission.CALL_PHONE),
            runtimePermissionsForFeature("phone", "android.phone.call"),
        )
        assertArrayEquals(
            arrayOf(Manifest.permission.ANSWER_PHONE_CALLS),
            runtimePermissionsForFeature("phone", "android.phone.answer"),
        )
        assertArrayEquals(
            arrayOf(Manifest.permission.READ_PHONE_STATE),
            runtimePermissionsForFeature("phone", "android.telephony.subscription.info"),
        )
    }

    @Test fun `generic permission groups remain comprehensive`() {
        assertEquals(3, runtimePermissionsForFeature("sms", "android.permission.request").size)
        assertEquals(2, runtimePermissionsForFeature("calendar", "android.permission.request").size)
    }
}
