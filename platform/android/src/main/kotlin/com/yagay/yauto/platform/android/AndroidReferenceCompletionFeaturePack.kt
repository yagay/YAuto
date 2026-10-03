package com.yagay.yauto.platform.android

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.UserManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.CaptioningManager
import android.view.inputmethod.InputMethodManager
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

/** 18 native actions + 16 state/condition pairs = 50 user-facing native features. */
class AndroidReferenceCompletionFeaturePack(context: Context) : FeaturePack {
    override val id = "android.reference_completion"
    private val context = context.applicationContext

    override fun install(registry: FeatureRegistry) {
        registerActions(registry)
        booleanPair(registry, "user_unlocked", "User unlocked", "Check whether credential-encrypted storage is unlocked", FeatureCategory.DEVICE) { context.getSystemService(UserManager::class.java).isUserUnlocked }
        booleanPair(registry, "managed_profile", "Managed profile", "Check whether YAuto runs inside a managed profile", FeatureCategory.DEVICE) { context.getSystemService(UserManager::class.java).isManagedProfile }
        booleanPair(registry, "microphone_muted", "Microphone muted", "Check microphone mute state", FeatureCategory.AUDIO) { context.getSystemService(AudioManager::class.java).isMicrophoneMute }
        @Suppress("DEPRECATION")
        booleanPair(registry, "speakerphone_on", "Speakerphone enabled", "Check speakerphone routing state", FeatureCategory.AUDIO) { context.getSystemService(AudioManager::class.java).isSpeakerphoneOn }
        booleanPair(registry, "accessibility_enabled", "Accessibility enabled", "Check whether accessibility is globally enabled", FeatureCategory.UI_AUTOMATION) { context.getSystemService(AccessibilityManager::class.java).isEnabled }
        booleanPair(registry, "touch_exploration_enabled", "Touch exploration enabled", "Check accessibility touch exploration", FeatureCategory.UI_AUTOMATION) { context.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled }
        booleanPair(registry, "overlay_allowed", "Overlay permission granted", "Check draw-over-other-apps access", FeatureCategory.SYSTEM) { Settings.canDrawOverlays(context) }
        booleanPair(registry, "write_settings_allowed", "Modify system settings granted", "Check modify-system-settings access", FeatureCategory.SYSTEM) { Settings.System.canWrite(context) }
        booleanPair(registry, "dnd_policy_access", "Do Not Disturb access granted", "Check notification-policy access", FeatureCategory.NOTIFICATION) { context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted }
        booleanPair(registry, "exact_alarm_access", "Exact alarm access granted", "Check exact-alarm access", FeatureCategory.SYSTEM) { context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() }
        booleanPair(registry, "captioning_enabled", "System captions enabled", "Check Android captioning state", FeatureCategory.SYSTEM) { context.getSystemService(CaptioningManager::class.java).isEnabled }
        booleanPair(registry, "adb_enabled", "ADB enabled", "Check Android ADB setting", FeatureCategory.ADVANCED) { Settings.Global.getInt(context.contentResolver, "adb_enabled", 0) == 1 }
        booleanPair(registry, "developer_options_enabled", "Developer options enabled", "Check Android developer options", FeatureCategory.ADVANCED) { Settings.Global.getInt(context.contentResolver, "development_settings_enabled", 0) == 1 }
        booleanPair(registry, "notification_badging", "Notification badges enabled", "Check notification badging", FeatureCategory.NOTIFICATION) { Settings.Secure.getInt(context.contentResolver, "notification_badging", 1) == 1 }
        booleanPair(registry, "screen_saver_enabled", "Screen saver enabled", "Check Android screensaver setting", FeatureCategory.DISPLAY) { Settings.Secure.getInt(context.contentResolver, "screensaver_enabled", 0) == 1 }
        booleanPair(registry, "wifi_scan_always_available", "Wi-Fi scan always available", "Check Wi-Fi scan-always availability", FeatureCategory.NETWORK) { context.getSystemService(WifiManager::class.java).isScanAlwaysAvailable }
    }

