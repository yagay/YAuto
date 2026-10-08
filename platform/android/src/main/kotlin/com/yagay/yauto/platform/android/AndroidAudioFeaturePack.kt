package com.yagay.yauto.platform.android

import android.content.Context
import android.media.AudioManager
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*
import kotlin.math.roundToInt
import com.yagay.yauto.core.model.userText

class AndroidAudioFeaturePack(context: Context) : FeaturePack {
    override val id = "android.audio"
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.audio.volume.set"), FeatureKind.ACTION, "Set audio stream volume",
                "Set media, ringtone, notification, alarm, system or voice-call volume by percentage",
                FeatureCategory.AUDIO,
                fields = listOf(
                    streamField(),
                    FieldSchema.Number("percent", "Volume percent", true, min = 0.0, max = 100.0),
                    FieldSchema.Toggle("showUi", "Show system volume UI"),
                ),
                keywords = setOf("volume", "ring", "alarm", "notification"),
                aliases = setOf("android.audio.media_volume.set"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            runCatching {
                val stream = stream(feature.config.string("stream", "media"))
                val percent = (feature.config["percent"].numberOrNull() ?: 50.0).coerceIn(0.0, 100.0)
                val min = audio.getStreamMinVolume(stream)
                val max = audio.getStreamMaxVolume(stream)
                val value = (min + (max - min) * percent / 100.0).roundToInt().coerceIn(min, max)
                audio.setStreamVolume(stream, value, if (feature.config.boolean("showUi")) AudioManager.FLAG_SHOW_UI else 0)
                ActionExecutionResult(true, ConfigValue.NumberValue(percent))
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.audio.volume.adjust"), FeatureKind.ACTION, "Adjust audio stream",
                "Raise, lower, mute or unmute an Android audio stream",
                FeatureCategory.AUDIO,
                fields = listOf(
                    streamField(),
                    FieldSchema.Choice("direction", "Adjustment", true, listOf("raise", "lower", "mute", "unmute", "toggle_mute")),
                    FieldSchema.Toggle("showUi", "Show system volume UI"),
                ), keywords = setOf("volume up", "volume down", "mute"), ownerPackId = id,
            )
        ) { feature, _ ->
            val adjustment = when (feature.config.string("direction", "raise")) {
                "lower" -> AudioManager.ADJUST_LOWER
                "mute" -> AudioManager.ADJUST_MUTE
                "unmute" -> AudioManager.ADJUST_UNMUTE
                "toggle_mute" -> AudioManager.ADJUST_TOGGLE_MUTE
                else -> AudioManager.ADJUST_RAISE
            }
            runCatching {
                audio.adjustStreamVolume(stream(feature.config.string("stream", "media")), adjustment,
                    if (feature.config.boolean("showUi")) AudioManager.FLAG_SHOW_UI else 0)
                ActionExecutionResult(true)
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.audio.ringer_mode.set"), FeatureKind.ACTION, "Ringer mode",
                "Set normal, vibrate or silent ringer mode. Device policy/DND rules may restrict silent mode.",
                FeatureCategory.AUDIO,
                fields = listOf(FieldSchema.Choice("mode", "Mode", true, listOf("normal", "vibrate", "silent"))),
                keywords = setOf("ringer", "silent", "vibrate"), ownerPackId = id,
            )
        ) { feature, _ ->
            val mode = when (feature.config.string("mode", "normal")) {
                "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                "silent" -> AudioManager.RINGER_MODE_SILENT
                else -> AudioManager.RINGER_MODE_NORMAL
            }
            runCatching { audio.ringerMode = mode; ActionExecutionResult(true) }
                .getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.audio.microphone_mute.set"), FeatureKind.ACTION, "Microphone mute",
                "Mute or unmute the Android microphone through AudioManager",
                FeatureCategory.AUDIO,
                fields = listOf(
                    FieldSchema.Toggle("enabled", "Muted"),
                    FieldSchema.Choice("mode", "Microphone operation", options = listOf("set", "mute", "unmute", "toggle")),
                ),
                keywords = setOf("microphone", "mic", "mute"), ownerPackId = id,
            )
        ) { feature, _ ->
            @Suppress("DEPRECATION")
            val muted = when (feature.config.string("mode", "set")) {
                "mute" -> true
                "unmute" -> false
                "toggle" -> !audio.isMicrophoneMute
                else -> feature.config.boolean("enabled", true)
            }
            runCatching {
                @Suppress("DEPRECATION") audio.isMicrophoneMute = muted
                ActionExecutionResult(true, ConfigValue.BooleanValue(muted))
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.audio.speakerphone.set"), FeatureKind.ACTION, "Speakerphone",
                "Turn communication speakerphone routing on or off",
                FeatureCategory.AUDIO,
                fields = listOf(
                    FieldSchema.Toggle("enabled", "Speakerphone on"),
                    FieldSchema.Choice("mode", "Speakerphone operation", options = listOf("set", "enable", "disable", "toggle")),
                ),
                keywords = setOf("speaker", "speakerphone"), ownerPackId = id,
            )
        ) { feature, _ ->
            @Suppress("DEPRECATION")
            val enabled = when (feature.config.string("mode", "set")) {
                "enable" -> true
                "disable" -> false
                "toggle" -> !audio.isSpeakerphoneOn
                else -> feature.config.boolean("enabled", true)
            }
            runCatching {
                @Suppress("DEPRECATION") audio.isSpeakerphoneOn = enabled
                ActionExecutionResult(true, ConfigValue.BooleanValue(enabled))
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }

        stateAndCondition(
            registry,
            "ringer_mode",
            "Ringer mode",
            listOf(FieldSchema.Choice("mode", "Mode", true, listOf("normal", "vibrate", "silent"))),
            legacyKey = "ringer_mode",
        ) { feature ->
            val actual = when (audio.ringerMode) {
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                AudioManager.RINGER_MODE_SILENT -> "silent"
                else -> "normal"
            }
            val expected = feature.config.string("mode", "").ifBlank {
                feature.config.string("value", "normal")
            }
            actual == expected
        }
        stateAndCondition(
            registry,
            "microphone_muted",
            "Microphone muted",
            listOf(FieldSchema.Toggle("value", "Muted")),
            legacyKey = "microphone_muted",
        ) { feature ->
            @Suppress("DEPRECATION") val actual = audio.isMicrophoneMute
            actual == feature.config.boolean("value", true)
        }
        stateAndCondition(
            registry,
            "speakerphone",
            "Speakerphone",
            listOf(FieldSchema.Toggle("value", "On")),
            legacyKey = "speakerphone_on",
        ) { feature ->
            @Suppress("DEPRECATION") val actual = audio.isSpeakerphoneOn
            actual == feature.config.boolean("value", true)
        }
        stateAndCondition(registry, "stream_volume", "Audio stream volume",
            listOf(streamField(), FieldSchema.Number("min", "Minimum percent", min = 0.0, max = 100.0), FieldSchema.Number("max", "Maximum percent", min = 0.0, max = 100.0))) { feature ->
            val stream = stream(feature.config.string("stream", "media")); val minIndex = audio.getStreamMinVolume(stream); val maxIndex = audio.getStreamMaxVolume(stream)
            val current = audio.getStreamVolume(stream)
            val percent = if (maxIndex <= minIndex) 0.0 else (current - minIndex) * 100.0 / (maxIndex - minIndex)
            val min = feature.config["min"].numberOrNull() ?: 0.0; val max = feature.config["max"].numberOrNull() ?: 100.0
            percent in min..max
        }
    }

    private fun stateAndCondition(
        registry: FeatureRegistry,
        key: String,
        title: String,
        fields: List<FieldSchema>,
        legacyKey: String? = null,
        evaluate: (com.yagay.yauto.core.model.FeatureRef) -> Boolean,
    ) {
        val stateId = "android.state.audio.$key"
        val conditionId = "android.condition.audio.$key"
        val state = FeatureDescriptor(
            FeatureId(stateId),
            FeatureKind.STATE,
            title,
            "Evaluate current Android audio state",
            FeatureCategory.AUDIO,
            fields = fields,
            aliases = legacyKey?.let { setOf("android.state.$it") }.orEmpty(),
            ownerPackId = id,
        )
        val condition = state.copy(
            id = FeatureId(conditionId),
            kind = FeatureKind.CONDITION,
            aliases = legacyKey?.let { setOf("android.condition.$it") }.orEmpty(),
        )
        val evaluator = ConditionEvaluator { feature, _ -> runCatching { evaluate(feature) }.getOrDefault(false) }
        registry.registerState(state, evaluator)
        registry.registerCondition(condition, evaluator)
    }

    private fun streamField() = FieldSchema.Choice("stream", "Audio stream", true, listOf("media", "ring", "notification", "alarm", "system", "voice_call"))
    private fun stream(value: String): Int = when (value) {
        "ring" -> AudioManager.STREAM_RING
        "notification" -> AudioManager.STREAM_NOTIFICATION
        "alarm" -> AudioManager.STREAM_ALARM
        "system" -> AudioManager.STREAM_SYSTEM
        "voice_call" -> AudioManager.STREAM_VOICE_CALL
        else -> AudioManager.STREAM_MUSIC
    }
}
