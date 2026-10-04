package com.yagay.yauto.platform.android

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.util.DisplayMetrics
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigMap
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import kotlin.math.roundToInt

/**
 * Stable system-control gaps verified against MacroDroid / ShortX / Tasker.
 *
 * Existing canonical state nodes are reused where they already exist (microphone, speakerphone,
 * default IME); this pack only registers new IDs.
 */
class AndroidReferenceControlExpansionFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.reference_control_expansion"
    private val context = context.applicationContext
    private val audio = this.context.getSystemService(AudioManager::class.java)

    override fun install(registry: FeatureRegistry) {
        registerImmersiveMode(registry)
        registerMicrophoneMute(registry)
        registerSpeakerphone(registry)
        registerVolumeUi(registry)

        numericPercentPair(
            registry,
            "display_density_percent",
            "Display density percent",
            "Compare the current display density with a percentage of the device stable density",
            FeatureCategory.DISPLAY,
            50.0,
            200.0,
            ::currentDensityPercent,
        )
        numericPercentPair(
            registry,
            "font_scale_percent",
            "Font scale percent",
            "Compare the current Android font scale percentage",
            FeatureCategory.DISPLAY,
            50.0,
            300.0,
            ::currentFontScalePercent,
        )
        choicePair(
            registry,
            "immersive_mode",
            "Immersive mode",
            "Match the current system-wide immersive policy",
            FeatureCategory.DISPLAY,
            listOf("off", "navigation", "status", "full"),
            ::currentImmersiveMode,
        )
        animationScalePair(registry)
    }

    private fun registerImmersiveMode(registry: FeatureRegistry) {
        privilegedAction(
            registry,
            "android.display.immersive.set",
            "Set immersive mode",
            "Show or hide Android status and navigation bars system-wide",
            FeatureCategory.DISPLAY,
            listOf(FieldSchema.Choice("mode", "Immersive mode", true, listOf("off", "navigation", "status", "full"))),
            setOf("immersive", "status bar", "navigation bar", "insets", "MacroDroid", "ShortX"),
        ) { feature, ctx ->
            val command = immersiveModeCommand(feature.config.string("mode", "off"))
                ?: return@privilegedAction ActionExecutionResult(false, message = userText("feature.operation_failed", feature.typeId))
            executeShell(feature.typeId, command, ctx)
        }
    }

    private fun registerMicrophoneMute(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.audio.microphone_mute.set"),
                FeatureKind.ACTION,
                "Set microphone mute",
                "Mute, unmute, or toggle the Android system microphone mute state",
                FeatureCategory.AUDIO,
                fields = listOf(FieldSchema.Choice("mode", "Microphone state", true, listOf("mute", "unmute", "toggle"))),
                keywords = setOf("microphone", "mute", "call", "voip", "MacroDroid"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            val mute = when (feature.config.string("mode", "mute")) {
                "mute" -> true
                "unmute" -> false
                "toggle" -> !audio.isMicrophoneMute
                else -> return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", feature.typeId))
            }
            @Suppress("DEPRECATION")
            val ok = runCatching {
                audio.isMicrophoneMute = mute
                audio.isMicrophoneMute == mute
            }.getOrDefault(false)
            ActionExecutionResult(ok, ConfigValue.BooleanValue(mute), if (ok) null else userText("feature.operation_failed", feature.typeId))
        }
    }

    private fun registerSpeakerphone(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.audio.speakerphone.set"),
                FeatureKind.ACTION,
                "Set speakerphone",
                "Enable, disable, or toggle speakerphone routing",
                FeatureCategory.AUDIO,
                fields = listOf(FieldSchema.Choice("mode", "Speakerphone state", true, listOf("enable", "disable", "toggle"))),
                keywords = setOf("speakerphone", "speaker", "call", "audio route", "MacroDroid"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            @Suppress("DEPRECATION")
            val enabled = when (feature.config.string("mode", "enable")) {
                "enable" -> true
                "disable" -> false
                "toggle" -> !audio.isSpeakerphoneOn
                else -> return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", feature.typeId))
            }
            @Suppress("DEPRECATION")
            val ok = runCatching {
                audio.isSpeakerphoneOn = enabled
                audio.isSpeakerphoneOn == enabled
            }.getOrDefault(false)
            ActionExecutionResult(ok, ConfigValue.BooleanValue(enabled), if (ok) null else userText("feature.operation_failed", feature.typeId))
        }
    }

    private fun registerVolumeUi(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.audio.volume_ui.show"),
                FeatureKind.ACTION,
                "Show volume panel",
                "Show Android's system volume panel for a selected audio stream",
                FeatureCategory.AUDIO,
                fields = listOf(
                    FieldSchema.Choice(
                        "stream",
                        "Audio stream",
                        true,
                        listOf("alarm", "music", "notification", "ringer", "system", "voice_call", "bluetooth_voice"),
                    )
                ),
                keywords = setOf("volume", "popup", "panel", "audio", "MacroDroid"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            val stream = audioStream(feature.config.string("stream", "music"))
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", feature.typeId))
            val ok = runCatching {
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
                true
            }.getOrDefault(false)
            ActionExecutionResult(ok, message = if (ok) null else userText("feature.operation_failed", feature.typeId))
        }
    }

    private fun numericPercentPair(
        registry: FeatureRegistry,
        key: String,
        title: String,
        description: String,
        category: FeatureCategory,
        minAllowed: Double,
        maxAllowed: Double,
        query: () -> Double,
    ) {
        val fields = listOf(
            FieldSchema.Number("minPercent", "Minimum percent", min = minAllowed, max = maxAllowed),
            FieldSchema.Number("maxPercent", "Maximum percent", min = minAllowed, max = maxAllowed),
        )
        val evaluator = ConditionEvaluator { feature, _ ->
            val min = feature.config["minPercent"].numberOrNull() ?: minAllowed
            val max = feature.config["maxPercent"].numberOrNull() ?: maxAllowed
            min <= max && runCatching(query).getOrDefault(Double.NaN) in min..max
        }
        pair(registry, key, title, description, category, fields, evaluator)
    }

    private fun choicePair(
        registry: FeatureRegistry,
        key: String,
        title: String,
        description: String,
        category: FeatureCategory,
        options: List<String>,
        query: () -> String,
    ) {
        val fields = listOf(FieldSchema.Choice("value", "Value", true, options))
        val evaluator = ConditionEvaluator { feature, _ ->
            runCatching(query).getOrDefault("") == feature.config.string("value", options.first())
        }
        pair(registry, key, title, description, category, fields, evaluator)
    }

    private fun animationScalePair(registry: FeatureRegistry) {
        val fields = listOf(
            FieldSchema.Choice("target", "Animation scale target", true, listOf("window", "transition", "animator")),
            FieldSchema.Number("min", "Minimum scale", min = 0.0, max = 10.0),
            FieldSchema.Number("max", "Maximum scale", min = 0.0, max = 10.0),
        )
        val evaluator = ConditionEvaluator { feature, _ ->
            val value = currentAnimationScale(feature.config.string("target", "window")) ?: return@ConditionEvaluator false
            val min = feature.config["min"].numberOrNull() ?: 0.0
            val max = feature.config["max"].numberOrNull() ?: 10.0
            min <= max && value in min..max
        }
        pair(
            registry,
            "animation_scale",
            "Animation scale",
            "Compare the current Android animation scale",
            FeatureCategory.DISPLAY,
            fields,
            evaluator,
        )
    }

    private fun pair(
        registry: FeatureRegistry,
        key: String,
        title: String,
        description: String,
        category: FeatureCategory,
        fields: List<FieldSchema>,
        evaluator: ConditionEvaluator,
    ) {
        val state = FeatureDescriptor(
            FeatureId("android.state.$key"),
            FeatureKind.STATE,
            title,
            description,
            category,
            fields = fields,
            keywords = setOf("MacroDroid", "ShortX", "Tasker", "system control"),
            ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(
            state.copy(id = FeatureId("android.condition.$key"), kind = FeatureKind.CONDITION),
            evaluator,
        )
    }

    private fun currentDensityPercent(): Double =
        context.resources.displayMetrics.densityDpi.toDouble() * 100.0 / DisplayMetrics.DENSITY_DEVICE_STABLE.toDouble()

    private fun currentFontScalePercent(): Double =
        context.resources.configuration.fontScale.toDouble() * 100.0

    private fun currentImmersiveMode(): String =
        immersiveModeFromPolicy(Settings.Global.getString(context.contentResolver, "policy_control"))

    private fun currentAnimationScale(target: String): Double? {
        val key = animationScaleSetting(target) ?: return null
        return Settings.Global.getFloat(context.contentResolver, key, 1f).toDouble()
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

}

internal fun densityCommand(scalePercent: Int, stableDensityDpi: Int): String? {
    if (scalePercent !in 50..150 || stableDensityDpi <= 0) return null
    val target = (stableDensityDpi * scalePercent / 100.0).roundToInt().coerceAtLeast(1)
    return "wm density $target"
}

internal fun immersiveModeCommand(mode: String): String? = when (mode) {
    "off" -> "settings delete global policy_control"
    "navigation" -> "settings put global policy_control immersive.navigation=*"
    "status" -> "settings put global policy_control immersive.status=*"
    "full" -> "settings put global policy_control immersive.full=*"
    else -> null
}

internal fun immersiveModeFromPolicy(value: String?): String = when {
    value.isNullOrBlank() -> "off"
    value.contains("immersive.full") -> "full"
    value.contains("immersive.navigation") -> "navigation"
    value.contains("immersive.status") -> "status"
    else -> "off"
}

internal fun animationScaleCommand(target: String, scale: Double): String? {
    if (!scale.isFinite() || scale !in 0.0..10.0) return null
    val literal = scale.toString()
    return when (target) {
        "window" -> "settings put global window_animation_scale $literal"
        "transition" -> "settings put global transition_animation_scale $literal"
        "animator" -> "settings put global animator_duration_scale $literal"
        "all" -> listOf(
            "settings put global window_animation_scale $literal",
            "settings put global transition_animation_scale $literal",
            "settings put global animator_duration_scale $literal",
        ).joinToString("; ")
        else -> null
    }
}

internal fun animationScaleSetting(target: String): String? = when (target) {
    "window" -> "window_animation_scale"
    "transition" -> "transition_animation_scale"
    "animator" -> "animator_duration_scale"
    else -> null
}

internal fun audioStream(value: String): Int? = when (value) {
    "alarm" -> AudioManager.STREAM_ALARM
    "music" -> AudioManager.STREAM_MUSIC
    "notification" -> AudioManager.STREAM_NOTIFICATION
    "ringer" -> AudioManager.STREAM_RING
    "system" -> AudioManager.STREAM_SYSTEM
    "voice_call" -> AudioManager.STREAM_VOICE_CALL
    "bluetooth_voice" -> AudioManager.STREAM_BLUETOOTH_SCO
    else -> null
}
