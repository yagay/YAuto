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
    CONDITIONS_LOOPS,
    YAUTO_SPECIFIC,
    MEDIA,
    MESSAGING,
    NOTIFICATIONS,
    PHONE,
    SCREEN,
    SENSORS,
    USER_INPUT,
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

    return when {
        starts("ai.") || has(".ai.") ->
            if (kind == FeatureKind.ACTION) FeaturePickerCategory.AI
            else FeaturePickerCategory.YAUTO_SPECIFIC

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

        // Conditions/loops is an Action-only MacroDroid category.
        kind == FeatureKind.ACTION && (
            starts("flow.") ||
                has(
                    ".flow.", ".loop", ".branch", ".delay", ".wait", ".parallel",
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

        has(
            ".wifi", ".bluetooth", ".ble", ".mobile_data", ".airplane", ".hotspot",
            ".tether", ".nfc", ".usb", ".connectivity", ".network_profile",
            ".data_usage", ".matter", ".wear", ".wireguard", ".vpn", ".private_dns",
            ".data_saver", ".ethernet", ".internet", "account_sync", ".sync.account",
        ) ->
            FeaturePickerCategory.CONNECTIVITY

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
    FeatureCategory.VARIABLE -> FeaturePickerCategory.YAUTO_SPECIFIC
    FeatureCategory.FLOW ->
        if (kind == FeatureKind.ACTION) FeaturePickerCategory.CONDITIONS_LOOPS
        else FeaturePickerCategory.YAUTO_SPECIFIC
    FeatureCategory.UI_AUTOMATION -> FeaturePickerCategory.USER_INPUT
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
