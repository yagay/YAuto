package com.yagay.yauto.platform.android
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.ConditionEvaluator
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureExecutionContext
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.FieldSchema
import java.util.Calendar
import java.util.TimeZone
/**
* Second reference-APK gap batch. The concepts in this pack were verified against
* MacroDroid 5.67.8, ShortX+ 1.11 and Tasker 6.6.20 before implementation.
*
* Exactly 50 native queries are registered as both STATE and CONDITION descriptors,
* contributing 100 user-facing features without adding importer compatibility code.
*/
class AndroidReferenceGapFeaturePack(context: Context) : FeaturePack {
override val id: String = "android.reference_gap"
private val context = context.applicationContext
private val audio = this.context.getSystemService(AudioManager::class.java)
private val displays = this.context.getSystemService(DisplayManager::class.java)
private val packages = this.context.packageManager
override fun install(registry: FeatureRegistry) {
numberPair(registry, "battery_voltage_mv", "Battery voltage", "Compare the battery voltage reported by Android in millivolts", FeatureCategory.DEVICE, 0.0, 30_000.0) {
batteryIntent()?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)?.takeUnless { it == Int.MIN_VALUE }?.toDouble()
}
choicePair(registry, "battery_status", "Battery status", "Match Android's current battery charging status", FeatureCategory.DEVICE,
listOf("charging", "discharging", "full", "not_charging", "unknown")) { batteryStatus() }
choicePair(registry, "battery_plug_source", "Battery power source", "Match the external power source currently reported by Android", FeatureCategory.DEVICE,
listOf("none", "ac", "usb", "wireless", "dock", "unknown")) { batteryPlugSource() }
booleanPair(registry, "battery_present", "Battery present", "Check whether Android reports a physical battery as present", FeatureCategory.DEVICE) {
batteryIntent()?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false) == true
}
choicePair(registry, "audio_mode", "Audio mode", "Match AudioManager's current telephony/communication audio mode", FeatureCategory.AUDIO,
listOf("normal", "ringtone", "in_call", "in_communication", "call_screening", "unknown")) { referenceAudioModeName(audio.mode) }
booleanPair(registry, "wired_headphones_connected", "Wired headphones connected", "Check for a wired headset or wired headphones output device", FeatureCategory.AUDIO) {
outputDevices().any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }
}
booleanPair(registry, "bluetooth_audio_connected", "Bluetooth audio connected", "Check for an active Bluetooth A2DP or Bluetooth LE audio output device", FeatureCategory.AUDIO) {
outputDevices().any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER }
}
booleanPair(registry, "usb_audio_connected", "USB audio connected", "Check for a USB audio device or USB headset output", FeatureCategory.AUDIO) {
outputDevices().any { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY }
}
numberPair(registry, "audio_output_device_count", "Audio output device count", "Compare the number of currently enumerated Android audio output devices", FeatureCategory.AUDIO, 0.0, 128.0) {
outputDevices().size.toDouble()
}
choicePair(registry, "communication_device_type", "Communication audio device", "Match the current Android communication audio route", FeatureCategory.AUDIO,
listOf("none", "earpiece", "speaker", "wired", "bluetooth", "usb", "other")) { communicationDeviceType() }
booleanPair(registry, "sound_effects_enabled", "System sound effects", "Check Android's system sound-effects setting", FeatureCategory.AUDIO) {
Settings.System.getInt(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0
}
booleanPair(registry, "haptic_feedback_enabled", "Haptic feedback", "Check Android's global haptic-feedback setting", FeatureCategory.DEVICE) {
Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
}
choicePair(registry, "orientation", "Screen orientation", "Match the current Android resource orientation", FeatureCategory.DISPLAY,
listOf("portrait", "landscape", "square", "undefined")) { orientationName(context.resources.configuration.orientation) }
choicePair(registry, "display_rotation", "Display rotation", "Match the current default-display rotation", FeatureCategory.DISPLAY,
listOf("0", "90", "180", "270", "unknown")) { displayRotationName(defaultDisplay()?.rotation) }
numberPair(registry, "display_refresh_rate", "Display refresh rate", "Compare the current default-display refresh rate in hertz", FeatureCategory.DISPLAY, 1.0, 1000.0) {
defaultDisplay()?.refreshRate?.toDouble()
}
numberPair(registry, "display_width_px", "Display width pixels", "Compare the current real default-display width in pixels", FeatureCategory.DISPLAY, 1.0, 20_000.0) {
realDisplayMetrics()?.widthPixels?.toDouble()
}
numberPair(registry, "display_height_px", "Display height pixels", "Compare the current real default-display height in pixels", FeatureCategory.DISPLAY, 1.0, 20_000.0) {
realDisplayMetrics()?.heightPixels?.toDouble()
}
numberPair(registry, "font_scale", "System font scale", "Compare the current Android configuration font scale", FeatureCategory.DISPLAY, 0.1, 10.0) {
context.resources.configuration.fontScale.toDouble()
}
choicePair(registry, "touchscreen_type", "Touchscreen type", "Match Android's current touchscreen configuration", FeatureCategory.DEVICE,
listOf("none", "finger", "stylus", "undefined", "other")) { touchscreenName(context.resources.configuration.touchscreen) }
choicePair(registry, "keyboard_type", "Keyboard type", "Match Android's current hardware keyboard configuration", FeatureCategory.DEVICE,
listOf("none", "qwerty", "12key", "undefined", "other")) { keyboardName(context.resources.configuration.keyboard) }
choicePair(registry, "keyboard_hidden", "Keyboard visibility", "Match Android's current keyboard-hidden configuration", FeatureCategory.DEVICE,
listOf("no", "yes", "soft", "undefined", "other")) { keyboardHiddenName(context.resources.configuration.keyboardHidden) }
choicePair(registry, "hard_keyboard_hidden", "Hardware keyboard visibility", "Match Android's current hard-keyboard-hidden configuration", FeatureCategory.DEVICE,
listOf("no", "yes", "undefined", "other")) { hardKeyboardHiddenName(context.resources.configuration.hardKeyboardHidden) }
choicePair(registry, "navigation_type", "Navigation hardware type", "Match Android's current navigation hardware configuration", FeatureCategory.DEVICE,
listOf("none", "dpad", "trackball", "wheel", "undefined", "other")) { navigationName(context.resources.configuration.navigation) }
choicePair(registry, "screen_layout_size", "Screen layout size class", "Match Android's current screen-layout size class", FeatureCategory.DISPLAY,
listOf("small", "normal", "large", "xlarge", "undefined")) { screenLayoutSizeName(context.resources.configuration.screenLayout) }
booleanPair(registry, "screen_layout_long", "Long screen layout", "Check whether Android classifies the screen as long", FeatureCategory.DISPLAY) {
context.resources.configuration.screenLayout and Configuration.SCREENLAYOUT_LONG_MASK == Configuration.SCREENLAYOUT_LONG_YES
}
booleanPair(registry, "screen_layout_round", "Round screen layout", "Check whether Android classifies the screen as round", FeatureCategory.DISPLAY) {
context.resources.configuration.screenLayout and Configuration.SCREENLAYOUT_ROUND_MASK == Configuration.SCREENLAYOUT_ROUND_YES
}
choicePair(registry, "ui_mode_type", "UI mode type", "Match Android's current UI mode type", FeatureCategory.DISPLAY,
listOf("normal", "desk", "car", "television", "appliance", "watch", "vr_headset", "undefined", "other")) { uiModeTypeName(context.resources.configuration.uiMode) }
booleanPair(registry, "wide_color_gamut", "Wide color gamut", "Check whether the current Android configuration uses a wide color gamut", FeatureCategory.DISPLAY) {
context.resources.configuration.colorMode and Configuration.COLOR_MODE_WIDE_COLOR_GAMUT_MASK == Configuration.COLOR_MODE_WIDE_COLOR_GAMUT_YES
}
booleanPair(registry, "hdr_color_mode", "HDR color mode", "Check whether the current Android configuration reports HDR color mode", FeatureCategory.DISPLAY) {
context.resources.configuration.colorMode and Configuration.COLOR_MODE_HDR_MASK == Configuration.COLOR_MODE_HDR_YES
}
numberPair(registry, "mobile_country_code", "Mobile country code", "Compare the MCC exposed by the current Android resource configuration", FeatureCategory.NETWORK, 0.0, 999.0) {
context.resources.configuration.mcc.toDouble()
}
numberPair(registry, "mobile_network_code", "Mobile network code", "Compare the MNC exposed by the current Android resource configuration", FeatureCategory.NETWORK, 0.0, 999.0) {
context.resources.configuration.mnc.toDouble()
}
numberPair(registry, "font_weight_adjustment", "Font weight adjustment", "Compare Android's current font-weight adjustment value", FeatureCategory.DISPLAY, -1000.0, 1000.0) {
context.resources.configuration.fontWeightAdjustment.takeUnless { it == Configuration.FONT_WEIGHT_ADJUSTMENT_UNDEFINED }?.toDouble()
}
choicePair(registry, "display_state", "Display power state", "Match the current default-display power state", FeatureCategory.DISPLAY,
listOf("off", "on", "doze", "doze_suspend", "vr", "on_suspend", "unknown")) { displayStateName(defaultDisplay()?.state) }
numberPair(registry, "display_mode_id", "Display mode ID", "Compare the current default-display mode identifier", FeatureCategory.DISPLAY, 0.0, 10_000.0) {
defaultDisplay()?.mode?.modeId?.toDouble()
}
numberPair(registry, "display_physical_width", "Display mode physical width", "Compare the current display mode physical width in pixels", FeatureCategory.DISPLAY, 1.0, 20_000.0) {
defaultDisplay()?.mode?.physicalWidth?.toDouble()
}
numberPair(registry, "display_physical_height", "Display mode physical height", "Compare the current display mode physical height in pixels", FeatureCategory.DISPLAY, 1.0, 20_000.0) {
defaultDisplay()?.mode?.physicalHeight?.toDouble()
}
numberPair(registry, "display_supported_modes_count", "Supported display mode count", "Compare the number of modes supported by the default display", FeatureCategory.DISPLAY, 0.0, 256.0) {
defaultDisplay()?.supportedModes?.size?.toDouble()
}
numberPair(registry, "display_hdr_type_count", "Supported HDR type count", "Compare the number of HDR types supported by the default display", FeatureCategory.DISPLAY, 0.0, 64.0) {
defaultDisplay()?.hdrCapabilities?.supportedHdrTypes?.size?.toDouble()
}
choicePair(registry, "day_of_week", "Day of week", "Match the current local day of week", FeatureCategory.SYSTEM,
listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")) { dayOfWeekName(Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) }
numberPair(registry, "day_of_month", "Day of month", "Compare the current local day of month", FeatureCategory.SYSTEM, 1.0, 31.0) {
Calendar.getInstance().get(Calendar.DAY_OF_MONTH).toDouble()
}
numberPair(registry, "month_of_year", "Month of year", "Compare the current local month number from 1 to 12", FeatureCategory.SYSTEM, 1.0, 12.0) {
(Calendar.getInstance().get(Calendar.MONTH) + 1).toDouble()
}
numberPair(registry, "day_of_year", "Day of year", "Compare the current local day number within the year", FeatureCategory.SYSTEM, 1.0, 366.0) {
Calendar.getInstance().get(Calendar.DAY_OF_YEAR).toDouble()
}
numberPair(registry, "week_of_year", "Week of year", "Compare the current local calendar week number", FeatureCategory.SYSTEM, 1.0, 53.0) {
Calendar.getInstance().get(Calendar.WEEK_OF_YEAR).toDouble()
}
numberPair(registry, "hour_of_day", "Hour of day", "Compare the current local hour using a 24-hour clock", FeatureCategory.SYSTEM, 0.0, 23.0) {
Calendar.getInstance().get(Calendar.HOUR_OF_DAY).toDouble()
}
booleanPair(registry, "weekend", "Weekend", "Check whether the current local day is Saturday or Sunday", FeatureCategory.SYSTEM) {
val day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
day == Calendar.SATURDAY || day == Calendar.SUNDAY
}
booleanPair(registry, "daylight_saving", "Daylight saving time", "Check whether the current time zone is observing daylight saving time now", FeatureCategory.SYSTEM) {
TimeZone.getDefault().inDaylightTime(java.util.Date())
}
appBooleanPair(registry, "app_enabled", "Application enabled", "Check whether the selected application is enabled for the current user") { it.enabled }
appBooleanPair(registry, "app_system", "System application", "Check whether the selected package is a system or updated-system application") {
it.flags and ApplicationInfo.FLAG_SYSTEM != 0 || it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
}
appNumberPair(registry, "app_target_sdk", "Application target SDK", "Compare the selected application's target SDK level", 1.0, 100.0) { it.targetSdkVersion.toDouble() }
appChoicePair(registry, "app_category", "Application category", "Match the Android package category assigned to the selected application",
listOf("game", "audio", "video", "image", "social", "news", "maps", "productivity", "accessibility", "undefined", "other")) { applicationCategoryName(it.category) }
}
private fun batteryIntent(): Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
private fun batteryStatus(): String = when (batteryIntent()?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)) {
BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
BatteryManager.BATTERY_STATUS_FULL -> "full"
BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
else -> "unknown"
}
private fun batteryPlugSource(): String = when (val plugged = batteryIntent()?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) {
0 -> "none"
BatteryManager.BATTERY_PLUGGED_AC -> "ac"
BatteryManager.BATTERY_PLUGGED_USB -> "usb"
BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
else -> if (Build.VERSION.SDK_INT >= 33 && plugged == BatteryManager.BATTERY_PLUGGED_DOCK) "dock" else "unknown"
}
private fun outputDevices(): Array<AudioDeviceInfo> = runCatching { audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrDefault(emptyArray())
private fun communicationDeviceType(): String {
val type = if (Build.VERSION.SDK_INT >= 31) audio.communicationDevice?.type else null
return when (type) {
null -> "none"
AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired"
AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "bluetooth"
AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "usb"
else -> "other"
}
}
private fun defaultDisplay(): Display? = displays.getDisplay(Display.DEFAULT_DISPLAY)
@Suppress("DEPRECATION")
private fun realDisplayMetrics(): DisplayMetrics? = defaultDisplay()?.let { display -> DisplayMetrics().also(display::getRealMetrics) }
private fun applicationInfo(packageName: String): ApplicationInfo? = runCatching {
@Suppress("DEPRECATION")
packages.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS)
}.getOrNull()
private fun booleanPair(registry: FeatureRegistry, key: String, title: String, description: String, category: FeatureCategory, query: () -> Boolean) =
pair(registry, key, title, description, category, listOf(FieldSchema.Toggle("value", "Enabled / true"))) { feature, _ -> query() == feature.config.boolean("value", true) }
private fun choicePair(registry: FeatureRegistry, key: String, title: String, description: String, category: FeatureCategory, options: List<String>, query: () -> String) =
pair(registry, key, title, description, category, listOf(FieldSchema.Choice("value", "Expected value", true, options))) { feature, _ -> query() == feature.config.string("value", options.first()) }
private fun numberPair(registry: FeatureRegistry, key: String, title: String, description: String, category: FeatureCategory, allowedMin: Double, allowedMax: Double, query: () -> Double?) =
pair(registry, key, title, description, category, numberFields(allowedMin, allowedMax)) { feature, _ ->
val value = query() ?: return@pair false
matchesReferenceGapNumber(value, feature.config["min"].numberOrNull(), feature.config["max"].numberOrNull(), allowedMin, allowedMax)
}
private fun appBooleanPair(registry: FeatureRegistry, key: String, title: String, description: String, query: (ApplicationInfo) -> Boolean) =
pair(registry, key, title, description, FeatureCategory.APP, listOf(FieldSchema.AppPicker("package", "App / package", true), FieldSchema.Toggle("value", "Enabled / true"))) { feature, _ ->
val info = applicationInfo(feature.config.string("package").trim()) ?: return@pair false
query(info) == feature.config.boolean("value", true)
}
private fun appNumberPair(registry: FeatureRegistry, key: String, title: String, description: String, allowedMin: Double, allowedMax: Double, query: (ApplicationInfo) -> Double) =
pair(registry, key, title, description, FeatureCategory.APP, listOf(FieldSchema.AppPicker("package", "App / package", true)) + numberFields(allowedMin, allowedMax)) { feature, _ ->
val info = applicationInfo(feature.config.string("package").trim()) ?: return@pair false
matchesReferenceGapNumber(query(info), feature.config["min"].numberOrNull(), feature.config["max"].numberOrNull(), allowedMin, allowedMax)
}
private fun appChoicePair(registry: FeatureRegistry, key: String, title: String, description: String, options: List<String>, query: (ApplicationInfo) -> String) =
pair(registry, key, title, description, FeatureCategory.APP, listOf(FieldSchema.AppPicker("package", "App / package", true), FieldSchema.Choice("value", "Expected value", true, options))) { feature, _ ->
val info = applicationInfo(feature.config.string("package").trim()) ?: return@pair false
query(info) == feature.config.string("value", options.first())
}
private fun numberFields(min: Double, max: Double) = listOf(
FieldSchema.Number("min", "Minimum", min = min, max = max),
FieldSchema.Number("max", "Maximum", min = min, max = max),
)
private fun pair(
registry: FeatureRegistry,
key: String,
title: String,
description: String,
category: FeatureCategory,
fields: List<FieldSchema>,
evaluate: suspend (com.yagay.yauto.core.model.FeatureRef, FeatureExecutionContext) -> Boolean,
) {
val evaluator = ConditionEvaluator { feature, ctx -> runCatching { evaluate(feature, ctx) }.getOrDefault(false) }
val state = FeatureDescriptor(
FeatureId("android.state.reference.$key"), FeatureKind.STATE, title, description, category,
fields = fields, keywords = setOf("reference", "MacroDroid", "ShortX", "Tasker"), ownerPackId = id,
)
registry.registerState(state, evaluator)
registry.registerCondition(state.copy(id = FeatureId("android.condition.reference.$key"), kind = FeatureKind.CONDITION), evaluator)
}
}
internal val REFERENCE_APK_GAP_KEYS = listOf(
"battery_voltage_mv", "battery_status", "battery_plug_source", "battery_present",
"audio_mode", "wired_headphones_connected", "bluetooth_audio_connected", "usb_audio_connected",
"audio_output_device_count", "communication_device_type", "sound_effects_enabled", "haptic_feedback_enabled",
"orientation", "display_rotation", "display_refresh_rate", "display_width_px", "display_height_px", "font_scale",
"touchscreen_type", "keyboard_type", "keyboard_hidden", "hard_keyboard_hidden", "navigation_type", "screen_layout_size",
"screen_layout_long", "screen_layout_round", "ui_mode_type", "wide_color_gamut", "hdr_color_mode", "mobile_country_code",
"mobile_network_code", "font_weight_adjustment", "display_state", "display_mode_id", "display_physical_width",
"display_physical_height", "display_supported_modes_count", "display_hdr_type_count", "day_of_week", "day_of_month",
"month_of_year", "day_of_year", "week_of_year", "hour_of_day", "weekend", "daylight_saving",
"app_enabled", "app_system", "app_target_sdk", "app_category",
)
internal fun matchesReferenceGapNumber(value: Double, min: Double?, max: Double?, allowedMin: Double, allowedMax: Double): Boolean {
if (!value.isFinite() || value !in allowedMin..allowedMax) return false
val safeMin = min ?: allowedMin
val safeMax = max ?: allowedMax
if (!safeMin.isFinite() || !safeMax.isFinite() || safeMin !in allowedMin..allowedMax || safeMax !in allowedMin..allowedMax || safeMax < safeMin) return false
return value in safeMin..safeMax
}
internal fun referenceAudioModeName(mode: Int): String = when (mode) {
AudioManager.MODE_NORMAL -> "normal"
AudioManager.MODE_RINGTONE -> "ringtone"
AudioManager.MODE_IN_CALL -> "in_call"
AudioManager.MODE_IN_COMMUNICATION -> "in_communication"
AudioManager.MODE_CALL_SCREENING -> "call_screening"
else -> "unknown"
}
internal fun orientationName(value: Int): String = when (value) {
Configuration.ORIENTATION_PORTRAIT -> "portrait"
Configuration.ORIENTATION_LANDSCAPE -> "landscape"
Configuration.ORIENTATION_SQUARE -> "square"
else -> "undefined"
}
internal fun displayRotationName(value: Int?): String = when (value) {
Surface.ROTATION_0 -> "0"
Surface.ROTATION_90 -> "90"
Surface.ROTATION_180 -> "180"
Surface.ROTATION_270 -> "270"
else -> "unknown"
}
internal fun touchscreenName(value: Int): String = when (value) {
Configuration.TOUCHSCREEN_NOTOUCH -> "none"
Configuration.TOUCHSCREEN_FINGER -> "finger"
Configuration.TOUCHSCREEN_STYLUS -> "stylus"
Configuration.TOUCHSCREEN_UNDEFINED -> "undefined"
else -> "other"
}
internal fun keyboardName(value: Int): String = when (value) {
Configuration.KEYBOARD_NOKEYS -> "none"
Configuration.KEYBOARD_QWERTY -> "qwerty"
Configuration.KEYBOARD_12KEY -> "12key"
Configuration.KEYBOARD_UNDEFINED -> "undefined"
else -> "other"
}
internal fun keyboardHiddenName(value: Int): String = when (value) {
Configuration.KEYBOARDHIDDEN_NO -> "no"
Configuration.KEYBOARDHIDDEN_YES -> "yes"
3 -> "soft"
Configuration.KEYBOARDHIDDEN_UNDEFINED -> "undefined"
else -> "other"
}
internal fun hardKeyboardHiddenName(value: Int): String = when (value) {
Configuration.HARDKEYBOARDHIDDEN_NO -> "no"
Configuration.HARDKEYBOARDHIDDEN_YES -> "yes"
Configuration.HARDKEYBOARDHIDDEN_UNDEFINED -> "undefined"
else -> "other"
}
internal fun navigationName(value: Int): String = when (value) {
Configuration.NAVIGATION_NONAV -> "none"
Configuration.NAVIGATION_DPAD -> "dpad"
Configuration.NAVIGATION_TRACKBALL -> "trackball"
Configuration.NAVIGATION_WHEEL -> "wheel"
Configuration.NAVIGATION_UNDEFINED -> "undefined"
else -> "other"
}
internal fun screenLayoutSizeName(screenLayout: Int): String = when (screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) {
Configuration.SCREENLAYOUT_SIZE_SMALL -> "small"
Configuration.SCREENLAYOUT_SIZE_NORMAL -> "normal"
Configuration.SCREENLAYOUT_SIZE_LARGE -> "large"
Configuration.SCREENLAYOUT_SIZE_XLARGE -> "xlarge"
else -> "undefined"
}
internal fun uiModeTypeName(uiMode: Int): String = when (uiMode and Configuration.UI_MODE_TYPE_MASK) {
Configuration.UI_MODE_TYPE_NORMAL -> "normal"
Configuration.UI_MODE_TYPE_DESK -> "desk"
Configuration.UI_MODE_TYPE_CAR -> "car"
Configuration.UI_MODE_TYPE_TELEVISION -> "television"
Configuration.UI_MODE_TYPE_APPLIANCE -> "appliance"
Configuration.UI_MODE_TYPE_WATCH -> "watch"
Configuration.UI_MODE_TYPE_VR_HEADSET -> "vr_headset"
Configuration.UI_MODE_TYPE_UNDEFINED -> "undefined"
else -> "other"
}
internal fun displayStateName(state: Int?): String = when (state) {
Display.STATE_OFF -> "off"
Display.STATE_ON -> "on"
Display.STATE_DOZE -> "doze"
Display.STATE_DOZE_SUSPEND -> "doze_suspend"
Display.STATE_VR -> "vr"
Display.STATE_ON_SUSPEND -> "on_suspend"
else -> "unknown"
}
internal fun dayOfWeekName(day: Int): String = when (day) {
Calendar.SUNDAY -> "sunday"
Calendar.MONDAY -> "monday"
Calendar.TUESDAY -> "tuesday"
Calendar.WEDNESDAY -> "wednesday"
Calendar.THURSDAY -> "thursday"
Calendar.FRIDAY -> "friday"
Calendar.SATURDAY -> "saturday"
else -> "sunday"
}
internal fun applicationCategoryName(category: Int): String = when (category) {
ApplicationInfo.CATEGORY_GAME -> "game"
ApplicationInfo.CATEGORY_AUDIO -> "audio"
ApplicationInfo.CATEGORY_VIDEO -> "video"
ApplicationInfo.CATEGORY_IMAGE -> "image"
ApplicationInfo.CATEGORY_SOCIAL -> "social"
ApplicationInfo.CATEGORY_NEWS -> "news"
ApplicationInfo.CATEGORY_MAPS -> "maps"
ApplicationInfo.CATEGORY_PRODUCTIVITY -> "productivity"
ApplicationInfo.CATEGORY_ACCESSIBILITY -> "accessibility"
ApplicationInfo.CATEGORY_UNDEFINED -> "undefined"
else -> "other"
}
