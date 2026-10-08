package com.yagay.yauto.core.registry

import java.util.Locale

/**
 * User-facing picker categories modelled after MacroDroid's category taxonomy.
 *
 * This is intentionally independent from [FeatureCategory], which remains an implementation
 * bucket used by feature packs. Picker categories describe where a user expects to find a feature.
 */
enum class FeaturePickerCategory {
    AI,
    APPLICATIONS,
    BATTERY_POWER,
    CALL_SMS,
    CAMERA_PHOTO,
    CONNECTIVITY,
    DATE_TIME,
    DEVICE_ACTIONS,
    DEVICE_EVENTS,
    DEVICE_SETTINGS,
    DEVICE_STATE,
    FILES,
    LOCATION,
    LOGGING,
    MACROS,
    CONDITIONS_LOOPS,
    YAUTO_SPECIFIC,
    MEDIA,
    MESSAGING,
    NOTIFICATIONS,
    PHONE,
    SCREEN,
    SENSORS,
    USER_INPUT,
    VARIABLES,
    VOLUME,
    WEB_INTERACTIONS,
}

/**
 * Compatibility classifier for the complete existing catalog.
 *
 * The rules are deliberately kind-aware because MacroDroid separates device actions, events and
 * state, and also treats Call/SMS triggers differently from Messaging/Phone actions.
 * Ambiguous future features may override [FeatureDescriptor.pickerCategory] explicitly.
 */
