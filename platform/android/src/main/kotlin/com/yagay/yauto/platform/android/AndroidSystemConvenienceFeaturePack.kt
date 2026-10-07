package com.yagay.yauto.platform.android

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.Settings
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

/** High-frequency Android convenience controls and live states backed by public framework APIs. */
class AndroidSystemConvenienceFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.system_convenience"
    private val context = context.applicationContext

    override fun install(registry: FeatureRegistry) {
        registerMasterSync(registry)
        booleanPair(registry, "master_sync", "Master sync enabled", "Check Android's global account synchronization switch", FeatureCategory.SYSTEM) {
            ContentResolver.getMasterSyncAutomatically()
        }
        booleanPair(registry, "device_locked", "Device locked", "Check whether Android currently considers the device locked", FeatureCategory.DEVICE) {
            context.getSystemService(KeyguardManager::class.java).isDeviceLocked
        }
        registerDndState(registry, FeatureKind.STATE, "android.state.dnd_filter")
        registerDndState(registry, FeatureKind.CONDITION, "android.condition.dnd_filter")
        registerLaunchHome(registry)
        registerOpenSettings(registry)
    }

    private fun registerMasterSync(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.sync.master.set"), FeatureKind.ACTION,
                "Set master sync", "Enable or disable Android's global account synchronization switch",
                FeatureCategory.SYSTEM,
                fields = listOf(FieldSchema.Toggle("enabled", "Enabled")),
                keywords = setOf("sync", "account", "master sync", "autosync"), ownerPackId = id,
            )
        ) { feature, _ ->
            runCatching {
                val enabled = feature.config.boolean("enabled", true)
                ContentResolver.setMasterSyncAutomatically(enabled)
                ActionExecutionResult(true, ConfigValue.BooleanValue(enabled))
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }
    }

    private fun registerDndState(registry: FeatureRegistry, kind: FeatureKind, typeId: String) {
        val descriptor = FeatureDescriptor(
            FeatureId(typeId), kind,
            "Do Not Disturb filter", "Match Android's current interruption filter reported by NotificationManager",
            FeatureCategory.NOTIFICATION,
            fields = listOf(FieldSchema.Choice("filter", "Interruption filter", true, listOf("all", "priority", "alarms", "none", "unknown"))),
            keywords = setOf("dnd", "do not disturb", "interruption", "priority", "alarms"), ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, _ ->
            val actual = when (context.getSystemService(NotificationManager::class.java).currentInterruptionFilter) {
                NotificationManager.INTERRUPTION_FILTER_ALL -> "all"
                NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
                NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
                NotificationManager.INTERRUPTION_FILTER_NONE -> "none"
                else -> "unknown"
            }
            actual == feature.config.string("filter", "all")
        }
        register(registry, descriptor, evaluator)
    }

    private fun registerLaunchHome(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.home.launch"), FeatureKind.ACTION,
                "Go to home screen", "Open the default Android home screen without requiring Accessibility",
                FeatureCategory.UI_AUTOMATION,
                keywords = setOf("home", "launcher", "desktop", "go home"), ownerPackId = id,
            )
        ) { _, _ ->
            runCatching {
                context.startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                ActionExecutionResult(true)
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }
    }

    private fun registerOpenSettings(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.settings.page.open"), FeatureKind.ACTION,
                "Open Android settings page", "Open a common Android system settings page, optionally scoped to YAuto when supported",
                FeatureCategory.SYSTEM,
                fields = listOf(FieldSchema.Choice("page", "Settings page", true, listOf(
                    "general", "wireless", "wifi", "bluetooth", "vpn", "data_roaming", "location", "nfc", "nfc_payment",
                    "display", "sound", "storage", "security", "privacy", "developer", "date_time", "input_method",
                    "accessibility", "captioning", "notification_listener", "notifications", "app_notification",
                    "manage_apps", "default_apps", "battery_saver", "battery_optimization", "usage_access",
                    "overlay", "write_settings", "unknown_sources", "all_files_access", "dnd", "print",
                ))),
                keywords = setOf("settings", "wifi settings", "accessibility", "overlay", "usage access", "dnd", "developer", "storage", "notifications"),
                aliases = setOf("android.settings.open"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            val page = feature.config.string("page", "general")
            val spec = settingsPageIntent(page, context.packageName)
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.settings_page_invalid"))
            runCatching {
                context.startActivity(spec.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionExecutionResult(true)
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }
    }

    private fun booleanPair(
        registry: FeatureRegistry,
        key: String,
        title: String,
        description: String,
        category: FeatureCategory,
        query: () -> Boolean,
    ) {
        for (kind in listOf(FeatureKind.STATE, FeatureKind.CONDITION)) {
            val typeId = "android.${if (kind == FeatureKind.STATE) "state" else "condition"}.$key"
            val descriptor = FeatureDescriptor(
                FeatureId(typeId), kind, title, description, category,
                fields = listOf(FieldSchema.Toggle("value", "Enabled / true")), ownerPackId = id,
            )
            val evaluator = ConditionEvaluator { feature, _ -> runCatching { query() }.getOrDefault(false) == feature.config.boolean("value", true) }
            register(registry, descriptor, evaluator)
        }
    }

    private fun register(registry: FeatureRegistry, descriptor: FeatureDescriptor, evaluator: ConditionEvaluator) {
        if (descriptor.kind == FeatureKind.STATE) registry.registerState(descriptor, evaluator) else registry.registerCondition(descriptor, evaluator)
    }
}

internal fun settingsPageIntent(page: String, packageName: String): Intent? {
    val packageUri = Uri.parse("package:$packageName")
    return when (page) {
        "general", "main" -> Intent(Settings.ACTION_SETTINGS)
        "wireless" -> Intent("android.settings.WIRELESS_SETTINGS")
        "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
        "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        "vpn" -> Intent("android.settings.VPN_SETTINGS")
        "data_roaming" -> Intent("android.settings.DATA_ROAMING_SETTINGS")
        "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        "nfc" -> Intent(Settings.ACTION_NFC_SETTINGS)
        "nfc_payment" -> Intent("android.settings.NFC_PAYMENT_SETTINGS")
        "display" -> Intent("android.settings.DISPLAY_SETTINGS")
        "sound" -> Intent("android.settings.SOUND_SETTINGS")
        "storage" -> Intent("android.settings.INTERNAL_STORAGE_SETTINGS")
        "security" -> Intent("android.settings.SECURITY_SETTINGS")
        "privacy" -> Intent("android.settings.PRIVACY_SETTINGS")
        "developer" -> Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
        "date_time" -> Intent("android.settings.DATE_SETTINGS")
        "input_method" -> Intent("android.settings.INPUT_METHOD_SETTINGS")
        "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        "captioning" -> Intent("android.settings.CAPTIONING_SETTINGS")
        "notification_listener" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        "notifications" -> Intent("android.settings.ALL_APPS_NOTIFICATION_SETTINGS")
        "app_notification" -> Intent("android.settings.APP_NOTIFICATION_SETTINGS").putExtra("android.provider.extra.APP_PACKAGE", packageName)
        "manage_apps", "apps" -> Intent("android.settings.MANAGE_APPLICATIONS_SETTINGS")
        "default_apps" -> Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS")
        "battery_saver" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        "battery_optimization" -> Intent("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS")
        "usage_access" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, packageUri)
        "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri)
        "write_settings" -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, packageUri)
        "unknown_sources" -> Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES", packageUri)
        "all_files_access" -> Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", packageUri)
        "dnd" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        "print" -> Intent("android.settings.ACTION_PRINT_SETTINGS")
        else -> null
    }
}
