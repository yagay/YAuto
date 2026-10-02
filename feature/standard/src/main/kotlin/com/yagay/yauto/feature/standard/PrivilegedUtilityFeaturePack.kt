package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

/** Small, explicit Root/Shizuku utilities shared by automation apps such as MacroDroid and Tasker. */
class PrivilegedUtilityFeaturePack : FeaturePack {
    override val id: String = "standard.android.utilities"

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.screen.wake"), FeatureKind.ACTION,
                "Wake screen", "Wake the display through an Android input key event",
                FeatureCategory.DISPLAY,
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                keywords = setOf("wake", "screen on", "display", "keyevent"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            executeShell(feature, ctx, "input keyevent 224")
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.input.keyevent"), FeatureKind.ACTION,
                "Send key event", "Send a common Android key event through a privileged shell backend",
                FeatureCategory.SYSTEM,
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                fields = listOf(
                    FieldSchema.Choice(
                        "key", "Key", true,
                        listOf("back", "home", "recents", "enter", "tab", "escape", "volume_up", "volume_down", "volume_mute", "media_play_pause", "media_next", "media_previous", "camera", "custom"),
                    ),
                    FieldSchema.Number("customKeyCode", "Custom Android key code", min = 0.0, max = 1000.0),
                    FieldSchema.Toggle("longPress", "Long press"),
                ),
                keywords = setOf("key", "keyevent", "button", "input", "media key", "volume button"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val key = feature.config.string("key", "back")
            val keyCode = KEY_CODES[key] ?: if (key == "custom") {
                feature.config["customKeyCode"].numberOrNull()?.toInt()
                    ?.takeIf { it in 0..1000 }
                    ?: return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", "Invalid custom key code"))
            } else {
                return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", "Unknown key"))
            }
            val longPress = if (feature.config.boolean("longPress")) " --longpress" else ""
            executeShell(feature, ctx, "input keyevent$longPress $keyCode")
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.screenshot.capture"), FeatureKind.ACTION,
                "Capture screenshot", "Capture the current display to a PNG file in shared storage",
                FeatureCategory.DISPLAY,
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                fields = listOf(
                    FieldSchema.Text("path", "PNG path (blank = Downloads/YAuto)"),
                    FieldSchema.Variable("resultVariable", "Store saved path in variable"),
                ),
                keywords = setOf("screenshot", "screen capture", "screencap", "png"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val rawPath = feature.config.string("path").resolveVariables(ctx.variables).trim()
            val path = runCatching { screenshotPath(rawPath) }.getOrElse {
                return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName))
            }
            val parent = path.substringBeforeLast('/', "/sdcard/Download/YAuto")
            val command = "mkdir -p ${shellQuote(parent)} && screencap -p ${shellQuote(path)}"
            val result = ctx.executeCapability(
                featureId = feature.typeId,
                request = CapabilityRequest(
                    CapabilityIds.PRIVILEGED_SHELL,
                    feature.typeId,
                    mapOf("command" to ConfigValue.StringValue(command)),
                    preferredBackendId = feature.preferredBackendId(),
                ),
            )
            if (!result.success) return@registerAction ActionExecutionResult(false, result.value, result.message)
            val output = ConfigValue.StringValue(path)
            feature.config.string("resultVariable").trim().takeIf { it.isNotEmpty() }?.let { ctx.variables.set(it, output) }
            ActionExecutionResult(true, output)
        }
    }

    private suspend fun executeShell(
        feature: com.yagay.yauto.core.model.FeatureRef,
        ctx: FeatureExecutionContext,
        command: String,
    ): ActionExecutionResult {
        val result = ctx.executeCapability(
            featureId = feature.typeId,
            request = CapabilityRequest(
                CapabilityIds.PRIVILEGED_SHELL,
                feature.typeId,
                mapOf("command" to ConfigValue.StringValue(command)),
                preferredBackendId = feature.preferredBackendId(),
            ),
        )
        return ActionExecutionResult(result.success, result.value, result.message)
    }

    private fun screenshotPath(raw: String): String {
        val path = raw.ifBlank { "/sdcard/Download/YAuto/screenshot-${System.currentTimeMillis()}.png" }
        require(path.startsWith("/sdcard/") || path.startsWith("/storage/emulated/0/")) {
            "Screenshot path must be in shared storage"
        }
        require(path.endsWith(".png", ignoreCase = true)) { "Screenshot path must end with .png" }
        require(path.none { it == '\n' || it == '\r' || it == '\u0000' }) { "Invalid screenshot path" }
        return path
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        val KEY_CODES = mapOf(
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
}
