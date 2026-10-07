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
        privilegedModeToggle(registry, "android.car_mode.set", "Set car mode", "Enable, disable, or toggle Android car mode", FeatureCategory.SYSTEM, ::carModeEnabled, ::carModeCommand)
        privilegedAction(registry, "android.display.dream.start", "Start screen saver", "Request Android's dream / screen-saver service to start", FeatureCategory.DISPLAY, emptyList(), setOf("dream", "daydream", "screen saver")) { _, ctx ->
            executeShell("android.display.dream.start", "cmd dreams start-dreaming", ctx)
        }
        privilegedAction(registry, "android.display.dream.stop", "Stop screen saver", "Stop the active Android dream / screen saver", FeatureCategory.DISPLAY, emptyList(), setOf("dream", "daydream", "screen saver")) { _, ctx ->
            executeShell("android.display.dream.stop", "cmd dreams stop-dreaming", ctx)
        }
        privilegedModeToggle(registry, "android.display.color_inversion.set", "Set color inversion", "Enable, disable, or toggle Android accessibility display color inversion", FeatureCategory.DISPLAY, ::colorInversionEnabled, ::colorInversionCommand)
        registerAmbientDisplay(registry)
        privilegedModeToggle(registry, "android.notification.heads_up.set", "Set heads-up notifications", "Enable, disable, or toggle Android heads-up notification presentation", FeatureCategory.NOTIFICATION, ::headsUpEnabled, ::headsUpCommand)
        privilegedModeToggle(registry, "android.network.data_roaming.set", "Set data roaming", "Enable, disable, or toggle the Android global data-roaming setting", FeatureCategory.NETWORK, ::dataRoamingEnabled, ::dataRoamingCommand)

        registerAudioFocusRequest(registry)
        registerAudioFocusAbandon(registry)
        registerAudioFocusEvents(registry)

        booleanPair(registry, "car_mode", "Car mode", "Check whether Android is currently in car UI mode", FeatureCategory.SYSTEM) {
            carModeEnabled()
        }
        booleanPair(registry, "ambient_display", "Always-on ambient display", "Check Android's always-on ambient-display setting", FeatureCategory.DISPLAY) {
            ambientDisplayEnabled("always_on")
        }
        booleanPair(registry, "color_inversion", "Color inversion", "Check Android accessibility display color inversion", FeatureCategory.DISPLAY) {
            colorInversionEnabled()
        }
        booleanPair(registry, "heads_up_notifications", "Heads-up notifications", "Check Android's heads-up notification setting", FeatureCategory.NOTIFICATION) {
            headsUpEnabled()
        }
        booleanPair(registry, "data_roaming_setting", "Data roaming setting", "Check Android's global data-roaming setting", FeatureCategory.NETWORK) {
            dataRoamingEnabled()
        }
        booleanPair(registry, "audio_focus_held", "Audio focus held by YAuto", "Check whether YAuto's most recent audio-focus request currently has focus", FeatureCategory.AUDIO) {
            audioFocus.hasFocus
        }
    }


    private fun registerAudioFocusEvents(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.audio_focus_changed"), FeatureKind.EVENT,
                "Audio focus changed",
                "Run when YAuto's requested Android audio focus changes",
                FeatureCategory.AUDIO,
                fields = listOf(
                    FieldSchema.Choice(
                        "change", "Focus change", true,
                        listOf("any", "gain", "loss_any", "loss", "loss_transient", "loss_can_duck")
                    )
                ),
                keywords = setOf("audio focus", "gain", "loss", "duck", "shortx"),
                ownerPackId = id,
                aliases = setOf(
                    "android.event.audio_focus_gain",
                    "android.event.audio_focus_lost",
                ),
                aliasConfigDefaults = mapOf(
                    "android.event.audio_focus_gain" to mapOf("change" to ConfigValue.StringValue("gain")),
                    "android.event.audio_focus_lost" to mapOf("change" to ConfigValue.StringValue("loss_any")),
                ),
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.audio_focus_changed") return@registerEvent false
            val wanted = feature.config.string("change", "any")
            val actual = ctx.event.payload.string("change")
            wanted == "any" ||
                (wanted == "loss_any" && actual in setOf("loss", "loss_transient", "loss_can_duck")) ||
                actual == wanted
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

    private fun privilegedModeToggle(
        registry: FeatureRegistry,
        typeId: String,
        title: String,
        description: String,
        category: FeatureCategory,
        current: () -> Boolean,
        command: (Boolean) -> String,
    ) = privilegedAction(
        registry,
        typeId,
        title,
        description,
        category,
        listOf(FieldSchema.Choice("mode", "Mode", true, listOf("enable", "disable", "toggle"))),
        setOf("reference", "MacroDroid", "ShortX", "Tasker", "toggle"),
    ) { feature, ctx ->
        val enabled = when (feature.config.string("mode", "enable")) {
            "enable" -> true
            "disable" -> false
            "toggle" -> !runCatching(current).getOrDefault(false)
            else -> return@privilegedAction ActionExecutionResult(false, message = userText("feature.operation_failed", feature.typeId))
        }
        executeShell(typeId, command(enabled), ctx)
    }

    private fun registerAmbientDisplay(registry: FeatureRegistry) = privilegedAction(
        registry,
        "android.display.ambient_display.set",
        "Set ambient display",
        "Enable, disable, or toggle wake-for-notifications or always-on ambient display",
        FeatureCategory.DISPLAY,
        listOf(
            FieldSchema.Choice("setting", "Ambient display setting", true, listOf("wake_for_notifications", "always_on")),
            FieldSchema.Choice("mode", "Mode", true, listOf("enable", "disable", "toggle")),
        ),
        setOf("ambient display", "always on", "doze", "wake notifications", "MacroDroid"),
        aliases = setOf("android.ambient_display.set"),
    ) { feature, ctx ->
        val setting = feature.config.string("setting", "").ifBlank { "both" }
        val enabled = when (feature.config.string("mode", "enable")) {
            "enable" -> true
            "disable" -> false
            "toggle" -> !ambientDisplayEnabled(setting)
            else -> return@privilegedAction ActionExecutionResult(false, message = userText("feature.operation_failed", feature.typeId))
        }
        val command = ambientDisplayCommand(setting, enabled)
            ?: return@privilegedAction ActionExecutionResult(false, message = userText("feature.operation_failed", feature.typeId))
        executeShell(feature.typeId, command, ctx)
    }

    private fun carModeEnabled(): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_CAR

    private fun colorInversionEnabled(): Boolean =
        Settings.Secure.getInt(context.contentResolver, "accessibility_display_inversion_enabled", 0) == 1

    private fun ambientDisplayEnabled(setting: String): Boolean {
        return when (setting) {
            "wake_for_notifications" ->
                Settings.Secure.getInt(context.contentResolver, "doze_enabled", 0) == 1
            "always_on" ->
                Settings.Secure.getInt(context.contentResolver, "doze_always_on", 0) == 1
            "both" ->
                Settings.Secure.getInt(context.contentResolver, "doze_enabled", 0) == 1 &&
                    Settings.Secure.getInt(context.contentResolver, "doze_always_on", 0) == 1
            else -> false
        }
    }

    private fun headsUpEnabled(): Boolean =
        Settings.Global.getInt(context.contentResolver, "heads_up_notifications_enabled", 1) == 1

    private fun dataRoamingEnabled(): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.DATA_ROAMING, 0) == 1

    private fun privilegedAction(
        registry: FeatureRegistry,
        typeId: String,
        title: String,
        description: String,
        category: FeatureCategory,
        fields: List<FieldSchema>,
        keywords: Set<String>,
        aliases: Set<String> = emptySet(),
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
                aliases = aliases,
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
        AudioFocusRuntimeBridge.dispatch(change)
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
internal fun ambientDisplayCommand(setting: String, enabled: Boolean): String? {
    val value = if (enabled) 1 else 0
    return when (setting) {
        "wake_for_notifications" -> "settings put secure doze_enabled $value"
        "always_on" -> "settings put secure doze_always_on $value"
        "both" -> "settings put secure doze_enabled $value; settings put secure doze_always_on $value"
        else -> null
    }
}
internal fun headsUpCommand(enabled: Boolean): String =
    "settings put global heads_up_notifications_enabled ${if (enabled) 1 else 0}"
internal fun dataRoamingCommand(enabled: Boolean): String =
    "settings put global data_roaming ${if (enabled) 1 else 0}"


object AudioFocusRuntimeBridge {
    @Volatile private var emitter: RuntimeEventEmitter? = null

    fun attach(value: RuntimeEventEmitter?) { emitter = value }

    fun dispatch(change: Int) {
        val name = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> "gain"
            AudioManager.AUDIOFOCUS_LOSS -> "loss"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "loss_transient"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "loss_can_duck"
            else -> "unknown"
        }
        emitter?.emit(
            com.yagay.yauto.core.model.RuntimeEvent(
                typeId = "android.event.audio_focus_changed",
                payload = mapOf(
                    "change" to ConfigValue.StringValue(name),
                    "raw" to ConfigValue.NumberValue(change.toDouble()),
                ),
                source = "android.audio.focus",
            )
        )
    }
}

class AudioFocusEventSource : AndroidEventSource {
    override val id: String = "android.audio.focus"
    override fun start(emitter: RuntimeEventEmitter) { AudioFocusRuntimeBridge.attach(emitter) }
    override fun stop() { AudioFocusRuntimeBridge.attach(null) }
}
