package com.yagay.yauto.core.registry

import java.util.Locale

/**
 * User-facing semantic domain for the feature picker.
 *
 * This is intentionally separate from [FeatureCategory]. FeatureCategory remains a legacy/internal
 * implementation bucket so existing feature packs stay source compatible, while picker navigation
 * is driven by what the user is trying to automate.
 */
enum class FeatureDomain {
    APPLICATIONS,
    POWER,
    COMMUNICATION,
    CONNECTIVITY,
    DATE_TIME,
    DEVICE,
    DISPLAY,
    AUDIO_MEDIA,
    NOTIFICATIONS,
    LOCATION,
    SENSORS,
    USER_INPUT,
    CAPTURE,
    FILES_STORAGE,
    DATA,
    FLOW_LOGIC,
    WEB_NETWORK,
    AI,
    SCRIPT_COMMANDS,
    YAUTO,
}

/**
 * Compatibility classifier for the existing catalog.
 *
 * New or ambiguous features should set FeatureDescriptor.domain explicitly. The inference keeps the
 * current large catalog organized without forcing every legacy descriptor to be rewritten at once.
 */
fun inferFeatureDomain(id: String, legacyCategory: FeatureCategory): FeatureDomain {
    val key = id.lowercase(Locale.ROOT)
    fun has(vararg tokens: String): Boolean = tokens.any { token -> key.contains(token) }

    return when {
        key.startsWith("ai.") || has(".ai.") ->
            FeatureDomain.AI
        has("screen_record", "screenshot", ".camera", ".photo", ".ocr", ".qr", "image_match", ".image.", ".capture") ->
            FeatureDomain.CAPTURE
        has(".location", ".geofence", ".gps", ".maps.", ".cell_tower", "latitude", "longitude") ->
            FeatureDomain.LOCATION
        has(".sensor", "activity_recognition", ".pedometer", ".proximity", ".accelerometer", ".gyroscope", "light_sensor") ->
            FeatureDomain.SENSORS
        has(".wifi", ".bluetooth", ".ble", ".mobile_data", ".airplane", ".hotspot", ".tether", ".nfc", ".usb", ".connectivity", ".network_profile", ".data_usage", ".matter", ".wear", ".wireguard", ".private_dns", ".data_saver") ->
            FeatureDomain.CONNECTIVITY
        has(".notification", ".toast") ->
            FeatureDomain.NOTIFICATIONS
        has(".audio", ".volume", ".media", ".playback", ".microphone", ".speakerphone", ".speech", ".tts", ".midi") ->
            FeatureDomain.AUDIO_MEDIA
        has(".display", ".brightness", ".screen", ".rotation", ".orientation", ".dpi", ".resolution", ".dark_mode") ->
            FeatureDomain.DISPLAY
        has(".battery", ".charging", ".power", ".reboot", ".shutdown", ".wake_lock", ".doze") ->
            FeatureDomain.POWER
        has(".phone", ".call", ".sms", ".email", ".contact", ".message") ->
            FeatureDomain.COMMUNICATION
        has(".time", ".date", ".interval", ".alarm", ".timer", ".calendar", ".timezone", ".solar", ".stopwatch") ->
            FeatureDomain.DATE_TIME
        has(".key", ".keyboard", ".gesture", ".accessibility", ".ui.", ".overlay", ".surface", ".tap", ".click", ".swipe", ".input", ".biometric", ".qs_tile", ".quick_settings", ".menu_action", ".back_navigation") ->
            FeatureDomain.USER_INPUT
        has(".file", ".directory", ".archive", ".zip", ".storage", ".download") ->
            FeatureDomain.FILES_STORAGE
        has(".http", ".webhook", ".websocket", ".webdav", ".url.") ->
            FeatureDomain.WEB_NETWORK
        key.startsWith("script.") || has(".shell", ".script", ".command", ".exec", ".logcat", ".dumpsys", ".adb_wifi") ->
            FeatureDomain.SCRIPT_COMMANDS
        key.startsWith("data.") || key.startsWith("variable.") || has(".variable", ".json", ".regex", ".hash", ".encode", ".decode", ".math", ".random", ".list", ".object", ".text.", ".clipboard", ".chart") ->
            FeatureDomain.DATA
        has(".flow", ".delay", ".wait", ".loop", ".branch", ".boolean", ".condition", ".parallel", "try_catch") ->
            FeatureDomain.FLOW_LOGIC
        has(".app", ".package", ".activity", ".component", ".foreground", ".shortcut", ".launcher", ".widget", ".work_profile", ".account_sync", ".sync.account", ".role.") ->
            FeatureDomain.APPLICATIONS
        has("manual", ".automation", ".macro", ".workspace", ".yauto", ".access.status", ".log.write", ".log.export") ->
            FeatureDomain.YAUTO
        else -> legacyCategory.defaultDomain()
    }
}

private fun FeatureCategory.defaultDomain(): FeatureDomain = when (this) {
    FeatureCategory.CORE -> FeatureDomain.YAUTO
    FeatureCategory.APP -> FeatureDomain.APPLICATIONS
    FeatureCategory.DEVICE -> FeatureDomain.DEVICE
    FeatureCategory.NETWORK -> FeatureDomain.CONNECTIVITY
    FeatureCategory.DISPLAY -> FeatureDomain.DISPLAY
    FeatureCategory.AUDIO -> FeatureDomain.AUDIO_MEDIA
    FeatureCategory.NOTIFICATION -> FeatureDomain.NOTIFICATIONS
    FeatureCategory.FILE -> FeatureDomain.FILES_STORAGE
    FeatureCategory.VARIABLE -> FeatureDomain.DATA
    FeatureCategory.FLOW -> FeatureDomain.FLOW_LOGIC
    FeatureCategory.UI_AUTOMATION -> FeatureDomain.USER_INPUT
    FeatureCategory.SYSTEM -> FeatureDomain.DEVICE
    FeatureCategory.SCRIPT -> FeatureDomain.SCRIPT_COMMANDS
    FeatureCategory.ADVANCED -> FeatureDomain.DEVICE
    FeatureCategory.COMPATIBILITY -> FeatureDomain.YAUTO
}
