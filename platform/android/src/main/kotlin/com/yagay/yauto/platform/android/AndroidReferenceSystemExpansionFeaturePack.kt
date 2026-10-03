package com.yagay.yauto.platform.android

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.provider.CallLog
import android.provider.Settings
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

/**
 * Stable reference-APK gaps shared by MacroDroid, ShortX and Tasker.
 *
 * Public-framework actions stay local; protected settings are routed through the existing
 * privileged-shell broker so Root/Shizuku selection and diagnostics remain centralized.
 */
class AndroidReferenceSystemExpansionFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.reference_system_expansion"
    private val context = context.applicationContext
    private val audioFocus = AudioFocusController(this.context.getSystemService(AudioManager::class.java))

    override fun install(registry: FeatureRegistry) {
        registerOpenCallLog(registry)
        registerOpenAppNotificationSettings(registry)
        privilegedToggle(registry, "android.car_mode.set", "Set car mode", "Enable or disable Android car mode", FeatureCategory.SYSTEM, ::carModeCommand)
        privilegedAction(registry, "android.display.dream.start", "Start screen saver", "Request Android's dream / screen-saver service to start", FeatureCategory.DISPLAY, emptyList(), setOf("dream", "daydream", "screen saver")) { _, ctx ->
            executeShell("android.display.dream.start", "cmd dreams start-dreaming", ctx)
        }
        privilegedAction(registry, "android.display.dream.stop", "Stop screen saver", "Stop the active Android dream / screen saver", FeatureCategory.DISPLAY, emptyList(), setOf("dream", "daydream", "screen saver")) { _, ctx ->
            executeShell("android.display.dream.stop", "cmd dreams stop-dreaming", ctx)
        }
        privilegedToggle(registry, "android.display.color_inversion.set", "Set color inversion", "Enable or disable Android accessibility display color inversion", FeatureCategory.DISPLAY, ::colorInversionCommand)
        privilegedToggle(registry, "android.display.ambient_display.set", "Set always-on ambient display", "Enable or disable the Android always-on ambient-display setting", FeatureCategory.DISPLAY, ::ambientDisplayCommand)
        privilegedToggle(registry, "android.notification.heads_up.set", "Set heads-up notifications", "Enable or disable Android heads-up notification presentation", FeatureCategory.NOTIFICATION, ::headsUpCommand)
        privilegedToggle(registry, "android.network.data_roaming.set", "Set data roaming", "Enable or disable the Android global data-roaming setting", FeatureCategory.NETWORK, ::dataRoamingCommand)

        registerAudioFocusRequest(registry)
        registerAudioFocusAbandon(registry)

        booleanPair(registry, "car_mode", "Car mode", "Check whether Android is currently in car UI mode", FeatureCategory.SYSTEM) {
            context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_CAR
        }
        booleanPair(registry, "ambient_display", "Always-on ambient display", "Check Android's always-on ambient-display setting", FeatureCategory.DISPLAY) {
            Settings.Secure.getInt(context.contentResolver, "doze_always_on", 0) == 1
        }
        booleanPair(registry, "color_inversion", "Color inversion", "Check Android accessibility display color inversion", FeatureCategory.DISPLAY) {
            Settings.Secure.getInt(context.contentResolver, "accessibility_display_inversion_enabled", 0) == 1
        }
        booleanPair(registry, "heads_up_notifications", "Heads-up notifications", "Check Android's heads-up notification setting", FeatureCategory.NOTIFICATION) {
            Settings.Global.getInt(context.contentResolver, "heads_up_notifications_enabled", 1) == 1
        }
        booleanPair(registry, "data_roaming_setting", "Data roaming setting", "Check Android's global data-roaming setting", FeatureCategory.NETWORK) {
            Settings.Global.getInt(context.contentResolver, Settings.Global.DATA_ROAMING, 0) == 1
        }
        booleanPair(registry, "developer_options", "Developer options", "Check whether Android developer options are enabled", FeatureCategory.SYSTEM) {
            Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        }
        booleanPair(registry, "adb_enabled", "ADB enabled", "Check whether Android debugging is enabled", FeatureCategory.SYSTEM) {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        }
        booleanPair(registry, "audio_focus_held", "Audio focus held by YAuto", "Check whether YAuto's most recent audio-focus request currently has focus", FeatureCategory.AUDIO) {
            audioFocus.hasFocus
        }
    }

    private fun registerOpenCallLog(registry: FeatureRegistry) {
        publicAction(
            registry,
            "android.call_log.open",
            "Open call log",
            "Open the system call-history UI",
            FeatureCategory.APP,
            emptyList(),
            setOf("call log", "call history", "dialer"),
        ) { _, _ ->
            launch(Intent(Intent.ACTION_VIEW, CallLog.Calls.CONTENT_URI))
        }
    }

    private fun registerOpenAppNotificationSettings(registry: FeatureRegistry) {
        publicAction(
            registry,
            "android.app.notification_settings.open",
            "Open app notification settings",
            "Open Android notification settings for a selected application",
            FeatureCategory.NOTIFICATION,
            listOf(FieldSchema.AppPicker("package", "App / package", true)),
            setOf("app notification", "settings", "channel", "permission"),
        ) { feature, ctx ->
            val packageName = feature.config.string("package").resolveVariables(ctx.variables).trim()
            if (!PACKAGE_NAME.matches(packageName)) {
                return@publicAction ActionExecutionResult(false, message = userText("feature.invalid_package_name"))
            }
            launch(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }
    }

    private fun registerAudioFocusRequest(registry: FeatureRegistry) {
        publicAction(
            registry,
            "android.audio.focus.request",
            "Request audio focus",
            "Request Android audio focus for YAuto using a selected gain type",
            FeatureCategory.AUDIO,
            listOf(FieldSchema.Choice("gain", "Focus gain", true, listOf("gain", "transient", "may_duck", "exclusive"))),
            setOf("audio focus", "media", "duck", "exclusive"),
        ) { feature, _ ->
            val granted = audioFocus.request(feature.config.string("gain", "gain"))
            ActionExecutionResult(granted, ConfigValue.BooleanValue(granted), if (granted) null else userText("feature.operation_failed", feature.typeId))
        }
    }

    private fun registerAudioFocusAbandon(registry: FeatureRegistry) {
        publicAction(
            registry,
            "android.audio.focus.abandon",
            "Abandon audio focus",
            "Release YAuto's active Android audio-focus request",
            FeatureCategory.AUDIO,
            emptyList(),
            setOf("audio focus", "release", "media"),
        ) { feature, _ ->
            val released = audioFocus.abandon()
            ActionExecutionResult(released, ConfigValue.BooleanValue(released), if (released) null else userText("feature.operation_failed", feature.typeId))
        }
    }

    private fun privilegedToggle(
        registry: FeatureRegistry,
        typeId: String,
        title: String,
        description: String,
        category: FeatureCategory,
        command: (Boolean) -> String,
    ) = privilegedAction(
        registry,
        typeId,
        title,
        description,
        category,
        listOf(FieldSchema.Toggle("enabled", "Enabled")),
        setOf("reference", "MacroDroid", "ShortX", "Tasker"),
    ) { feature, ctx ->
        executeShell(typeId, command(feature.config.boolean("enabled", true)), ctx)
    }

    private fun privilegedAction(
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
            FeatureDescriptor(
                FeatureId(typeId),
                FeatureKind.ACTION,
                title,
                description,
                category,
                fields = fields,
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                keywords = keywords,
                ownerPackId = id,
            ),
            ActionExecutor(execute),
        )
    }

    private fun publicAction(
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
            FeatureDescriptor(
                FeatureId(typeId),
                FeatureKind.ACTION,
                title,
                description,
                category,
                fields = fields,
                keywords = keywords,
                ownerPackId = id,
            ),
            ActionExecutor(execute),
        )
    }

    private suspend fun executeShell(
        operationId: String,
        command: String,
        ctx: FeatureExecutionContext,
    ): ActionExecutionResult {
        val result = ctx.capabilities.execute(
            CapabilityRequest(
                capability = CapabilityIds.PRIVILEGED_SHELL,
                operationId = operationId,
                payload = mapOf("command" to ConfigValue.StringValue(command)),
            )
        )
        return ActionExecutionResult(result.success, result.value, result.message)
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
        val evaluator = ConditionEvaluator { feature, _ ->
            runCatching(query).getOrDefault(false) == feature.config.boolean("value", true)
        }
        val state = FeatureDescriptor(
            FeatureId("android.state.$key"),
            FeatureKind.STATE,
            title,
            description,
            category,
            fields = fields,
            keywords = setOf("reference", "MacroDroid", "ShortX", "Tasker"),
            ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(
            state.copy(id = FeatureId("android.condition.$key"), kind = FeatureKind.CONDITION),
            evaluator,
        )
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

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    }
}