fun inferFeaturePickerCategory(
    id: String,
    kind: FeatureKind,
    legacyCategory: FeatureCategory,
): FeaturePickerCategory {
    val key = id.lowercase(Locale.ROOT)
    // Apply verified concrete-feature corrections before broad compatibility keywords.
    // This prevents a media/photo action or a system event from being misread as
    // a playback action or a user-input trigger purely because of its ID.
    explicitMacroDroidFeatureCategory(key, kind)?.let { return it }
    fun has(vararg tokens: String): Boolean = tokens.any(key::contains)
    fun starts(vararg prefixes: String): Boolean = prefixes.any(key::startsWith)

    val inferred = when {
        starts("ai.") || has(".ai.") ->
            if (kind == FeatureKind.ACTION) FeaturePickerCategory.AI
            else FeaturePickerCategory.YAUTO_SPECIFIC

        // MacroDroid action picker separates macro management, variable operations and
        // control-flow blocks. The saved runtime IDs remain unchanged.
        kind == FeatureKind.ACTION && (
            starts("automation.", "macro.") ||
                has(".automation.", ".macro.", ".action_block.", ".macro_run", "run_macro") ||
                key in setOf("core.category.set_enabled", "core.trigger.set_enabled", "core.runtime.set_enabled")
            ) -> FeaturePickerCategory.MACROS

        // MacroDroid's Wait Before Next Action belongs to MacroDroid Specific, not Macros.
        kind == FeatureKind.ACTION && key in setOf("core.delay", "flow.delay", "flow.wait") ->
            FeaturePickerCategory.YAUTO_SPECIFIC

        kind == FeatureKind.ACTION && (
            starts("variable.", "persistent.", "collection.", "data.list.", "data.object.") ||
                has(".variable.", ".variables.", ".array.", ".dictionary.")
            ) -> FeaturePickerCategory.VARIABLES

        // MacroDroid's Web Interactions includes JSON parsing and UDP commands.
        kind == FeatureKind.ACTION && (
            starts("json.") || has(".json.", ".udp.send", ".tcp.send", ".tcp.wait")
            ) -> FeaturePickerCategory.WEB_INTERACTIONS

        // Root-level time features and log writes otherwise fall through the legacy bucket.
        starts("time.") -> FeaturePickerCategory.DATE_TIME
        kind == FeatureKind.ACTION && key == "core.log" -> FeaturePickerCategory.LOGGING

        // Precise calendar operations are pinned by the semantic override table below.

        // Script/Tasker plugin actions are in MacroDroid's Applications category.
        kind == FeatureKind.ACTION && (
            starts("script.", "tasker.plugin.") ||
                has(".javascript.", ".beanshell.", ".mvel.", ".shell.execute", ".shell.exec")
            ) -> FeaturePickerCategory.APPLICATIONS

        // Physical button / notification-bar interactions are user-input triggers,
        // even when their name also contains media volume or notifications.
        kind == FeatureKind.EVENT && has(
            "volume_button", "power_button", "notification_bar_button",
            "notification_button", "widget_button", "floating_button",
        ) -> FeaturePickerCategory.USER_INPUT

        // SIM-card changes are device events, unlike incoming calls and SMS messages.
        kind == FeatureKind.EVENT && has(".sim_card", ".sim_state") ->
            FeaturePickerCategory.DEVICE_EVENTS

        // Screen-awake controls belong with screen actions, not device reboot commands.
        kind == FeatureKind.ACTION && has(".power.stay_awake", ".screen.keep_awake") ->
            FeaturePickerCategory.SCREEN

        // MacroDroid exposes web/network requests separately from connectivity controls.
        has(
            ".http", ".webhook", ".websocket", ".webdav", ".url.",
            "http_server", "rest_api", ".mqtt",
        ) -> when (kind) {
            FeatureKind.STATE, FeatureKind.CONDITION -> FeaturePickerCategory.CONNECTIVITY
            FeatureKind.ACTION -> FeaturePickerCategory.WEB_INTERACTIONS
            FeatureKind.EVENT -> FeaturePickerCategory.CONNECTIVITY
        }

        // Logging is a first-class MacroDroid category rather than a script/command subtype.
        has(
            ".logcat", ".dumpsys", ".log.write", ".log.export", ".system_log",
            ".execution_log", ".diagnostic", ".trace.", ".logger",
        ) -> when (kind) {
            FeatureKind.ACTION -> FeaturePickerCategory.LOGGING
            FeatureKind.EVENT -> FeaturePickerCategory.DEVICE_EVENTS
            FeatureKind.STATE, FeatureKind.CONDITION -> FeaturePickerCategory.DEVICE_STATE
        }

        // Only branching/loops belong here; generic waits and macro control do not.
        kind == FeatureKind.ACTION && (
            starts("flow.") && !starts("flow.delay", "flow.wait") ||
                has(
                    ".flow.", ".loop", ".branch", ".parallel",
                    "try_catch", ".if.", ".else.", ".break_loop", ".continue_loop",
                )
            ) ->
            FeaturePickerCategory.CONDITIONS_LOOPS

        // Camera/photo includes capture and image-processing operations.
        has(
            ".camera", ".photo", "screen_record", "screenshot", ".ocr", ".qr",
            "image_match", ".image.", ".capture", ".torch", ".flashlight",
        ) -> when (kind) {
            FeatureKind.ACTION -> FeaturePickerCategory.CAMERA_PHOTO
            FeatureKind.EVENT -> FeaturePickerCategory.DEVICE_EVENTS
            FeatureKind.STATE, FeatureKind.CONDITION -> FeaturePickerCategory.DEVICE_STATE
        }

        has(
            ".location", ".geofence", ".gps", ".maps.", ".cell_tower",
            "latitude", "longitude", ".weather",
        ) ->
            FeaturePickerCategory.LOCATION

        has(
            ".sensor", "activity_recognition", "physical_activity", ".shake", ".pedometer", ".proximity",
            ".light_level", ".motion_detected",
            ".accelerometer", ".gyroscope", "light_sensor", "significant_motion",
            "sound_level",
        ) ->
            if (kind == FeatureKind.ACTION) FeaturePickerCategory.DEVICE_ACTIONS
            else FeaturePickerCategory.SENSORS

        // NFC tags, airplane-mode and auto-sync changes are device events in MacroDroid.
        kind == FeatureKind.EVENT && has(
            ".nfc", ".airplane", "account_sync", ".sync.account",
        ) -> FeaturePickerCategory.DEVICE_EVENTS

        has(
            ".wifi", ".bluetooth", ".ble", ".mobile_data", ".airplane", ".hotspot",
            ".tether", ".nfc", ".usb", ".connectivity", ".network_profile",
            ".data_usage", ".matter", ".wear", ".wireguard", ".vpn", ".network", ".cellular", ".private_dns",
            ".data_saver", ".ethernet", ".internet", "account_sync", ".sync.account",
        ) ->
            FeaturePickerCategory.CONNECTIVITY

        // Clipboard is a device capability, not a variable/script feature. Keep actions and
        // input events separate so identical names do not imply identical semantics.
        has(".clipboard") -> when (kind) {
            FeatureKind.ACTION -> FeaturePickerCategory.DEVICE_ACTIONS
            FeatureKind.EVENT -> FeaturePickerCategory.DEVICE_EVENTS
            FeatureKind.STATE, FeatureKind.CONDITION -> FeaturePickerCategory.DEVICE_STATE
        }

        has(".notification", ".toast", "heads_up") ->
            FeaturePickerCategory.NOTIFICATIONS

        // MacroDroid keeps volume/ringer/DND separate from general media/audio.
        has(
            ".volume", "_volume", "volume_", "ringer_mode", ".ringer.", "dnd_filter", ".dnd",
            "do_not_disturb",
        ) ->
            FeaturePickerCategory.VOLUME

        has(
            ".audio", ".media", ".playback", ".microphone", ".speakerphone",
            ".speech", ".tts", ".midi", ".headset", ".headphone", "audio_focus",
            ".sound.",
        ) ->
            FeaturePickerCategory.MEDIA

        // Screenshot/screen-record already matched Camera/Photo above.
        has(
            ".display", ".brightness", ".screen", ".rotation", ".orientation",
            ".dpi", ".resolution", ".dark_mode", ".font_scale", ".immersive",
            "ambient_display", ".dream",
        ) ->
            FeaturePickerCategory.SCREEN

        has(
            ".battery", ".charging", "power_save", ".wake_lock", ".wakelock",
            ".doze", "device_idle", "low_power", "power_connected",
            "power_disconnected", "battery_optimization",
        ) -> when (kind) {
            FeatureKind.ACTION ->
                if (has("power_save", "battery_optimization", "stay_awake_while_charging"))
                    FeaturePickerCategory.DEVICE_SETTINGS
                else FeaturePickerCategory.DEVICE_ACTIONS
            FeatureKind.EVENT, FeatureKind.STATE, FeatureKind.CONDITION ->
                FeaturePickerCategory.BATTERY_POWER
        }

        has(
            ".time", ".date", ".interval", ".alarm", ".timer", ".calendar",
            ".timezone", ".solar", ".stopwatch", "next_alarm", "day_of_",
            "month_of_", "week_of_", "hour_of_", "daylight_saving", ".weekend",
        ) ->
            FeaturePickerCategory.DATE_TIME

        starts("file.", "directory.", "archive.", "storage.") || has(
            ".file", ".directory", ".archive", ".zip", ".storage", ".download",
            "media_store", "document_tree",
        ) -> when (kind) {
            FeatureKind.ACTION -> FeaturePickerCategory.FILES
            FeatureKind.EVENT -> FeaturePickerCategory.DEVICE_EVENTS
            FeatureKind.STATE, FeatureKind.CONDITION -> FeaturePickerCategory.DEVICE_STATE
        }

        // SystemUI lifecycle notifications are device events, not user gestures.
        kind == FeatureKind.EVENT && starts(
            "android.event.systemui_", "android.event.status_bar_",
            "android.event.service_screen_state_",
        ) && !has("_clicked", "_pressed") ->
            FeaturePickerCategory.DEVICE_EVENTS

        has(
            "hardware_key", ".keyevent", ".keyboard", ".gesture", ".accessibility",
            ".ui.", ".overlay", ".surface", ".tap", ".click", ".swipe", ".input",
            ".biometric", ".qs_tile", ".quick_settings", ".menu_action",
            ".back_navigation", ".ime", ".window_", "systemui_", "status_bar_",
        ) -> when (kind) {
            FeatureKind.ACTION -> FeaturePickerCategory.DEVICE_ACTIONS
            FeatureKind.EVENT -> FeaturePickerCategory.USER_INPUT
            FeatureKind.STATE, FeatureKind.CONDITION -> FeaturePickerCategory.DEVICE_STATE
        }

        // MacroDroid uses Call/SMS chiefly for trigger/state style communication.
        kind != FeatureKind.ACTION && has(
            ".call", ".sms", "phone_state", "call_log", "incoming_call",
            "outgoing_call",
        ) ->
            if (kind == FeatureKind.EVENT) FeaturePickerCategory.CALL_SMS
            else FeaturePickerCategory.PHONE

        has(
            ".sms", ".email", ".message", ".messaging", ".share.text",
            "compose_sms", "compose_email",
        ) ->
            if (kind == FeatureKind.ACTION) FeaturePickerCategory.MESSAGING
            else FeaturePickerCategory.CALL_SMS

        has(
            ".phone", ".call", "call_log", ".contact", ".telephony", ".dial",
            ".sim_", ".voicemail",
        ) ->
            FeaturePickerCategory.PHONE

        has(
            ".app", ".package", ".activity", ".component", ".foreground",
            ".shortcut", ".launcher", ".widget", ".work_profile", ".role.",
            ".process", ".service", ".task", ".intent", ".apk.", ".install",
            ".uninstall",
        ) -> when (kind) {
            FeatureKind.ACTION, FeatureKind.EVENT -> FeaturePickerCategory.APPLICATIONS
            FeatureKind.STATE, FeatureKind.CONDITION -> FeaturePickerCategory.DEVICE_STATE
        }

        has(".wallpaper") -> when (kind) {
            FeatureKind.ACTION -> FeaturePickerCategory.DEVICE_SETTINGS
            FeatureKind.EVENT, FeatureKind.STATE, FeatureKind.CONDITION -> FeaturePickerCategory.SCREEN
        }

        // System/settings actions belong in Device Settings only after semantic categories above.
        kind == FeatureKind.ACTION && has(
            ".settings", "developer_options", "adb_enabled", "animation_scale",
            "stay_awake_while_charging", "unknown_sources", "install_unknown",
            ".setting.", "car_mode",
        ) ->
            FeaturePickerCategory.DEVICE_SETTINGS

        // Data/variables/scripts are closest to MacroDroid Specific rather than inventing
        // YAuto-only top-level categories that fragment the picker.
        starts(
            "data.", "variable.", "collection.", "expression.", "json.",
            "automation.", "core.", "tasker.",
        ) || has(
            ".variable", ".json", ".regex", ".hash", ".encode", ".decode",
            ".math", ".random", ".list", ".object", ".text.", ".clipboard",
            ".chart", ".javascript", ".mvel", ".beanshell", ".workspace",
            ".automation", ".macro", ".yauto", ".plugin",
        ) ->
            FeaturePickerCategory.YAUTO_SPECIFIC

        // Explicit device operations that otherwise contain generic words such as "power".
        kind == FeatureKind.ACTION && has(
            ".reboot", ".shutdown", "power_menu", ".shell", ".command", ".exec",
            ".vibrate", ".vibration", ".lock_device", ".wake", ".sleep",
        ) ->
            FeaturePickerCategory.DEVICE_ACTIONS

        else -> fallbackPickerCategory(kind, legacyCategory)
    }
    return normalizeMacroDroidPickerCategory(kind, inferred)
}

