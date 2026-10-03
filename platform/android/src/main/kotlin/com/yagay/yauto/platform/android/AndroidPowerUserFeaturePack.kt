package com.yagay.yauto.platform.android

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.ConditionEvaluator
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureExecutionContext
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.FieldSchema
import com.yagay.yauto.core.registry.resolveVariables

/** High-value native utilities inspired by mature automation apps without adding compatibility layers. */
class AndroidPowerUserFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.power_user"
    private val context = context.applicationContext
    private val audio = this.context.getSystemService(AudioManager::class.java)
    private val connectivity = this.context.getSystemService(ConnectivityManager::class.java)
    private val keyguard = this.context.getSystemService(KeyguardManager::class.java)
    private val vibrator = this.context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val resolver = this.context.contentResolver

    override fun install(registry: FeatureRegistry) {
        registerShell(registry)
        registerVibration(registry)
        registerAudioStates(registry)
        registerDeviceStates(registry)
        registerNetworkStates(registry)
    }

    private fun registerShell(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.shell.execute"), FeatureKind.ACTION,
                "Run privileged shell command", "Run a user-supplied shell command through the selected privileged backend and expose its structured result",
                FeatureCategory.SCRIPT,
                fields = listOf(
                    FieldSchema.Text("command", "Command", true, multiline = true),
                    FieldSchema.Variable("resultVariable", "Store result object in variable"),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                keywords = setOf("shell", "command", "root", "shizuku", "terminal"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val command = validatedShellCommand(feature.config.string("command").resolveVariables(ctx.variables))
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.shell_command_invalid"))
            val result = ctx.capabilities.execute(
                CapabilityRequest(
                    capability = CapabilityIds.PRIVILEGED_SHELL,
                    operationId = "android.shell.execute",
                    payload = mapOf("command" to ConfigValue.StringValue(command)),
                )
            )
            feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { name ->
                ctx.variables.set(name, result.value)
            }
            ActionExecutionResult(result.success, result.value, result.message)
        }
    }

    private fun registerVibration(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.vibration.vibrate"), FeatureKind.ACTION,
                "Vibrate", "Run a one-shot device vibration with bounded duration and amplitude",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Number("durationMs", "Duration milliseconds", true, min = 1.0, max = 60_000.0),
                    FieldSchema.Number("amplitude", "Amplitude (1-255)", min = 1.0, max = 255.0),
                ),
                keywords = setOf("vibrate", "vibration", "haptic"), ownerPackId = id,
            )
        ) { feature, _ ->
            val duration = feature.config["durationMs"].numberOrNull()?.toLong()
            val amplitude = feature.config["amplitude"].numberOrNull()?.toInt() ?: VibrationEffect.DEFAULT_AMPLITUDE
            if (duration == null || duration !in 1L..60_000L || (amplitude != VibrationEffect.DEFAULT_AMPLITUDE && amplitude !in 1..255)) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.vibration_pattern_invalid"))
            }
            runCatching {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
                ActionExecutionResult(true)
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.vibration.pattern"), FeatureKind.ACTION,
                "Vibrate pattern", "Run a finite vibration waveform from comma-separated on/off timing values",
                FeatureCategory.DEVICE,
                fields = listOf(FieldSchema.Text("timings", "Timings milliseconds", true)),
                keywords = setOf("vibrate", "pattern", "waveform", "haptic"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val timings = parseVibrationTimings(feature.config.string("timings").resolveVariables(ctx.variables))
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.vibration_pattern_invalid"))
            runCatching {
                vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
                ActionExecutionResult(true)
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.vibration.cancel"), FeatureKind.ACTION,
                "Cancel vibration", "Cancel vibration currently controlled by YAuto or another app using the default vibrator",
                FeatureCategory.DEVICE,
                keywords = setOf("vibrate", "cancel", "stop", "haptic"), ownerPackId = id,
            )
        ) { _, _ ->
            runCatching { vibrator.cancel(); ActionExecutionResult(true) }
                .getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }
    }

    private fun registerAudioStates(registry: FeatureRegistry) {
        registerPair(
            registry,
            FeatureDescriptor(
                FeatureId("android.state.audio.music_active"), FeatureKind.STATE,
                "Media audio active", "Check whether Android reports active music/media playback",
                FeatureCategory.AUDIO,
                fields = listOf(FieldSchema.Toggle("value", "Active")), ownerPackId = id,
            ),
            FeatureDescriptor(
                FeatureId("android.condition.audio.music_active"), FeatureKind.CONDITION,
                "Media audio active", "Check whether Android reports active music/media playback",
                FeatureCategory.AUDIO,
                fields = listOf(FieldSchema.Toggle("value", "Active")), ownerPackId = id,
            ),
        ) { feature, _ -> audio.isMusicActive == feature.config.boolean("value", true) }

        val modeFields = listOf(FieldSchema.Choice("mode", "Audio mode", true, listOf("normal", "ringtone", "in_call", "in_communication", "unknown")))
        registerPair(
            registry,
            FeatureDescriptor(
                FeatureId("android.state.audio.mode"), FeatureKind.STATE,
                "Audio mode", "Match the current Android AudioManager communication mode",
                FeatureCategory.AUDIO, fields = modeFields, ownerPackId = id,
            ),
            FeatureDescriptor(
                FeatureId("android.condition.audio.mode"), FeatureKind.CONDITION,
                "Audio mode", "Match the current Android AudioManager communication mode",
                FeatureCategory.AUDIO, fields = modeFields, ownerPackId = id,
            ),
        ) { feature, _ -> audioModeName(audio.mode) == feature.config.string("mode", "normal") }
    }

    private fun registerDeviceStates(registry: FeatureRegistry) {
        val fields = listOf(FieldSchema.Toggle("value", "Locked"))
        registerPair(
            registry,
            FeatureDescriptor(
                FeatureId("android.state.keyguard_locked"), FeatureKind.STATE,
                "Lock screen active", "Check whether Android currently reports the keyguard/lock screen as locked",
                FeatureCategory.DEVICE, fields = fields, ownerPackId = id,
            ),
            FeatureDescriptor(
                FeatureId("android.condition.keyguard_locked"), FeatureKind.CONDITION,
                "Lock screen active", "Check whether Android currently reports the keyguard/lock screen as locked",
                FeatureCategory.DEVICE, fields = fields, ownerPackId = id,
            ),
        ) { feature, _ -> keyguard.isKeyguardLocked == feature.config.boolean("value", true) }
    }

    private fun registerNetworkStates(registry: FeatureRegistry) {
        val dataSaverFields = listOf(
            FieldSchema.Choice("status", "Data saver status", true, listOf("disabled", "enabled", "whitelisted")),
        )
        registerPair(
            registry,
            FeatureDescriptor(
                FeatureId("android.state.data_saver"), FeatureKind.STATE,
                "Data saver status", "Match Android background-data restriction status",
                FeatureCategory.NETWORK, fields = dataSaverFields, ownerPackId = id,
            ),
            FeatureDescriptor(
                FeatureId("android.condition.data_saver"), FeatureKind.CONDITION,
                "Data saver status", "Match Android background-data restriction status",
                FeatureCategory.NETWORK, fields = dataSaverFields, ownerPackId = id,
            ),
        ) { feature, _ -> dataSaverStatus(connectivity.restrictBackgroundStatus) == feature.config.string("status", "disabled") }

        val privateDnsFields = listOf(
            FieldSchema.Choice("mode", "Private DNS mode", true, listOf("off", "automatic", "hostname")),
            FieldSchema.Text("hostnameContains", "Hostname contains"),
        )
        registerPair(
            registry,
            FeatureDescriptor(
                FeatureId("android.state.private_dns"), FeatureKind.STATE,
                "Private DNS status", "Match Android private DNS mode and optional provider hostname",
                FeatureCategory.NETWORK, fields = privateDnsFields, ownerPackId = id,
            ),
            FeatureDescriptor(
                FeatureId("android.condition.private_dns"), FeatureKind.CONDITION,
                "Private DNS status", "Match Android private DNS mode and optional provider hostname",
                FeatureCategory.NETWORK, fields = privateDnsFields, ownerPackId = id,
            ),
        ) { feature, _ ->
            val expectedMode = feature.config.string("mode", "automatic")
            val mode = privateDnsMode(Settings.Global.getString(resolver, "private_dns_mode"))
            val expectedHost = feature.config.string("hostnameContains").trim()
            val actualHost = Settings.Global.getString(resolver, "private_dns_specifier").orEmpty()
            mode == expectedMode && (expectedHost.isBlank() || actualHost.contains(expectedHost, ignoreCase = true))
        }
    }

    private fun registerPair(
        registry: FeatureRegistry,
        state: FeatureDescriptor,
        condition: FeatureDescriptor,
        evaluate: suspend (com.yagay.yauto.core.model.FeatureRef, FeatureExecutionContext) -> Boolean,
    ) {
        val evaluator = ConditionEvaluator { feature, ctx -> runCatching { evaluate(feature, ctx) }.getOrDefault(false) }
        registry.registerState(state, evaluator)
        registry.registerCondition(condition, evaluator)
    }
}

internal fun validatedShellCommand(raw: String): String? {
    val command = raw.trim()
    return command.takeIf { it.isNotEmpty() && it.length <= 16_384 && '\u0000' !in it }
}

internal fun parseVibrationTimings(raw: String): LongArray? {
    val values = raw.trim().split(Regex("[,\\s]+"))
        .filter { it.isNotBlank() }
        .map { it.toLongOrNull() ?: return null }
    if (values.isEmpty() || values.size > 64 || values.any { it !in 0L..60_000L } || values.sum() !in 1L..120_000L) return null
    return values.toLongArray()
}

internal fun audioModeName(mode: Int): String = when (mode) {
    AudioManager.MODE_NORMAL -> "normal"
    AudioManager.MODE_RINGTONE -> "ringtone"
    AudioManager.MODE_IN_CALL -> "in_call"
    AudioManager.MODE_IN_COMMUNICATION -> "in_communication"
    else -> "unknown"
}

internal fun dataSaverStatus(status: Int): String = when (status) {
    ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "enabled"
    ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "whitelisted"
    else -> "disabled"
}

internal fun privateDnsMode(raw: String?): String = when (raw) {
    "off" -> "off"
    "hostname" -> "hostname"
    else -> "automatic"
}
