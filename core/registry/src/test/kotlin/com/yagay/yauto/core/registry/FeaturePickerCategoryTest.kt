package com.yagay.yauto.core.registry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeaturePickerCategoryTest {
    @Test
    fun `actions follow MacroDroid action categories`() {
        val cases = mapOf(
            "ai.text.generate" to FeaturePickerCategory.AI,
            "android.app.launch" to FeaturePickerCategory.APPLICATIONS,
            "android.screen.screenshot" to FeaturePickerCategory.CAMERA_PHOTO,
            "android.wifi.network.connect" to FeaturePickerCategory.CONNECTIVITY,
            "android.network.udp.send" to FeaturePickerCategory.WEB_INTERACTIONS,
            "json.parse" to FeaturePickerCategory.WEB_INTERACTIONS,
            "android.calendar.event.insert" to FeaturePickerCategory.LOGGING,
            "script.javascript.execute" to FeaturePickerCategory.APPLICATIONS,
            "android.macro.run" to FeaturePickerCategory.MACROS,
            "core.automation.run" to FeaturePickerCategory.MACROS,
            "core.automation.set_enabled" to FeaturePickerCategory.MACROS,
            "core.delay" to FeaturePickerCategory.YAUTO_SPECIFIC,
            "core.log" to FeaturePickerCategory.LOGGING,
            "core.category.set_enabled" to FeaturePickerCategory.MACROS,
            "core.trigger.set_enabled" to FeaturePickerCategory.MACROS,
            "core.runtime.set_enabled" to FeaturePickerCategory.MACROS,
            "data.list.append_all" to FeaturePickerCategory.VARIABLES,
            "data.object.merge" to FeaturePickerCategory.VARIABLES,
            "time.stopwatch.reset" to FeaturePickerCategory.DATE_TIME,
            "variable.increment" to FeaturePickerCategory.VARIABLES,
            "android.clipboard.write" to FeaturePickerCategory.DEVICE_ACTIONS,
            "android.wallpaper.set" to FeaturePickerCategory.DEVICE_SETTINGS,
            "android.power.stay_awake" to FeaturePickerCategory.SCREEN,
            "system.shell.exec" to FeaturePickerCategory.APPLICATIONS,
            "android.stopwatch.start" to FeaturePickerCategory.DATE_TIME,
            "android.device.reboot" to FeaturePickerCategory.DEVICE_ACTIONS,
            "android.settings.page.open" to FeaturePickerCategory.DEVICE_SETTINGS,
            "file.read_text" to FeaturePickerCategory.FILES,
            "android.location.update" to FeaturePickerCategory.LOCATION,
            "android.logcat.query" to FeaturePickerCategory.LOGGING,
            "flow.delay" to FeaturePickerCategory.YAUTO_SPECIFIC,
            "flow.if" to FeaturePickerCategory.CONDITIONS_LOOPS,
            "variable.set" to FeaturePickerCategory.VARIABLES,
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
    fun `MacroDroid 5_67_8 importer targets map to correct picker categories`() {
        val actions = mapOf(
            "android.airplane_mode.set" to FeaturePickerCategory.CONNECTIVITY,
            "android.bluetooth.set" to FeaturePickerCategory.CONNECTIVITY,
            "android.mobile_data.set" to FeaturePickerCategory.CONNECTIVITY,
            "android.wifi.set" to FeaturePickerCategory.CONNECTIVITY,
            "android.torch.set" to FeaturePickerCategory.CAMERA_PHOTO,
            "android.audio.microphone_mute.set" to FeaturePickerCategory.MEDIA,
            "android.audio.speakerphone.set" to FeaturePickerCategory.MEDIA,
            "android.vibrate" to FeaturePickerCategory.DEVICE_ACTIONS,
            "android.audio.volume.adjust" to FeaturePickerCategory.VOLUME,
            "android.audio.play" to FeaturePickerCategory.MEDIA,
            "android.share.text" to FeaturePickerCategory.MESSAGING,
            "android.shell.execute" to FeaturePickerCategory.APPLICATIONS,
            "android.notification.dismiss_all" to FeaturePickerCategory.NOTIFICATIONS,
            "android.http.request" to FeaturePickerCategory.WEB_INTERACTIONS,
            "android.home.launch" to FeaturePickerCategory.APPLICATIONS,
            "android.sync.master.set" to FeaturePickerCategory.CONNECTIVITY,
        )
        val events = mapOf(
            "android.event.boot" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.airplane_mode_changed" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.clipboard_changed" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.user_present" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.location_mode_changed" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.sms_received" to FeaturePickerCategory.CALL_SMS,
            "android.event.phone_state_changed" to FeaturePickerCategory.CALL_SMS,
            "android.event.broadcast" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.nfc_tag" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.network_changed" to FeaturePickerCategory.CONNECTIVITY,
            "android.event.sensor_value" to FeaturePickerCategory.SENSORS,
            "android.event.shake" to FeaturePickerCategory.SENSORS,
            "android.event.wifi_changed" to FeaturePickerCategory.CONNECTIVITY,
        )
        val conditions = mapOf(
            "android.condition.airplane_mode" to FeaturePickerCategory.CONNECTIVITY,
            "android.condition.brightness" to FeaturePickerCategory.SCREEN,
            "time.condition.weekday" to FeaturePickerCategory.DATE_TIME,
            "android.condition.charging_source" to FeaturePickerCategory.BATTERY_POWER,
            "android.condition.phone_call_state" to FeaturePickerCategory.PHONE,
            "android.condition.notification_active" to FeaturePickerCategory.NOTIFICATIONS,
            "android.condition.audio.stream_volume" to FeaturePickerCategory.SCREEN,
            "android.condition.screen" to FeaturePickerCategory.SCREEN,
            "android.condition.audio.speakerphone" to FeaturePickerCategory.SCREEN,
            "android.condition.network_profile" to FeaturePickerCategory.CONNECTIVITY,
            "android.condition.wifi_network" to FeaturePickerCategory.CONNECTIVITY,
        )
        actions.forEach { (id, expected) -> assertEquals(id, expected, classify(id, FeatureKind.ACTION)) }
        events.forEach { (id, expected) -> assertEquals(id, expected, classify(id, FeatureKind.EVENT)) }
        conditions.forEach { (id, expected) -> assertEquals(id, expected, classify(id, FeatureKind.CONDITION)) }
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
            "android.event.location_mode_changed" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.time_tick" to FeaturePickerCategory.DATE_TIME,
            "android.event.location_changed" to FeaturePickerCategory.LOCATION,
            "android.event.media_track_changed" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.notification_posted" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.screen_on" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.sensor_value" to FeaturePickerCategory.SENSORS,
            "android.event.hardware_key" to FeaturePickerCategory.USER_INPUT,
            "android.event.volume_button_press" to FeaturePickerCategory.USER_INPUT,
            "android.event.notification_bar_button" to FeaturePickerCategory.USER_INPUT,
            "android.event.sim_card_changed" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.webhook" to FeaturePickerCategory.CONNECTIVITY,
            "android.event.http_server.request" to FeaturePickerCategory.CONNECTIVITY,
            "android.event.boot" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.systemui_app_ready" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.status_bar_icon_changed" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.clipboard_changed" to FeaturePickerCategory.DEVICE_EVENTS,
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
            "android.condition.network_roaming" to FeaturePickerCategory.CONNECTIVITY,
            "android.condition.clipboard_present" to FeaturePickerCategory.DEVICE_STATE,
            "android.condition.day_of_week" to FeaturePickerCategory.DATE_TIME,
            "time.condition.weekday" to FeaturePickerCategory.DATE_TIME,
            "android.condition.location_available" to FeaturePickerCategory.LOCATION,
            "android.condition.audio.music_active" to FeaturePickerCategory.MEDIA,
            "android.condition.notification_present" to FeaturePickerCategory.NOTIFICATIONS,
            "android.condition.phone_idle" to FeaturePickerCategory.PHONE,
            "android.condition.screen" to FeaturePickerCategory.SCREEN,
            "android.condition.sensor_value" to FeaturePickerCategory.SENSORS,
            "android.condition.physical_activity" to FeaturePickerCategory.SENSORS,
            "android.condition.media_volume" to FeaturePickerCategory.SCREEN,
            "android.condition.audio.speakerphone" to FeaturePickerCategory.SCREEN,
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

    @Test
    fun `source audited concrete features override ambiguous category keywords`() {
        val actionCases = mapOf(
            "android.home.launch" to FeaturePickerCategory.APPLICATIONS,
            "android.media.latest.open" to FeaturePickerCategory.CAMERA_PHOTO,
            "android.contact_via_app.send" to FeaturePickerCategory.MESSAGING,
            "android.keyguard.set" to FeaturePickerCategory.SCREEN,
            "android.chart.create" to FeaturePickerCategory.FILES,
            "android.mode.set" to FeaturePickerCategory.YAUTO_SPECIFIC,
            "android.telephony.info" to FeaturePickerCategory.CONNECTIVITY,
            "android.audio.sound_level.measure" to FeaturePickerCategory.MEDIA,
            "android.plugin.locale.action" to FeaturePickerCategory.APPLICATIONS,
            "android.plugin.locale.scan" to FeaturePickerCategory.APPLICATIONS,
            "android.calendar.events.query" to FeaturePickerCategory.LOGGING,
            "android.calendar.event.insert" to FeaturePickerCategory.LOGGING,
            "android.yauto.setting.set" to FeaturePickerCategory.YAUTO_SPECIFIC,
            "android.sensors_off.set" to FeaturePickerCategory.DEVICE_SETTINGS,
        )
        actionCases.forEach { (id, expected) ->
            assertEquals(id, expected, classify(id, FeatureKind.ACTION))
        }
        val eventCases = mapOf(
            "android.event.sleep_transition" to FeaturePickerCategory.SENSORS,
            "android.event.sleep_classification" to FeaturePickerCategory.SENSORS,
            "android.event.assistant_activated" to FeaturePickerCategory.USER_INPUT,
            "android.event.accessibility_state_changed" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.sim_subscription_changed" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.email_received" to FeaturePickerCategory.APPLICATIONS,
            "android.event.window_focus_changed" to FeaturePickerCategory.APPLICATIONS,
            "android.event.sound_level" to FeaturePickerCategory.SENSORS,
            "android.event.cellular_service_changed" to FeaturePickerCategory.CONNECTIVITY,
            "android.event.input_filter_state_changed" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.accessibility_windows_queried" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.window_added" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.activity_manager_started" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.activity_manager_shell_command" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.window_manager_ready" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.task_cleanup" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.process_uncaught_exception" to FeaturePickerCategory.DEVICE_EVENTS,
        )
        eventCases.forEach { (id, expected) ->
            assertEquals(id, expected, classify(id, FeatureKind.EVENT))
        }
        assertEquals(
            FeaturePickerCategory.CONNECTIVITY,
            classify("android.state.cellular_service_available", FeatureKind.STATE),
        )
        assertEquals(
            FeaturePickerCategory.CONNECTIVITY,
            classify("android.condition.cellular_service_available", FeatureKind.CONDITION),
        )
        assertEquals(FeaturePickerCategory.SENSORS, classify("android.state.sleeping", FeatureKind.STATE))
        assertEquals(FeaturePickerCategory.SENSORS, classify("android.condition.sleeping", FeatureKind.CONDITION))
    }

    @Test
    fun `MacroDroid category order is specific to triggers actions and constraints`() {
        val actions = macroDroidCategoriesForKind(FeatureKind.ACTION)
            .sortedBy { macroDroidCategoryOrder(FeatureKind.ACTION, it) }
        assertEquals(FeaturePickerCategory.APPLICATIONS, actions.first())
        assertEquals(FeaturePickerCategory.AI, actions.last())
        assertEquals(FeaturePickerCategory.FILES, actions[2])
        assertEquals(FeaturePickerCategory.MACROS, actions[3])
        assertEquals(FeaturePickerCategory.VARIABLES, actions[17])
        val events = macroDroidCategoriesForKind(FeatureKind.EVENT)
            .sortedBy { macroDroidCategoryOrder(FeatureKind.EVENT, it) }
        assertEquals(FeaturePickerCategory.APPLICATIONS, events.first())
        assertEquals(FeaturePickerCategory.SENSORS, events[1])
        assertEquals(FeaturePickerCategory.DEVICE_EVENTS, events[5])
        val conditions = macroDroidCategoriesForKind(FeatureKind.CONDITION)
            .sortedBy { macroDroidCategoryOrder(FeatureKind.CONDITION, it) }
        assertEquals(FeaturePickerCategory.SENSORS, conditions.first())
        assertEquals(FeaturePickerCategory.SCREEN, conditions[4])
        assertEquals(conditions, macroDroidCategoriesForKind(FeatureKind.STATE)
            .sortedBy { macroDroidCategoryOrder(FeatureKind.STATE, it) })
        FeatureKind.entries.forEach { kind ->
            val ranks = macroDroidCategoriesForKind(kind).map { macroDroidCategoryOrder(kind, it) }
            assertEquals(ranks.size, ranks.toSet().size)
            assertTrue(ranks.all { it < 10_000 })
        }
    }

    @Test
    fun `every MacroDroid picker kind has a separate allowed set`() {
        val trigger = macroDroidCategoriesForKind(FeatureKind.EVENT)
        val action = macroDroidCategoriesForKind(FeatureKind.ACTION)
        val condition = macroDroidCategoriesForKind(FeatureKind.CONDITION)
        assertEquals(10, trigger.size)
        assertEquals(21, action.size) // 20 reference categories + YAuto AI extension
        assertEquals(11, condition.size)
        assertEquals(condition, macroDroidCategoriesForKind(FeatureKind.STATE))
        assertTrue(FeaturePickerCategory.MACROS in action)
        assertTrue(FeaturePickerCategory.VARIABLES in action)
        assertTrue(FeaturePickerCategory.MACROS !in trigger)
        assertTrue(FeaturePickerCategory.CONDITIONS_LOOPS !in condition)
        FeatureKind.entries.forEach { kind ->
            FeaturePickerCategory.entries.forEach { category ->
                assertTrue(
                    "invalid remap for $kind / $category",
                    normalizeMacroDroidPickerCategory(kind, category) in macroDroidCategoriesForKind(kind),
                )
            }
        }
    }

    @Test
    fun `triggers are classified by event not by similarly named action`() {
        val examples = mapOf(
            "android.event.screen_off" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.clipboard_changed" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.notification_posted" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.systemui_app_ready" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.airplane_mode_changed" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.nfc_tag" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.wifi_changed" to FeaturePickerCategory.CONNECTIVITY,
            "android.event.hardware_key" to FeaturePickerCategory.USER_INPUT,
            "android.event.battery_low" to FeaturePickerCategory.BATTERY_POWER,
            "android.event.shake" to FeaturePickerCategory.SENSORS,
        )
        examples.forEach { (id, expected) -> assertEquals(id, expected, classify(id, FeatureKind.EVENT)) }
    }

    @Test
    fun `constraint taxonomy has device state and screen speaker without action categories`() {
        assertEquals(FeaturePickerCategory.DEVICE_STATE, classify("android.condition.app_installed", FeatureKind.CONDITION))
        assertEquals(FeaturePickerCategory.SCREEN, classify("android.state.audio.ringer_mode", FeatureKind.STATE))
        assertEquals(FeaturePickerCategory.CONNECTIVITY, classify("android.condition.websocket_connected", FeatureKind.CONDITION))
        assertEquals(FeaturePickerCategory.YAUTO_SPECIFIC, classify("variable.condition.equals", FeatureKind.CONDITION))
    }

    @Test
    fun `descriptor exposes picker category independently from implementation category and domain`() {
        val descriptor = FeatureDescriptor(
            id = FeatureId("android.audio.volume.set"),
            kind = FeatureKind.ACTION,
            title = "Set volume",
            description = "Set volume",
            category = FeatureCategory.AUDIO,
        )

        assertEquals(FeaturePickerCategory.VOLUME, descriptor.pickerCategory)
        assertEquals(FeatureCategory.AUDIO, descriptor.category)
        assertEquals(FeatureDomain.AUDIO_MEDIA, descriptor.domain)
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