/**
 * Source-audited exceptional IDs. Broad substring matching is not enough for these
 * features; preserve this table when changing the overall category classifier.
 */
private fun explicitMacroDroidFeatureCategory(
    key: String,
    kind: FeatureKind,
): FeaturePickerCategory? = when (kind) {
    FeatureKind.ACTION -> when (key) {
        "android.home.launch" -> FeaturePickerCategory.APPLICATIONS
        "android.media.latest.open" -> FeaturePickerCategory.CAMERA_PHOTO
        "android.contact_via_app.send" -> FeaturePickerCategory.MESSAGING
        "android.keyguard.set" -> FeaturePickerCategory.SCREEN
        // Screenshot/PNG chart generation writes a file; YAuto settings are not Android settings.
        "android.chart.create" -> FeaturePickerCategory.FILES
        "android.yauto.setting.set" -> FeaturePickerCategory.YAUTO_SPECIFIC
        "android.sensors_off.set" -> FeaturePickerCategory.DEVICE_SETTINGS
        "android.keyguard.pin_unlock" -> FeaturePickerCategory.SCREEN
        "android.systemui.demo" -> FeaturePickerCategory.DEVICE_SETTINGS
        "android.mode.set" -> FeaturePickerCategory.YAUTO_SPECIFIC
        "android.telephony.info", "android.sync.master.set" -> FeaturePickerCategory.CONNECTIVITY
        "android.audio.sound_level.measure" -> FeaturePickerCategory.MEDIA
        "android.calendar.events.query", "android.calendar.event.insert",
        "android.calendar.event.update", "android.calendar.event.delete" ->
            FeaturePickerCategory.LOGGING
        else -> if (key.startsWith("android.plugin.locale.")) {
            FeaturePickerCategory.APPLICATIONS
        } else null
    }
    FeatureKind.EVENT -> when (key) {
        // These ShortX timers describe a schedule, not a generic Android device event.
        "android.event.fixed_in_period", "android.event.random_in_period" ->
            FeaturePickerCategory.DATE_TIME
        // MacroDroid's Tasker/Locale plugin trigger belongs to Applications.
        "android.event.plugin_locale" -> FeaturePickerCategory.APPLICATIONS
        // A tap on a rich notification action is a user-initiated button trigger.
        "android.event.ppn_action" -> FeaturePickerCategory.USER_INPUT
        "android.event.user_foreground", "android.event.user_background" ->
            FeaturePickerCategory.DEVICE_EVENTS
        "android.event.location_mode_changed" -> FeaturePickerCategory.DEVICE_EVENTS
        // Android service starts/commands are system lifecycle events, not app launches.
        "android.event.activity_manager_started",
        "android.event.activity_manager_ready",
        "android.event.activity_manager_shell_command",
        "android.event.window_manager_ready",
        "android.event.task_cleanup",
        "android.event.process_uncaught_exception",
        "android.event.rendernode_crash_suppressed" -> FeaturePickerCategory.DEVICE_EVENTS
        // System-server/LSPosed observers are lifecycle events, not physical user input.
        "android.event.input_filter_state_changed",
        "android.event.input_manager_started",
        "android.event.accessibility_user_state_created",
        "android.event.accessibility_display_list_queried",
        "android.event.accessibility_windows_queried",
        "android.event.accessibility_ui_automation_checked",
        "android.event.window_added" -> FeaturePickerCategory.DEVICE_EVENTS
        "android.event.sound_level" -> FeaturePickerCategory.SENSORS
        "android.event.sleep_transition", "android.event.sleep_classification" ->
            FeaturePickerCategory.SENSORS
        "android.event.assistant_activated" -> FeaturePickerCategory.USER_INPUT
        "android.event.accessibility_state_changed",
        "android.event.sim_subscription_changed",
        "android.event.sms_provider_ready", "android.event.sms_provider_changed" ->
            FeaturePickerCategory.DEVICE_EVENTS
        "android.event.email_received", "android.event.window_focus_changed" ->
            FeaturePickerCategory.APPLICATIONS
        else -> null
    }
    FeatureKind.STATE, FeatureKind.CONDITION -> when (key) {
        // Telephony operator identity is a cellular network attribute, not an app/process state.
        "android.state.reference.mobile_country_code",
        "android.condition.reference.mobile_country_code",
        "android.state.reference.mobile_network_code",
        "android.condition.reference.mobile_network_code" ->
            FeaturePickerCategory.CONNECTIVITY
        "android.state.audio.speakerphone", "android.condition.audio.speakerphone",
        "android.state.speakerphone", "android.condition.speakerphone" ->
            FeaturePickerCategory.SCREEN
        "android.state.sleeping", "android.condition.sleeping",
        "android.state.physical_activity", "android.condition.physical_activity" ->
            FeaturePickerCategory.SENSORS
        else -> null
    }
}

