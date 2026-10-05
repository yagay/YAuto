package com.yagay.yauto.importer.tasker

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskerCalendarParityTest {
    @Test
    fun calendarInsertUsesDirectProviderFeature() {
        assertEquals("android.calendar.event.insert", TaskerFeatureSuggestions.action("567"))
    }
}