    private fun registerActions(registry: FeatureRegistry) {
        action(registry, "android.email.compose", "Compose email", "Open the mail composer", FeatureCategory.APP,
            listOf(FieldSchema.Text("to", "Recipients (comma separated)"), FieldSchema.Text("subject", "Subject"), FieldSchema.Text("body", "Body", multiline = true)), setOf("email", "mail")) { feature, ctx ->
            val recipients = feature.config.string("to").resolveVariables(ctx.variables).split(',').map(String::trim).filter(String::isNotBlank).toTypedArray()
            launch(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply { if (recipients.isNotEmpty()) putExtra(Intent.EXTRA_EMAIL, recipients); putExtra(Intent.EXTRA_SUBJECT, feature.config.string("subject").resolveVariables(ctx.variables)); putExtra(Intent.EXTRA_TEXT, feature.config.string("body").resolveVariables(ctx.variables)) })
        }
        action(registry, "android.sms.compose", "Compose SMS", "Open the SMS composer", FeatureCategory.APP,
            listOf(FieldSchema.Text("number", "Phone number"), FieldSchema.Text("message", "Message", multiline = true)), setOf("sms", "message")) { feature, ctx ->
            val number = feature.config.string("number").resolveVariables(ctx.variables).trim()
            launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")).putExtra("sms_body", feature.config.string("message").resolveVariables(ctx.variables)))
        }
        action(registry, "android.phone.dial", "Open dialer", "Open the phone dialer without placing a call", FeatureCategory.APP,
            listOf(FieldSchema.Text("number", "Phone number")), setOf("phone", "dial")) { feature, ctx ->
            val number = feature.config.string("number").resolveVariables(ctx.variables).trim()
            launch(if (number.isBlank()) Intent(Intent.ACTION_DIAL) else Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")))
        }
        action(registry, "android.search.web", "Web search", "Open the system web search handler", FeatureCategory.APP,
            listOf(FieldSchema.Text("query", "Search query", true)), setOf("search", "web")) { feature, ctx ->
            launch(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, feature.config.string("query").resolveVariables(ctx.variables)))
        }
        action(registry, "android.map.search", "Map search", "Open a map application with a query", FeatureCategory.APP,
            listOf(FieldSchema.Text("query", "Place / address", true)), setOf("map", "geo", "address")) { feature, ctx ->
            val query = feature.config.string("query").resolveVariables(ctx.variables).trim()
            if (query.isBlank()) ActionExecutionResult(false, message = userText("feature.map_query_empty")) else launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query)}")))
        }
        action(registry, "android.calendar.event.insert", "Create calendar event", "Open the calendar event editor", FeatureCategory.APP,
            listOf(FieldSchema.Text("title", "Title", true), FieldSchema.Text("location", "Location"), FieldSchema.Text("description", "Description", multiline = true), FieldSchema.Number("beginEpochMs", "Start time epoch ms", min = 0.0), FieldSchema.Number("endEpochMs", "End time epoch ms", min = 0.0), FieldSchema.Toggle("allDay", "All day")), setOf("calendar", "event")) { feature, ctx ->
            launch(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).apply { putExtra(CalendarContract.Events.TITLE, feature.config.string("title").resolveVariables(ctx.variables)); putExtra(CalendarContract.Events.EVENT_LOCATION, feature.config.string("location").resolveVariables(ctx.variables)); putExtra(CalendarContract.Events.DESCRIPTION, feature.config.string("description").resolveVariables(ctx.variables)); feature.config["beginEpochMs"].numberOrNull()?.toLong()?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }; feature.config["endEpochMs"].numberOrNull()?.toLong()?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }; putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, feature.config.boolean("allDay")) })
        }
        action(registry, "android.alarm.set", "Set alarm", "Ask the clock app to create an alarm", FeatureCategory.SYSTEM,
            listOf(FieldSchema.Number("hour", "Hour", true, min = 0.0, max = 23.0), FieldSchema.Number("minute", "Minute", true, min = 0.0, max = 59.0), FieldSchema.Text("message", "Label"), FieldSchema.Toggle("skipUi", "Skip confirmation UI")), setOf("alarm", "clock")) { feature, ctx ->
            val hour = feature.config["hour"].numberOrNull()?.toInt() ?: return@action ActionExecutionResult(false, message = userText("feature.alarm_hour_missing"))
            val minute = feature.config["minute"].numberOrNull()?.toInt() ?: 0
            launch(Intent(AlarmClock.ACTION_SET_ALARM).apply { putExtra(AlarmClock.EXTRA_HOUR, hour); putExtra(AlarmClock.EXTRA_MINUTES, minute); putExtra(AlarmClock.EXTRA_MESSAGE, feature.config.string("message").resolveVariables(ctx.variables)); putExtra(AlarmClock.EXTRA_SKIP_UI, feature.config.boolean("skipUi")) })
        }
        action(registry, "android.alarm.show", "Show alarms", "Open the clock alarm list", FeatureCategory.SYSTEM, emptyList(), setOf("alarm", "clock")) { _, _ -> launch(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
        action(registry, "android.timer.set", "Set timer", "Ask the clock app to create a timer", FeatureCategory.SYSTEM,
            listOf(FieldSchema.Number("seconds", "Seconds", true, min = 1.0, max = 86_400.0), FieldSchema.Text("message", "Label"), FieldSchema.Toggle("skipUi", "Skip confirmation UI")), setOf("timer", "countdown")) { feature, ctx ->
            val seconds = feature.config["seconds"].numberOrNull()?.toInt()?.coerceIn(1, 86_400) ?: return@action ActionExecutionResult(false, message = userText("feature.timer_duration_missing"))
            launch(Intent(AlarmClock.ACTION_SET_TIMER).apply { putExtra(AlarmClock.EXTRA_LENGTH, seconds); putExtra(AlarmClock.EXTRA_MESSAGE, feature.config.string("message").resolveVariables(ctx.variables)); putExtra(AlarmClock.EXTRA_SKIP_UI, feature.config.boolean("skipUi")) })
        }
        action(registry, "android.camera.still.open", "Open still camera", "Open the camera in still-image mode", FeatureCategory.APP, emptyList(), setOf("camera", "photo")) { _, _ -> launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) }
        action(registry, "android.camera.video.open", "Open video camera", "Open the camera in video mode", FeatureCategory.APP, emptyList(), setOf("camera", "video")) { _, _ -> launch(Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA)) }
        action(registry, "android.app.market.open", "Open app store listing", "Open an application's app-store listing", FeatureCategory.APP, listOf(FieldSchema.AppPicker("package", "App / package", true)), setOf("market", "store", "app")) { feature, ctx ->
            val pkg = feature.config.string("package").resolveVariables(ctx.variables).trim(); if (!isValidPackageName(pkg)) ActionExecutionResult(false, message = userText("feature.invalid_package_name")) else launch(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
        }
        action(registry, "android.app.uninstall.request", "Request app uninstall", "Open Android's normal uninstall confirmation UI", FeatureCategory.APP, listOf(FieldSchema.AppPicker("package", "App / package", true)), setOf("uninstall", "remove")) { feature, ctx ->
            val pkg = feature.config.string("package").resolveVariables(ctx.variables).trim(); if (!isValidPackageName(pkg)) ActionExecutionResult(false, message = userText("feature.invalid_package_name")) else launch(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
        }
        action(registry, "android.input_method.picker", "Show keyboard picker", "Show Android's input-method picker", FeatureCategory.SYSTEM, emptyList(), setOf("keyboard", "ime", "picker")) { _, _ ->
            runCatching { context.getSystemService(InputMethodManager::class.java).showInputMethodPicker(); ActionExecutionResult(true) }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }
        action(registry, "android.wallpaper.picker", "Open wallpaper picker", "Open Android's wallpaper selection UI", FeatureCategory.DISPLAY, emptyList(), setOf("wallpaper", "background")) { _, _ -> launch(Intent(Intent.ACTION_SET_WALLPAPER)) }
        action(registry, "android.ringtone.picker", "Open ringtone picker", "Open Android's ringtone selection UI", FeatureCategory.AUDIO, emptyList(), setOf("ringtone", "sound")) { _, _ -> launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER)) }
        action(registry, "android.voice_command.open", "Open voice command", "Open the system voice-command handler", FeatureCategory.APP, emptyList(), setOf("voice", "assistant")) { _, _ -> launch(Intent(Intent.ACTION_VOICE_COMMAND)) }
        action(registry, "android.contacts.open", "Open contacts", "Open the system contacts UI", FeatureCategory.APP, emptyList(), setOf("contacts", "people")) { _, _ -> launch(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)) }
    }

    private fun action(registry: FeatureRegistry, typeId: String, title: String, description: String, category: FeatureCategory, fields: List<FieldSchema>, keywords: Set<String>, execute: suspend (com.yagay.yauto.core.model.FeatureRef, FeatureExecutionContext) -> ActionExecutionResult) {
        registry.registerAction(FeatureDescriptor(FeatureId(typeId), FeatureKind.ACTION, title, description, category, fields = fields, keywords = keywords, ownerPackId = id), ActionExecutor(execute))
    }

    private fun booleanPair(registry: FeatureRegistry, key: String, title: String, description: String, category: FeatureCategory, query: () -> Boolean) {
        val fields = listOf(FieldSchema.Toggle("value", "Enabled / true"))
        val evaluator = ConditionEvaluator { feature, _ -> runCatching(query).getOrDefault(false) == feature.config.boolean("value", true) }
        val state = FeatureDescriptor(FeatureId("android.state.$key"), FeatureKind.STATE, title, description, category, fields = fields, ownerPackId = id)
        registry.registerState(state, evaluator)
        registry.registerCondition(state.copy(id = FeatureId("android.condition.$key"), kind = FeatureKind.CONDITION), evaluator)
    }

    private fun launch(intent: Intent): ActionExecutionResult = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ActionExecutionResult(true) }
        .getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
}