/** The category order on MacroDroid's three pickers is not alphabetical and differs by kind. */
private val triggerPickerOrder = listOf(
    FeaturePickerCategory.APPLICATIONS,
    FeaturePickerCategory.BATTERY_POWER,
    FeaturePickerCategory.CALL_SMS,
    FeaturePickerCategory.CONNECTIVITY,
    FeaturePickerCategory.DATE_TIME,
    FeaturePickerCategory.DEVICE_EVENTS,
    FeaturePickerCategory.LOCATION,
    FeaturePickerCategory.YAUTO_SPECIFIC,
    FeaturePickerCategory.SENSORS,
    FeaturePickerCategory.USER_INPUT,
)

private val actionPickerOrder = listOf(
    FeaturePickerCategory.APPLICATIONS,
    FeaturePickerCategory.CAMERA_PHOTO,
    FeaturePickerCategory.CONNECTIVITY,
    FeaturePickerCategory.CONDITIONS_LOOPS,
    FeaturePickerCategory.DATE_TIME,
    FeaturePickerCategory.DEVICE_ACTIONS,
    FeaturePickerCategory.DEVICE_SETTINGS,
    FeaturePickerCategory.FILES,
    FeaturePickerCategory.LOCATION,
    FeaturePickerCategory.LOGGING,
    FeaturePickerCategory.YAUTO_SPECIFIC,
    FeaturePickerCategory.MACROS,
    FeaturePickerCategory.MEDIA,
    FeaturePickerCategory.MESSAGING,
    FeaturePickerCategory.NOTIFICATIONS,
    FeaturePickerCategory.PHONE,
    FeaturePickerCategory.SCREEN,
    FeaturePickerCategory.VARIABLES,
    FeaturePickerCategory.VOLUME,
    FeaturePickerCategory.WEB_INTERACTIONS,
    FeaturePickerCategory.AI,
)

