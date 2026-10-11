package com.yagay.yauto

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.CaptioningManager
import java.util.Locale
import java.util.TimeZone

/**
 * Safe, read-only Android API observations for parameterized STATE / CONDITION
 * features. A successful getter is interface evidence, not proof that a rule
 * with arbitrary user-configured arguments evaluates or that a side-effect action works.
 *
 * Keep this catalog parameter-free: never inspect contacts, notifications,
 * text, SSIDs, locations or other user content.
 */
internal object AdditionalReadOnlyInterfaceProbe {
    val keys: Set<String> = setOf(
        "clock_24h", "accessibility_enabled", "auto_time",
        "build_release", "build_sdk", "build_brand", "build_manufacturer",
        "build_model", "build_device", "build_product", "build_tags",
        "build_type", "build_security_patch", "primary_abi",
        "locale", "timezone", "layout_direction_rtl",
        "screen_density_dpi", "screen_width_dp", "screen_height_dp",
        "smallest_width_dp", "font_scale_percent", "ui_night_mode",
        "has_nfc", "has_nfc_hce", "has_bluetooth_le", "has_camera",
        "has_camera_flash", "has_microphone", "has_gps", "has_telephony",
        "has_usb_host", "has_fingerprint", "supports_picture_in_picture",
        "supports_freeform_window", "touch_exploration_enabled",
        "memory_available", "low_memory", "battery_charge_counter",
        "battery_current", "battery_energy_counter", "battery_technology",
        "charging_source", "device_locked", "dnd_filter", "dnd",
        "audio.microphone_muted", "audio.speakerphone", "audio.ringer_mode",
        "thermal_level", "uptime_range", "storage_available", "master_sync",
        "notification_badging", "color_inversion", "screen_saver_enabled",
        "captioning_enabled",
    )

