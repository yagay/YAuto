package com.yagay.yauto.platform.android

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.UserManager
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.CaptioningManager
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

/**
 * Non-duplicate native gaps retained after comparing the reference APKs with YAuto main.
 * 10 actions + 20 state/condition pairs = 50 user-facing features.
 */
class AndroidReferenceCompletionFeaturePack(context: Context) : FeaturePack {
    override val id = "android.reference_completion"
    private val context = context.applicationContext

    override fun install(registry: FeatureRegistry) {
        registerActions(registry)

        booleanPair(registry, "managed_profile", "Managed profile", "Check whether YAuto runs inside a managed profile", FeatureCategory.DEVICE) {
            context.getSystemService(UserManager::class.java).isManagedProfile
        }
        booleanPair(registry, "accessibility_enabled", "Accessibility enabled", "Check whether accessibility is globally enabled", FeatureCategory.UI_AUTOMATION) {
            context.getSystemService(AccessibilityManager::class.java).isEnabled
        }
        booleanPair(registry, "touch_exploration_enabled", "Touch exploration enabled", "Check accessibility touch exploration", FeatureCategory.UI_AUTOMATION) {
            context.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled
        }
        booleanPair(registry, "captioning_enabled", "System captions enabled", "Check Android captioning state", FeatureCategory.SYSTEM) {
            context.getSystemService(CaptioningManager::class.java).isEnabled
        }
        booleanPair(registry, "notification_badging", "Notification badges enabled", "Check notification badging", FeatureCategory.NOTIFICATION) {
            Settings.Secure.getInt(context.contentResolver, "notification_badging", 1) == 1
        }
        booleanPair(registry, "screen_saver_enabled", "Screen saver enabled", "Check Android screensaver setting", FeatureCategory.DISPLAY) {
            Settings.Secure.getInt(context.contentResolver, "screensaver_enabled", 0) == 1
        }
        booleanPair(registry, "wifi_scan_always_available", "Wi-Fi scan always available", "Check Wi-Fi scan-always availability", FeatureCategory.NETWORK) {
            context.getSystemService(WifiManager::class.java).isScanAlwaysAvailable
        }

        hardwarePair(registry, "has_camera", "Camera hardware available", "Check whether Android reports camera hardware", FeatureCategory.DEVICE, "android.hardware.camera.any")
        hardwarePair(registry, "has_camera_flash", "Camera flash available", "Check whether Android reports a camera flash", FeatureCategory.DEVICE, "android.hardware.camera.flash")
        hardwarePair(registry, "has_microphone", "Microphone hardware available", "Check whether Android reports microphone hardware", FeatureCategory.DEVICE, "android.hardware.microphone")
        hardwarePair(registry, "has_nfc", "NFC hardware available", "Check whether Android reports NFC hardware", FeatureCategory.DEVICE, "android.hardware.nfc")
        hardwarePair(registry, "has_nfc_hce", "NFC host card emulation available", "Check whether Android reports NFC host-card-emulation support", FeatureCategory.DEVICE, "android.hardware.nfc.hce")
        hardwarePair(registry, "has_telephony", "Telephony hardware available", "Check whether Android reports telephony hardware", FeatureCategory.DEVICE, "android.hardware.telephony")
        hardwarePair(registry, "has_gps", "GPS hardware available", "Check whether Android reports GPS location hardware", FeatureCategory.DEVICE, "android.hardware.location.gps")
        hardwarePair(registry, "has_usb_host", "USB host available", "Check whether Android reports USB host support", FeatureCategory.DEVICE, "android.hardware.usb.host")
        hardwarePair(registry, "has_bluetooth_le", "Bluetooth LE available", "Check whether Android reports Bluetooth Low Energy support", FeatureCategory.NETWORK, "android.hardware.bluetooth_le")
        hardwarePair(registry, "has_ethernet", "Ethernet available", "Check whether Android reports Ethernet support", FeatureCategory.NETWORK, "android.hardware.ethernet")
        hardwarePair(registry, "supports_picture_in_picture", "Picture-in-picture supported", "Check whether Android reports picture-in-picture support", FeatureCategory.DISPLAY, "android.software.picture_in_picture")
        hardwarePair(registry, "has_fingerprint", "Fingerprint hardware available", "Check whether Android reports fingerprint hardware", FeatureCategory.DEVICE, "android.hardware.fingerprint")
        hardwarePair(registry, "supports_freeform_window", "Freeform windows supported", "Check whether Android reports freeform-window-management support", FeatureCategory.DISPLAY, "android.software.freeform_window_management")
    }

    private fun registerActions(registry: FeatureRegistry) {
        action(
            registry,
            "android.search.web",
            "Web search",
            "Open the system web search handler",
            FeatureCategory.APP,
            listOf(FieldSchema.Text("query", "Search query", true)),
            setOf("search", "web"),
        ) { feature, ctx ->
            launch(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, feature.config.string("query").resolveVariables(ctx.variables)))
        }