private val constraintPickerOrder = listOf(
    FeaturePickerCategory.BATTERY_POWER,
    FeaturePickerCategory.CONNECTIVITY,
    FeaturePickerCategory.DATE_TIME,
    FeaturePickerCategory.DEVICE_STATE,
    FeaturePickerCategory.LOCATION,
    FeaturePickerCategory.YAUTO_SPECIFIC,
    FeaturePickerCategory.MEDIA,
    FeaturePickerCategory.NOTIFICATIONS,
    FeaturePickerCategory.PHONE,
    FeaturePickerCategory.SCREEN,
    FeaturePickerCategory.SENSORS,
)

/** Stable UI order. Unknown/invalid combinations are placed after the supported categories. */
fun macroDroidCategoryOrder(kind: FeatureKind, category: FeaturePickerCategory): Int {
    val categories = when (kind) {
        FeatureKind.EVENT -> triggerPickerOrder
        FeatureKind.ACTION -> actionPickerOrder
        FeatureKind.STATE, FeatureKind.CONDITION -> constraintPickerOrder
    }
    val index = categories.indexOf(category)
    return if (index < 0) 10_000 else (index + 1) * 10
}

/**
 * MacroDroid maintains distinct category menus for triggers, actions and constraints.
 * YAuto-specific capabilities use YAUTO_SPECIFIC without creating misleading empty tabs.
 * STATE follows the constraint taxonomy because both inspect the current device state.
 */
