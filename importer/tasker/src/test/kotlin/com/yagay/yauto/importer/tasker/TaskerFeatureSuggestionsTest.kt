package com.yagay.yauto.importer.tasker

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskerFeatureSuggestionsTest {
    @Test
    fun `reference utility action codes suggest canonical YAuto features`() {
        assertEquals("android.audio.play", TaskerFeatureSuggestions.action("192"))
        assertEquals("android.app.notification_settings.open", TaskerFeatureSuggestions.action("337"))
        assertEquals("android.network.connectivity.check", TaskerFeatureSuggestions.action("341"))
        assertEquals("android.audio.default_sound.set", TaskerFeatureSuggestions.action("457"))
        assertEquals("android.status_bar.control", TaskerFeatureSuggestions.action("512"))
        assertEquals("android.call_log.open", TaskerFeatureSuggestions.action("910"))
        assertEquals("android.car_mode.set", TaskerFeatureSuggestions.action("988"))
    }
}