    fun read(context: Context, key: String): Pair<String, String> {
        require(key in keys) { "Unsupported read-only interface" }
        val resolver = context.contentResolver
        val config = context.resources.configuration
        val pm = context.packageManager
        return when (key) {
            "clock_24h" ->
                "DateFormat.is24HourFormat" to DateFormat.is24HourFormat(context).toString()
            "accessibility_enabled" ->
                "Settings.Secure.ACCESSIBILITY_ENABLED" to
                    (Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 0).toString()
            "auto_time" ->
                "Settings.Global.AUTO_TIME" to
                    (Settings.Global.getInt(resolver, Settings.Global.AUTO_TIME, 0) != 0).toString()
            "build_release" -> "Build.VERSION.RELEASE" to Build.VERSION.RELEASE
            "build_sdk" -> "Build.VERSION.SDK_INT" to Build.VERSION.SDK_INT.toString()
            "build_brand" -> "Build.BRAND" to Build.BRAND
            "build_manufacturer" -> "Build.MANUFACTURER" to Build.MANUFACTURER
            "build_model" -> "Build.MODEL" to Build.MODEL
            "build_device" -> "Build.DEVICE" to Build.DEVICE
            "build_product" -> "Build.PRODUCT" to Build.PRODUCT
            "build_tags" -> "Build.TAGS" to Build.TAGS
            "build_type" -> "Build.TYPE" to Build.TYPE
            "build_security_patch" ->
                "Build.VERSION.SECURITY_PATCH" to Build.VERSION.SECURITY_PATCH
            "primary_abi" ->
                "Build.SUPPORTED_ABIS" to Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
            "locale" ->
                "Locale.getDefault" to Locale.getDefault().toLanguageTag()
            "timezone" ->
                "TimeZone.getDefault" to TimeZone.getDefault().id
            "layout_direction_rtl" ->
                "Configuration.layoutDirection" to
                    (config.layoutDirection == View.LAYOUT_DIRECTION_RTL).toString()
            "screen_density_dpi" ->
                "DisplayMetrics.densityDpi" to context.resources.displayMetrics.densityDpi.toString()
            "screen_width_dp" ->
                "Configuration.screenWidthDp" to config.screenWidthDp.toString()
            "screen_height_dp" ->
                "Configuration.screenHeightDp" to config.screenHeightDp.toString()
            "smallest_width_dp" ->
                "Configuration.smallestScreenWidthDp" to config.smallestScreenWidthDp.toString()
            "font_scale_percent" ->
                "Configuration.fontScale" to (config.fontScale * 100f).toString()
            "ui_night_mode" ->
                "Configuration.UI_MODE_NIGHT_MASK" to
                    (config.uiMode and Configuration.UI_MODE_NIGHT_MASK).toString()
            "has_nfc" ->
                feature(pm, PackageManager.FEATURE_NFC)
            "has_nfc_hce" ->
                feature(pm, PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)
            "has_bluetooth_le" ->
                feature(pm, PackageManager.FEATURE_BLUETOOTH_LE)
            "has_camera" ->
                feature(pm, PackageManager.FEATURE_CAMERA_ANY)
            "has_camera_flash" ->
                feature(pm, PackageManager.FEATURE_CAMERA_FLASH)
            "has_microphone" ->
                feature(pm, PackageManager.FEATURE_MICROPHONE)
            "has_gps" ->
                feature(pm, PackageManager.FEATURE_LOCATION_GPS)
            "has_telephony" ->
                feature(pm, PackageManager.FEATURE_TELEPHONY)
            "has_usb_host" ->
                feature(pm, PackageManager.FEATURE_USB_HOST)
            "has_fingerprint" ->
                feature(pm, PackageManager.FEATURE_FINGERPRINT)
            "supports_picture_in_picture" ->
                feature(pm, PackageManager.FEATURE_PICTURE_IN_PICTURE)
            "supports_freeform_window" ->
                feature(pm, "android.software.freeform_window_management")
            "touch_exploration_enabled" ->
                "AccessibilityManager.isTouchExplorationEnabled" to
                    context.getSystemService(AccessibilityManager::class.java)
                        .isTouchExplorationEnabled.toString()
            "memory_available", "low_memory" -> {
                val info = ActivityManager.MemoryInfo()
                context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
                if (key == "memory_available") {
                    "ActivityManager.MemoryInfo.availMem" to info.availMem.toString()
                } else {
                    "ActivityManager.MemoryInfo.lowMemory" to info.lowMemory.toString()
                }
            }
            "battery_charge_counter", "battery_current", "battery_energy_counter" -> {
                val battery = context.getSystemService(BatteryManager::class.java)
                when (key) {
                    "battery_charge_counter" ->
                        "BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER" to
                            battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).toString()
                    "battery_current" ->
                        "BatteryManager.BATTERY_PROPERTY_CURRENT_NOW" to
                            battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toString()
                    else ->
                        "BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER" to
                            battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER).toString()
                }
            }
            "battery_technology", "charging_source" -> {
                val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    ?: error("Battery status unavailable")
                if (key == "battery_technology") {
                    "ACTION_BATTERY_CHANGED.technology" to
                        (intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "unknown").take(40)
                } else {
                    "ACTION_BATTERY_CHANGED.plugged" to
                        intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0).toString()
                }
            }
            "device_locked" ->
                "KeyguardManager.isDeviceLocked" to
                    context.getSystemService(KeyguardManager::class.java).isDeviceLocked.toString()
            "dnd_filter", "dnd" -> {
                val filter = context.getSystemService(NotificationManager::class.java)
                    .currentInterruptionFilter
                if (key == "dnd_filter") {
                    "NotificationManager.currentInterruptionFilter" to filter.toString()
                } else {
                    "NotificationManager.currentInterruptionFilter" to
                        (filter != NotificationManager.INTERRUPTION_FILTER_ALL).toString()
                }
            }
            "audio.microphone_muted" ->
                "AudioManager.isMicrophoneMute" to
                    context.getSystemService(AudioManager::class.java).isMicrophoneMute.toString()
            "audio.speakerphone" ->
                "AudioManager.isSpeakerphoneOn" to
                    context.getSystemService(AudioManager::class.java).isSpeakerphoneOn.toString()
            "audio.ringer_mode" ->
                "AudioManager.ringerMode" to
                    context.getSystemService(AudioManager::class.java).ringerMode.toString()
            "thermal_level" ->
                "PowerManager.currentThermalStatus" to
                    context.getSystemService(PowerManager::class.java).currentThermalStatus.toString()
            "uptime_range" ->
                "SystemClock.uptimeMillis" to SystemClock.uptimeMillis().toString()
            "storage_available" ->
                "StatFs.availableBytes" to
                    StatFs(Environment.getDataDirectory().absolutePath).availableBytes.toString()
            "master_sync" ->
                "ContentResolver.getMasterSyncAutomatically" to
                    ContentResolver.getMasterSyncAutomatically().toString()
            "notification_badging" ->
                "Settings.Secure.notification_badging" to
                    (Settings.Secure.getInt(resolver, "notification_badging", 0) != 0).toString()
            "color_inversion" ->
                "Settings.Secure.accessibility_display_inversion_enabled" to
                    (Settings.Secure.getInt(resolver, "accessibility_display_inversion_enabled", 0) != 0).toString()
            "screen_saver_enabled" ->
                "Settings.Secure.screensaver_enabled" to
                    (Settings.Secure.getInt(resolver, "screensaver_enabled", 0) != 0).toString()
            "captioning_enabled" ->
                "CaptioningManager.isEnabled" to
                    context.getSystemService(CaptioningManager::class.java).isEnabled.toString()
            else -> error("No standalone read-only query")
        }
    }

    private fun feature(pm: PackageManager, featureName: String): Pair<String, String> =
        "PackageManager.hasSystemFeature($featureName)" to pm.hasSystemFeature(featureName).toString()
}
