package com.yagay.yauto.feature.standard.privileged

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDefinition
import com.yagay.yauto.core.registry.FieldBehavior
import com.yagay.yauto.core.registry.FieldSchema
import com.yagay.yauto.core.registry.actionFeature
import com.yagay.yauto.core.registry.resolveVariables

internal object PrivilegedInputCaptureFeatures {
    private const val SCREEN_RECORD_FINISH_GRACE_MS = 15_000L

    val definitions: List<FeatureDefinition> = listOf(
        privilegedCommandFeature(
            privilegedDescriptor(
                "android.screen.wake",
                "Wake screen",
                "Wake the display through an Android input key event",
                FeatureCategory.DISPLAY,
                keywords = setOf("wake", "screen on", "display", "keyevent"),
            )
        ) { _, _ -> "input keyevent 224" },
        actionFeature(
            privilegedDescriptor(
                "android.input.keyevent",
                "Send key event",
                "Send a common Android key event through a privileged shell backend",
                FeatureCategory.SYSTEM,
                fields = listOf(
                    FieldSchema.Choice(
                        "key",
                        "Key",
                        true,
                        listOf(
                            "back", "home", "recents", "enter", "tab", "escape",
                            "volume_up", "volume_down", "volume_mute", "media_play_pause",
                            "media_next", "media_previous", "camera", "custom",
                        ),
                    ),
                    FieldSchema.Number("customKeyCode", "Custom Android key code", min = 0.0, max = 1000.0),
                    FieldSchema.Toggle("longPress", "Long press"),
                ),
                keywords = setOf("key", "keyevent", "button", "input", "media key", "volume button"),
                behaviors = mapOf(
                    "key" to FieldBehavior(defaultValue = ConfigValue.StringValue("back")),
                    "customKeyCode" to FieldBehavior(
                        visibleWhen = com.yagay.yauto.core.registry.FieldRule.Equals(
                            "key",
                            ConfigValue.StringValue("custom"),
                        ),
                    ),
                    "longPress" to FieldBehavior(defaultValue = ConfigValue.BooleanValue(false)),
                ),
            )
        ) { feature, context ->
            val key = feature.config.string("key", "back")
            val keyCode = KEY_CODES[key] ?: if (key == "custom") {
                feature.config["customKeyCode"].numberOrNull()?.toInt()
                    ?.takeIf { it in 0..1000 }
                    ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.invalid_custom_key_code"))
            } else {
                return@actionFeature ActionExecutionResult(false, message = userText("feature.unknown_key"))
            }
            val longPress = if (feature.config.boolean("longPress")) " --longpress" else ""
            executePrivilegedShell(feature, context, "input keyevent$longPress $keyCode")
        },
        actionFeature(
            privilegedDescriptor(
                "android.screenshot.capture",
                "Capture screenshot",
                "Capture the current display to a PNG file in shared storage",
                FeatureCategory.DISPLAY,
                fields = listOf(
                    FieldSchema.Text("path", "PNG path (blank = Downloads/YAuto)"),
                    FieldSchema.Variable("resultVariable", "Store saved path in variable"),
                ),
                keywords = setOf("screenshot", "screen capture", "screencap", "png"),
                behaviors = mapOf("path" to FieldBehavior(supportsVariables = true)),
            )
        ) { feature, context ->
            val rawPath = feature.config.string("path").resolveVariables(context.variables).trim()
            val path = runCatching { screenshotPath(rawPath) }.getOrElse { error ->
                return@actionFeature ActionExecutionResult(
                    false,
                    message = error.message ?: userText("feature.screenshot_path_invalid"),
                )
            }
            val parent = path.substringBeforeLast('/', "/sdcard/Download/YAuto")
            val result = executePrivilegedShell(
                feature,
                context,
                "mkdir -p ${shellQuote(parent)} && screencap -p ${shellQuote(path)}",
            )
            if (!result.success) return@actionFeature result
            val output = ConfigValue.StringValue(path)
            feature.config.string("resultVariable").trim().takeIf(String::isNotEmpty)?.let {
                context.variables.set(it, output)
            }
            ActionExecutionResult(true, output)
        },
        actionFeature(
            privilegedDescriptor(
                "android.screen.record",
                "Record screen",
                "Record the display to an MP4 file for a bounded duration using Android screenrecord",
                FeatureCategory.DISPLAY,
                fields = listOf(
                    FieldSchema.Text("path", "MP4 path (blank = Downloads/YAuto)"),
                    FieldSchema.Number("durationSeconds", "Duration seconds", min = 1.0, max = 180.0),
                    FieldSchema.Number("bitrateMbps", "Bitrate Mbps", min = 1.0, max = 100.0),
                    FieldSchema.Variable("resultVariable", "Store saved path in variable"),
                ),
                keywords = setOf("screen record", "screenrecord", "video", "mp4", "capture"),
                behaviors = mapOf(
                    "path" to FieldBehavior(supportsVariables = true),
                    "durationSeconds" to FieldBehavior(defaultValue = ConfigValue.NumberValue(30.0)),
                ),
            )
        ) { feature, context ->
            val rawPath = feature.config.string("path").resolveVariables(context.variables).trim()
            val path = runCatching { screenRecordingPath(rawPath) }.getOrElse { error ->
                return@actionFeature ActionExecutionResult(
                    false,
                    message = error.message ?: userText("feature.screen_record_path_invalid"),
                )
            }
            val duration = (feature.config["durationSeconds"].numberOrNull() ?: 30.0).toInt()
            if (duration !in 1..180) {
                return@actionFeature ActionExecutionResult(false, message = userText("feature.screen_record_duration_invalid"))
            }
            val bitrateMbps = feature.config["bitrateMbps"].numberOrNull()
            if (bitrateMbps != null && (bitrateMbps < 1.0 || bitrateMbps > 100.0 || !bitrateMbps.isFinite())) {
                return@actionFeature ActionExecutionResult(false, message = userText("feature.screen_record_bitrate_invalid"))
            }
            val parent = path.substringBeforeLast('/', "/sdcard/Download/YAuto")
            val bitrateArg = bitrateMbps?.let { " --bit-rate ${(it * 1_000_000.0).toLong()}" }.orEmpty()
            val command = "mkdir -p ${shellQuote(parent)} && screenrecord --time-limit $duration$bitrateArg ${shellQuote(path)}"
            val result = executePrivilegedShell(
                feature,
                context,
                command,
                timeoutMs = duration * 1_000L + SCREEN_RECORD_FINISH_GRACE_MS,
            )
            if (!result.success) return@actionFeature result
            val output = ConfigValue.StringValue(path)
            feature.config.string("resultVariable").trim().takeIf(String::isNotEmpty)?.let {
                context.variables.set(it, output)
            }
            ActionExecutionResult(true, output)
        },
    )

