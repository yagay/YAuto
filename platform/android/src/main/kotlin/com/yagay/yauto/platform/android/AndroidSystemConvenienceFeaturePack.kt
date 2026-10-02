package com.yagay.yauto.platform.android

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
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
        booleanPair(registry, "music_active", "Music playback active", "Check whether Android AudioManager currently reports active music playback", FeatureCategory.AUDIO) {
            context.getSystemService(AudioManager::class.java).isMusicActive
        }
        booleanPair(registry, "network_metered", "Active network metered", "Check whether Android marks the active network as metered", FeatureCategory.NETWORK) {
            context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
        }
        registerDataSaverState(registry, FeatureKind.STATE, "android.state.data_saver_status")
        registerDataSaverState(registry, FeatureKind.CONDITION, "android.condition.data_saver_status")
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

    private fun registerDataSaverState(registry: FeatureRegistry, kind: FeatureKind, typeId: String) {
        val descriptor = FeatureDescriptor(
            FeatureId(typeId), kind,
            "Data saver status", "Match Android's background-data restriction status for YAuto on the active system",
            FeatureCategory.NETWORK,
            fields = listOf(FieldSchema.Choice("status", "Data saver status", true, listOf("disabled", "enabled", "whitelisted"))),
            keywords = setOf("data saver", "background data", "metered", "network restriction"), ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, _ ->
            val actual = when (context.getSystemService(ConnectivityManager::class.java).restrictBackgroundStatus) {
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED -> "disabled"
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "enabled"
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "whitelisted"
                else -> "unknown"
            }
            actual == feature.config.string("status", "disabled")
        }
        register(registry, descriptor, evaluator)
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
                    "general", "wifi", "bluetooth", "location", "nfc", "accessibility", "notification_listener",
                    "battery_saver", "usage_access", "overlay", "write_settings", "dnd",
                ))),
                keywords = setOf("settings", "wifi settings", "accessibility", "overlay", "usage access", "dnd"), ownerPackId = id,
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
        "general" -> Intent(Settings.ACTION_SETTINGS)
        "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
        "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        "nfc" -> Intent(Settings.ACTION_NFC_SETTINGS)
        "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        "notification_listener" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        "battery_saver" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        "usage_access" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, packageUri)
        "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri)
        "write_settings" -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, packageUri)
        "dnd" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        else -> null
    }
}