internal class AudioFocusController(private val audio: AudioManager) {
    @Volatile var hasFocus: Boolean = false
        private set

    private var request: AudioFocusRequest? = null
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        hasFocus = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> true
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> false
            else -> hasFocus
        }
    }

    fun request(gain: String): Boolean {
        val focusGain = when (gain) {
            "transient" -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            "may_duck" -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            "exclusive" -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            else -> AudioManager.AUDIOFOCUS_GAIN
        }
        abandon()
        val next = AudioFocusRequest.Builder(focusGain)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(listener)
            .build()
        val granted = runCatching {
            audio.requestAudioFocus(next) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }.getOrDefault(false)
        request = if (granted) next else null
        hasFocus = granted
        return granted
    }

    fun abandon(): Boolean {
        val current = request
        if (current == null) {
            hasFocus = false
            return true
        }
        val released = runCatching {
            audio.abandonAudioFocusRequest(current) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }.getOrDefault(false)
        request = null
        hasFocus = false
        return released
    }
}

internal fun carModeCommand(enabled: Boolean): String = "cmd uimode car ${if (enabled) "yes" else "no"}"
internal fun colorInversionCommand(enabled: Boolean): String =
    "settings put secure accessibility_display_inversion_enabled ${if (enabled) 1 else 0}"
internal fun ambientDisplayCommand(enabled: Boolean): String =
    "settings put secure doze_always_on ${if (enabled) 1 else 0}"
internal fun headsUpCommand(enabled: Boolean): String =
    "settings put global heads_up_notifications_enabled ${if (enabled) 1 else 0}"
internal fun dataRoamingCommand(enabled: Boolean): String =
    "settings put global data_roaming ${if (enabled) 1 else 0}"
