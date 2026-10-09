package com.yagay.yauto.platform.android

import android.graphics.BitmapFactory
import android.util.Base64
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*
import java.io.File

/** Real status-bar ViewGroup child, injected inside SystemUI via LSPosed (not a notification). */
class AndroidShortXStatusChipFeaturePack : FeaturePack {
    override val id = "android.shortx.status_chip"

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.status_chip.control"), FeatureKind.ACTION,
                "Control SystemUI status-bar chip",
                "Show or hide a text/image chip inside SystemUI; taps and long presses emit YAuto trigger events",
                FeatureCategory.SYSTEM,
                fields = listOf(
                    FieldSchema.Choice("mode", "Operation", true, listOf("show", "hide")),
                    FieldSchema.Text("chipId", "Chip ID", true),
                    FieldSchema.Text("text", "Chip text"),
                    FieldSchema.Choice("iconMode", "Icon implementation", options = listOf("none", "android_drawable", "png_file")),
                    FieldSchema.Text("icon", "Android drawable name"),
                    FieldSchema.Text("pngPath", "Absolute path to PNG image (maximum 64 KiB)"),
                ),
                fieldBehaviors = mapOf(
                    "chipId" to FieldBehavior(defaultValue = ConfigValue.StringValue("shortx"), supportsVariables = true),
                    "text" to FieldBehavior(supportsVariables = true),
                    "icon" to FieldBehavior(visibleWhen = FieldRule.Equals("iconMode", ConfigValue.StringValue("android_drawable"))),
                    "pngPath" to FieldBehavior(visibleWhen = FieldRule.Equals("iconMode", ConfigValue.StringValue("png_file")), supportsVariables = true),
                ),
                capabilities = setOf(CapabilityIds.LSPOSED),
                accessRequirements = setOf(AccessRequirement.LSPOSED),
                implementationOptions = listOf(FeatureImplementationOption("lsposed", setOf(AccessRequirement.LSPOSED))),
                keywords = setOf("shortx", "systemui chip", "status bar chip", "text", "tap", "long click", "custom icon"),
                ownerPackId = id,
            )
        ) { feature, context ->
            val id = feature.config.string("chipId", "shortx").resolveVariables(context.variables).trim()
            val mode = feature.config.string("mode", "show")
            if (!statusChipIdValid(id) || mode !in setOf("show", "hide")) {
                return@registerAction ActionExecutionResult(false, message = "Invalid status chip operation or ID")
            }
            val title = feature.config.string("text").resolveVariables(context.variables).trim()
            if (mode == "show" && (title.isEmpty() || title.length > 48)) {
                return@registerAction ActionExecutionResult(false, message = "Chip text must contain 1–48 characters")
            }
            val iconMode = feature.config.string("iconMode", "none")
            val iconName = feature.config.string("icon").trim()
            if (mode == "show" && iconMode == "android_drawable" && !statusChipDrawableValid(iconName)) {
                return@registerAction ActionExecutionResult(false, message = "Invalid Android drawable name")
            }
            val encoded = if (mode == "show" && iconMode == "png_file") {
                val path = feature.config.string("pngPath").resolveVariables(context.variables).trim()
                val bytes = runCatching { File(path).takeIf { it.isFile && it.length() in 1L..65_536L }?.readBytes() }.getOrNull()
                if (bytes == null || !statusChipImageValid(bytes)) {
                    return@registerAction ActionExecutionResult(false, message = "PNG missing, larger than 64 KiB or exceeds 128 pixels")
                }
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            } else ""
            if (mode == "show" && iconMode !in setOf("none", "android_drawable", "png_file")) {
                return@registerAction ActionExecutionResult(false, message = "Unsupported icon method")
            }
            val result = context.capabilities.execute(CapabilityRequest(
                capability = CapabilityIds.LSPOSED,
                operationId = if (mode == "show") "status_chip.show" else "status_chip.hide",
                payload = mapOf(
                    "chipId" to ConfigValue.StringValue(id),
                    "text" to ConfigValue.StringValue(title),
                    "iconMode" to ConfigValue.StringValue(iconMode),
                    "icon" to ConfigValue.StringValue(iconName),
                    "imageBase64" to ConfigValue.StringValue(encoded),
                ),
                preferredBackendId = "lsposed",
                allowFallback = false,
            ))
            ActionExecutionResult(result.success, result.value, result.message)
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.status_chip_interaction"), FeatureKind.EVENT,
                "Status-bar chip interaction",
                "Triggered when the user taps or long-presses the YAuto chip in SystemUI",
                FeatureCategory.SYSTEM,
                fields = listOf(
                    FieldSchema.Text("chipId", "Chip ID"),
                    FieldSchema.Choice("gesture", "Interaction", options = listOf("any", "click", "long_click")),
                ),
                accessRequirements = setOf(AccessRequirement.LSPOSED),
                keywords = setOf("shortx", "status chip", "click", "long click", "status bar"),
                ownerPackId = id,
            )
        ) { feature, context ->
            if (context.event.typeId != "android.event.status_chip_interaction") return@registerEvent false
            val id = feature.config.string("chipId").trim()
            val gesture = feature.config.string("gesture", "any")
            (id.isBlank() || context.event.payload.string("chipId") == id) &&
                (gesture == "any" || context.event.payload.string("gesture") == gesture)
        }
    }
}

internal fun statusChipIdValid(value: String): Boolean =
    Regex("[a-z][a-z0-9_]{0,23}").matches(value)

internal fun statusChipDrawableValid(value: String): Boolean =
    Regex("[a-z][a-z0-9_]{0,63}").matches(value)

internal fun statusChipImageValid(bytes: ByteArray): Boolean {
    if (bytes.size !in 8..65_536 || !bytes.take(8).toByteArray().contentEquals(
            byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) return false
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    return opts.outWidth in 1..128 && opts.outHeight in 1..128
}