fun macroDroidCategoriesForKind(kind: FeatureKind): Set<FeaturePickerCategory> = when (kind) {
    FeatureKind.EVENT -> triggerPickerOrder.toSet()
    FeatureKind.ACTION -> actionPickerOrder.toSet()
    FeatureKind.STATE, FeatureKind.CONDITION -> constraintPickerOrder.toSet()
}

/** Constrain legacy and explicit categories to the matching MacroDroid-style picker menu. */
fun normalizeMacroDroidPickerCategory(
    kind: FeatureKind,
    category: FeaturePickerCategory,
): FeaturePickerCategory {
    if (category in macroDroidCategoriesForKind(kind)) return category
    return when (kind) {
        FeatureKind.EVENT -> when (category) {
            FeaturePickerCategory.PHONE, FeaturePickerCategory.MESSAGING -> FeaturePickerCategory.CALL_SMS
            FeaturePickerCategory.AI, FeaturePickerCategory.VARIABLES,
            FeaturePickerCategory.MACROS, FeaturePickerCategory.CONDITIONS_LOOPS ->
                FeaturePickerCategory.YAUTO_SPECIFIC
            else -> FeaturePickerCategory.DEVICE_EVENTS
        }
        FeatureKind.ACTION -> when (category) {
            FeaturePickerCategory.BATTERY_POWER, FeaturePickerCategory.DEVICE_STATE ->
                FeaturePickerCategory.DEVICE_SETTINGS
            FeaturePickerCategory.CALL_SMS -> FeaturePickerCategory.MESSAGING
            else -> FeaturePickerCategory.DEVICE_ACTIONS
        }
        FeatureKind.STATE, FeatureKind.CONDITION -> when (category) {
            FeaturePickerCategory.VOLUME -> FeaturePickerCategory.SCREEN
            FeaturePickerCategory.CALL_SMS, FeaturePickerCategory.MESSAGING -> FeaturePickerCategory.PHONE
            FeaturePickerCategory.WEB_INTERACTIONS -> FeaturePickerCategory.CONNECTIVITY
            FeaturePickerCategory.AI, FeaturePickerCategory.VARIABLES,
            FeaturePickerCategory.MACROS, FeaturePickerCategory.CONDITIONS_LOOPS ->
                FeaturePickerCategory.YAUTO_SPECIFIC
            else -> FeaturePickerCategory.DEVICE_STATE
        }
    }
}

