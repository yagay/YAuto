package com.yagay.yauto.core.registry

import org.junit.Assert.assertEquals
import org.junit.Test

class FeaturePickerCategoryTest {
    @Test
    fun `actions follow MacroDroid action categories`() {
        val cases = mapOf(
            "ai.text.generate" to FeaturePickerCategory.AI,
            "android.app.launch" to FeaturePickerCategory.APPLICATIONS,
            "android.screen.screenshot" to FeaturePickerCategory.CAMERA_PHOTO,
            "android.wifi.network.connect" to FeaturePickerCategory.CONNECTIVITY,
            "android.stopwatch.start" to FeaturePickerCategory.DATE_TIME,
            "android.device.reboot" to FeaturePickerCategory.DEVICE_ACTIONS,
            "android.settings.page.open" to FeaturePickerCategory.DEVICE_SETTINGS,
            "file.read_text" to FeaturePickerCategory.FILES,
            "android.location.update" to FeaturePickerCategory.LOCATION,
            "android.logcat.query" to FeaturePickerCategory.LOGGING,
            "flow.delay" to FeaturePickerCategory.CONDITIONS_LOOPS,
            "variable.set" to FeaturePickerCategory.YAUTO_SPECIFIC,
            "android.audio.play" to FeaturePickerCategory.MEDIA,
            "android.sms.compose" to FeaturePickerCategory.MESSAGING,
            "android.notification.reply" to FeaturePickerCategory.NOTIFICATIONS,
            "android.phone.dial" to FeaturePickerCategory.PHONE,
            "android.display.brightness.set" to FeaturePickerCategory.SCREEN,
            "android.audio.volume.set" to FeaturePickerCategory.VOLUME,
            "android.http.request" to FeaturePickerCategory.WEB_INTERACTIONS,
        )

        cases.forEach { (id, expected) ->
            assertEquals(id, expected, classify(id, FeatureKind.ACTION))
        }
    }

    @Test
    fun `action-only semantics do not leak trigger and constraint categories`() {
        assertEquals(
            FeaturePickerCategory.DEVICE_ACTIONS,
            classify("android.power.wake_lock.acquire", FeatureKind.ACTION),
        )
        assertEquals(
            FeaturePickerCategory.DEVICE_SETTINGS,
            classify("android.power_save.set", FeatureKind.ACTION),
        )
        assertEquals(
            FeaturePickerCategory.DEVICE_ACTIONS,
            classify("android.sensor.read", FeatureKind.ACTION),
        )
        assertEquals(
            FeaturePickerCategory.DEVICE_ACTIONS,
            classify("android.input.keyevent", FeatureKind.ACTION),
        )
    }

    @Test
    fun `events follow MacroDroid trigger categories`() {
        val cases = mapOf(
            "android.event.package_added" to FeaturePickerCategory.APPLICATIONS,
            "android.event.battery_changed" to FeaturePickerCategory.BATTERY_POWER,
            "android.event.phone_state_changed" to FeaturePickerCategory.CALL_SMS,
            "android.event.sms_received" to FeaturePickerCategory.CALL_SMS,
            "android.event.wifi_changed" to FeaturePickerCategory.CONNECTIVITY,
            "android.event.time_tick" to FeaturePickerCategory.DATE_TIME,
            "android.event.location_changed" to FeaturePickerCategory.LOCATION,
            "android.event.media_track_changed" to FeaturePickerCategory.MEDIA,
            "android.event.notification_posted" to FeaturePickerCategory.NOTIFICATIONS,
            "android.event.screen_on" to FeaturePickerCategory.SCREEN,
            "android.event.sensor_value" to FeaturePickerCategory.SENSORS,
            "android.event.hardware_key" to FeaturePickerCategory.USER_INPUT,
            "android.event.webhook" to FeaturePickerCategory.WEB_INTERACTIONS,
            "android.event.boot" to FeaturePickerCategory.DEVICE_EVENTS,
        )

        cases.forEach { (id, expected) ->
            assertEquals(id, expected, classify(id, FeatureKind.EVENT))
        }
    }

    @Test
    fun `file camera and log events use device events instead of action categories`() {
        assertEquals(
            FeaturePickerCategory.DEVICE_EVENTS,
            classify("android.event.file_changed", FeatureKind.EVENT),
        )
        assertEquals(
            FeaturePickerCategory.DEVICE_EVENTS,
            classify("android.event.photo_taken", FeatureKind.EVENT),
        )
        assertEquals(
            FeaturePickerCategory.DEVICE_EVENTS,
            classify("android.event.logcat_match", FeatureKind.EVENT),
        )
    }

    @Test
    fun `states and constraints follow MacroDroid constraint categories`() {
        val cases = mapOf(
            "android.condition.battery_level" to FeaturePickerCategory.BATTERY_POWER,
            "android.condition.network" to FeaturePickerCategory.CONNECTIVITY,
            "android.condition.day_of_week" to FeaturePickerCategory.DATE_TIME,
            "android.condition.location_available" to FeaturePickerCategory.LOCATION,
            "android.condition.audio.music_active" to FeaturePickerCategory.MEDIA,
            "android.condition.notification_present" to FeaturePickerCategory.NOTIFICATIONS,
            "android.condition.phone_idle" to FeaturePickerCategory.PHONE,
            "android.condition.screen" to FeaturePickerCategory.SCREEN,
            "android.condition.sensor_value" to FeaturePickerCategory.SENSORS,
            "android.condition.media_volume" to FeaturePickerCategory.VOLUME,
            "android.condition.device_locked" to FeaturePickerCategory.DEVICE_STATE,
        )

        cases.forEach { (id, expected) ->
            assertEquals(id, expected, classify(id, FeatureKind.CONDITION))
        }
    }

    @Test
    fun `application file input and web states collapse into valid constraint categories`() {
        assertEquals(
            FeaturePickerCategory.DEVICE_STATE,
            classify("android.condition.app_installed", FeatureKind.CONDITION),
        )
        assertEquals(
            FeaturePickerCategory.DEVICE_STATE,
            classify("android.condition.storage_available", FeatureKind.CONDITION),
        )
        assertEquals(
            FeaturePickerCategory.DEVICE_STATE,
            classify("android.condition.accessibility_enabled", FeatureKind.CONDITION),
        )
        assertEquals(
            FeaturePickerCategory.CONNECTIVITY,
            classify("android.condition.websocket_connected", FeatureKind.CONDITION),
        )
    }

    private fun classify(
        id: String,
        kind: FeatureKind,
        legacyCategory: FeatureCategory = when (kind) {
            FeatureKind.ACTION -> FeatureCategory.DEVICE
            FeatureKind.EVENT -> FeatureCategory.SYSTEM
            FeatureKind.STATE, FeatureKind.CONDITION -> FeatureCategory.DEVICE
        },
    ): FeaturePickerCategory = inferFeaturePickerCategory(id, kind, legacyCategory)
}
