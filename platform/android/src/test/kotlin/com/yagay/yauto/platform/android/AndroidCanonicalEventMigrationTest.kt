package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCanonicalEventMigrationTest {
    @Test
    fun `audio focus legacy aliases preserve gain and any-loss semantics`() {
        assertEquals(
            ConfigValue.StringValue("gain"),
            AUDIO_FOCUS_ALIAS_CONFIG_DEFAULTS.getValue("android.event.audio_focus_gain").getValue("change"),
        )
        assertEquals(
            ConfigValue.StringValue("loss_any"),
            AUDIO_FOCUS_ALIAS_CONFIG_DEFAULTS.getValue("android.event.audio_focus_lost").getValue("change"),
        )

        assertTrue(audioFocusChangeMatches("gain", "gain"))
        assertTrue(audioFocusChangeMatches("loss_any", "loss"))
        assertTrue(audioFocusChangeMatches("loss_any", "loss_transient"))
        assertTrue(audioFocusChangeMatches("loss_any", "loss_can_duck"))
        assertFalse(audioFocusChangeMatches("loss_any", "gain"))
    }

    @Test
    fun `ShortX hook events use stable semantic domains for unified picker groups`() {
        val userInput = listOf(
            "android.event.input_filter_state_changed",
            "android.event.input_manager_started",
            "android.event.accessibility_windows_queried",
            "android.event.ime_shown",
            "android.event.window_focus_changed",
            "android.event.systemui_qs_tile_clicked",
        )
        userInput.forEach {
            assertEquals(it, FeatureDomain.USER_INPUT, shortXHookDomain(it, FeatureCategory.SYSTEM))
        }

        val applications = listOf(
            "android.event.activity_manager_started",
            "android.event.activity_resumed",
            "android.event.task_cleanup",
            "android.event.shortcut_query",
            "android.event.process_uncaught_exception",
        )
        applications.forEach {
            assertEquals(it, FeatureDomain.APPLICATIONS, shortXHookDomain(it, FeatureCategory.SYSTEM))
        }

        assertEquals(
            FeatureDomain.NOTIFICATIONS,
            shortXHookDomain("android.event.notification_posted_system", FeatureCategory.SYSTEM),
        )
        assertEquals(
            FeatureDomain.COMMUNICATION,
            shortXHookDomain("android.event.sms_provider_changed", FeatureCategory.SYSTEM),
        )
        assertEquals(
            FeatureDomain.FILES_STORAGE,
            shortXHookDomain("android.event.media_provider_changed", FeatureCategory.SYSTEM),
        )
    }
}