private fun fallbackPickerCategory(
    kind: FeatureKind,
    legacyCategory: FeatureCategory,
): FeaturePickerCategory = when (legacyCategory) {
    FeatureCategory.APP -> FeaturePickerCategory.APPLICATIONS
    FeatureCategory.NETWORK -> FeaturePickerCategory.CONNECTIVITY
    FeatureCategory.DISPLAY -> FeaturePickerCategory.SCREEN
    FeatureCategory.AUDIO -> FeaturePickerCategory.MEDIA
    FeatureCategory.NOTIFICATION -> FeaturePickerCategory.NOTIFICATIONS
    FeatureCategory.FILE -> FeaturePickerCategory.FILES
    FeatureCategory.VARIABLE -> if (kind == FeatureKind.ACTION) FeaturePickerCategory.VARIABLES
        else FeaturePickerCategory.YAUTO_SPECIFIC
    FeatureCategory.FLOW ->
        if (kind == FeatureKind.ACTION) FeaturePickerCategory.CONDITIONS_LOOPS
        else FeaturePickerCategory.YAUTO_SPECIFIC
    FeatureCategory.UI_AUTOMATION -> when (kind) {
        FeatureKind.ACTION -> FeaturePickerCategory.DEVICE_ACTIONS
        FeatureKind.EVENT -> FeaturePickerCategory.USER_INPUT
        FeatureKind.STATE, FeatureKind.CONDITION -> FeaturePickerCategory.DEVICE_STATE
    }
    FeatureCategory.SCRIPT -> FeaturePickerCategory.YAUTO_SPECIFIC
    FeatureCategory.CORE, FeatureCategory.COMPATIBILITY -> FeaturePickerCategory.YAUTO_SPECIFIC
    FeatureCategory.DEVICE,
    FeatureCategory.SYSTEM,
    FeatureCategory.ADVANCED -> when (kind) {
        FeatureKind.ACTION -> FeaturePickerCategory.DEVICE_ACTIONS
        FeatureKind.EVENT -> FeaturePickerCategory.DEVICE_EVENTS
        FeatureKind.STATE, FeatureKind.CONDITION -> FeaturePickerCategory.DEVICE_STATE
    }
}
