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
                has(".macro.", ".action_block.", ".macro_run", "run_macro") ||
                key in setOf("core.delay", "flow.delay", "flow.wait")
            ) -> FeaturePickerCategory.MACROS

        kind == FeatureKind.ACTION && (
            starts("variable.", "persistent.", "collection.") ||
                has(".variable.", ".variables.", ".array.", ".dictionary.")
            ) -> FeaturePickerCategory.VARIABLES

        // MacroDroid's Web Interactions includes JSON parsing and UDP commands.
        kind == FeatureKind.ACTION && (
            starts("json.") || has(".json.", ".udp.send", ".tcp.send")
            ) -> FeaturePickerCategory.WEB_INTERACTIONS

        // Calendar writes are filed with Logging/Calendar, not time triggers.
        kind == FeatureKind.ACTION && (
            has(".calendar.event.insert", ".calendar.event.update", ".calendar.event.delete")
            ) -> FeaturePickerCategory.LOGGING

        // Script/Tasker plugin actions are in MacroDroid's Applications category.
        kind == FeatureKind.ACTION && (
            starts("script.", "tasker.plugin.") ||
                has(".javascript.", ".beanshell.", ".mvel.", ".shell.execute")
            ) -> FeaturePickerCategory.APPLICATIONS

        // MacroDroid exposes web/network requests separately from connectivity controls.
        has(
            ".http", ".webhook", ".websocket", ".webdav", ".url.",
            "http_server", "rest_api", ".mqtt",
        ) -> when (kind) {
            FeatureKind.STATE, FeatureKind.CONDITION -> FeaturePickerCategory.CONNECTIVITY
            FeatureKind.ACTION, FeatureKind.EVENT -> FeaturePickerCategory.WEB_INTERACTIONS
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
            ".sensor", "activity_recognition", ".pedometer", ".proximity",
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
            ".data_usage", ".matter", ".wear", ".wireguard", ".vpn", ".network", ".private_dns",
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
            ".volume", "ringer_mode", ".ringer.", "dnd_filter", ".dnd",
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

        has(
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
 * MacroDroid maintains distinct category menus for triggers, actions and constraints.
 * YAuto-specific capabilities use YAUTO_SPECIFIC without creating misleading empty tabs.
 * STATE follows the constraint taxonomy because both inspect the current device state.
 */
fun macroDroidCategoriesForKind(kind: FeatureKind): Set<FeaturePickerCategory> = when (kind) {
    FeatureKind.EVENT -> setOf(
        FeaturePickerCategory.APPLICATIONS, FeaturePickerCategory.SENSORS,
        FeaturePickerCategory.BATTERY_POWER, FeaturePickerCategory.USER_INPUT,
        FeaturePickerCategory.LOCATION, FeaturePickerCategory.DEVICE_EVENTS,
        FeaturePickerCategory.CONNECTIVITY, FeaturePickerCategory.CALL_SMS,
        FeaturePickerCategory.DATE_TIME, FeaturePickerCategory.YAUTO_SPECIFIC,
    )
    FeatureKind.ACTION -> setOf(
        FeaturePickerCategory.AI, FeaturePickerCategory.APPLICATIONS,
        FeaturePickerCategory.CAMERA_PHOTO, FeaturePickerCategory.CONNECTIVITY,
        FeaturePickerCategory.DATE_TIME, FeaturePickerCategory.DEVICE_ACTIONS,
        FeaturePickerCategory.DEVICE_SETTINGS, FeaturePickerCategory.FILES,
        FeaturePickerCategory.LOCATION, FeaturePickerCategory.LOGGING,
        FeaturePickerCategory.MACROS, FeaturePickerCategory.CONDITIONS_LOOPS,
        FeaturePickerCategory.YAUTO_SPECIFIC, FeaturePickerCategory.MEDIA,
        FeaturePickerCategory.MESSAGING, FeaturePickerCategory.NOTIFICATIONS,
        FeaturePickerCategory.PHONE, FeaturePickerCategory.SCREEN,
        FeaturePickerCategory.VARIABLES, FeaturePickerCategory.VOLUME,
        FeaturePickerCategory.WEB_INTERACTIONS,
    )
    FeatureKind.STATE, FeatureKind.CONDITION -> setOf(
        FeaturePickerCategory.SENSORS, FeaturePickerCategory.BATTERY_POWER,
        FeaturePickerCategory.MEDIA, FeaturePickerCategory.LOCATION,
        FeaturePickerCategory.SCREEN, FeaturePickerCategory.DEVICE_STATE,
        FeaturePickerCategory.CONNECTIVITY, FeaturePickerCategory.NOTIFICATIONS,
        FeaturePickerCategory.PHONE, FeaturePickerCategory.DATE_TIME,
        FeaturePickerCategory.YAUTO_SPECIFIC,
    )
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