    private fun screenshotPath(raw: String): String {
        val path = raw.ifBlank { "/sdcard/Download/YAuto/screenshot-${System.currentTimeMillis()}.png" }
        requireSharedStorage(path, "feature.screenshot_path_shared_storage_required")
        require(path.endsWith(".png", ignoreCase = true)) { userText("feature.screenshot_path_png_required") }
        requireSafePath(path, "feature.screenshot_path_invalid")
        return path
    }

    private fun screenRecordingPath(raw: String): String {
        val path = raw.ifBlank { "/sdcard/Download/YAuto/screenrecord-${System.currentTimeMillis()}.mp4" }
        requireSharedStorage(path, "feature.screen_record_path_shared_storage_required")
        require(path.endsWith(".mp4", ignoreCase = true)) { userText("feature.screen_record_path_mp4_required") }
        requireSafePath(path, "feature.screen_record_path_invalid")
        return path
    }

    private fun requireSharedStorage(path: String, key: String) {
        require(path.startsWith("/sdcard/") || path.startsWith("/storage/emulated/0/")) { userText(key) }
    }

    private fun requireSafePath(path: String, key: String) {
        require(path.none { it == '\n' || it == '\r' || it == '\u0000' }) { userText(key) }
    }

    private val KEY_CODES = mapOf(
        "back" to 4,
        "home" to 3,
        "recents" to 187,
        "enter" to 66,
        "tab" to 61,
        "escape" to 111,
        "volume_up" to 24,
        "volume_down" to 25,
        "volume_mute" to 164,
        "media_play_pause" to 85,
        "media_next" to 87,
        "media_previous" to 88,
        "camera" to 27,
    )
}