        action(registry, "android.alarm.show", "Show alarms", "Open the clock alarm list", FeatureCategory.SYSTEM, emptyList(), setOf("alarm", "clock")) { _, _ ->
            launch(Intent("android.intent.action.SHOW_ALARMS"))
        }
        action(registry, "android.camera.still.open", "Open still camera", "Open the camera in still-image mode", FeatureCategory.APP, emptyList(), setOf("camera", "photo")) { _, _ ->
            launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        }
        action(registry, "android.camera.video.open", "Open video camera", "Open the camera in video mode", FeatureCategory.APP, emptyList(), setOf("camera", "video")) { _, _ ->
            launch(Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA))
        }
        action(registry, "android.app.market.open", "Open app store listing", "Open an application's app-store listing", FeatureCategory.APP, listOf(FieldSchema.AppPicker("package", "App / package", true)), setOf("market", "store", "app")) { feature, ctx ->
            val pkg = feature.config.string("package").resolveVariables(ctx.variables).trim()
            if (!isValidPackageName(pkg)) ActionExecutionResult(false, message = userText("feature.invalid_package_name"))
            else launch(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
        }
        action(registry, "android.app.uninstall.request", "Request app uninstall", "Open Android's normal uninstall confirmation UI", FeatureCategory.APP, listOf(FieldSchema.AppPicker("package", "App / package", true)), setOf("uninstall", "remove")) { feature, ctx ->
            val pkg = feature.config.string("package").resolveVariables(ctx.variables).trim()
            if (!isValidPackageName(pkg)) ActionExecutionResult(false, message = userText("feature.invalid_package_name"))
            else launch(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
        }
        action(registry, "android.wallpaper.picker", "Open wallpaper picker", "Open Android's wallpaper selection UI", FeatureCategory.DISPLAY, emptyList(), setOf("wallpaper", "background")) { _, _ ->
            launch(Intent(Intent.ACTION_SET_WALLPAPER))
        }
        action(registry, "android.ringtone.picker", "Open ringtone picker", "Open Android's ringtone selection UI", FeatureCategory.AUDIO, emptyList(), setOf("ringtone", "sound")) { _, _ ->
            launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER))
        }
        action(registry, "android.voice_command.open", "Open voice command", "Open the system voice-command handler", FeatureCategory.APP, emptyList(), setOf("voice", "assistant")) { _, _ ->
            launch(Intent(Intent.ACTION_VOICE_COMMAND))
        }
        action(registry, "android.contacts.open", "Open contacts", "Open the system contacts UI", FeatureCategory.APP, emptyList(), setOf("contacts", "people")) { _, _ ->
            launch(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI))
        }
    }

    private fun action(
        registry: FeatureRegistry,
        typeId: String,
        title: String,
        description: String,
        category: FeatureCategory,
        fields: List<FieldSchema>,
        keywords: Set<String>,
        execute: suspend (com.yagay.yauto.core.model.FeatureRef, FeatureExecutionContext) -> ActionExecutionResult,
    ) {
        registry.registerAction(
            FeatureDescriptor(FeatureId(typeId), FeatureKind.ACTION, title, description, category, fields = fields, keywords = keywords, ownerPackId = id),
            ActionExecutor(execute),
        )
    }

    private fun hardwarePair(
        registry: FeatureRegistry,
        key: String,
        title: String,
        description: String,
        category: FeatureCategory,
        featureName: String,
    ) = booleanPair(registry, key, title, description, category) {
        context.packageManager.hasSystemFeature(featureName)
    }

    private fun booleanPair(
        registry: FeatureRegistry,
        key: String,
        title: String,
        description: String,
        category: FeatureCategory,
        query: () -> Boolean,
    ) {
        val fields = listOf(FieldSchema.Toggle("value", "Enabled / true"))
        val evaluator = ConditionEvaluator { feature, _ -> runCatching(query).getOrDefault(false) == feature.config.boolean("value", true) }
        val state = FeatureDescriptor(FeatureId("android.state.$key"), FeatureKind.STATE, title, description, category, fields = fields, ownerPackId = id)
        registry.registerState(state, evaluator)
        registry.registerCondition(state.copy(id = FeatureId("android.condition.$key"), kind = FeatureKind.CONDITION), evaluator)
    }

    private fun launch(intent: Intent): ActionExecutionResult = runCatching {
        val launch = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch.resolveActivity(context.packageManager) == null) {
            return@runCatching ActionExecutionResult(false, message = userText("feature.no_compatible_app"))
        }
        context.startActivity(launch)
        ActionExecutionResult(true)
    }.getOrElse {
        ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName))
    }
}
