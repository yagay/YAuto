package com.yagay.yauto.importer.shortx

import com.yagay.yauto.core.importer.sourceFeature
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import java.util.Base64

/**
 * Native ShortX protobuf Any payload mappings.
 *
 * Keep this deliberately conservative: recognizing a type name is not sufficient to convert it.
 * Each native mapping below also decodes the documented protobuf field layout required by the
 * YAuto feature. Unknown fields remain inside source.raw so future versions can remap them without
 * data loss.
 */
object ShortXMappings {
    fun nativeAction(any: AnyStub, importerId: String): FeatureRef? {
        val type = shortName(any.typeUrl)
        return when (type) {
            "ShowToast" -> showToast(any, importerId)
            else -> null
        }
    }

    fun suggestedActionFeature(typeUrl: String): String? = when (shortName(typeUrl)) {
        "ShowToast" -> "android.toast.show"
        "Delay" -> "core.delay"
        "LaunchApp", "LaunchAppByPkg" -> "android.app.launch"
        "WriteClipboard" -> "android.clipboard.set"
        "ShellCommand" -> "system.shell.execute"
        else -> null
    }

    private fun showToast(any: AnyStub, importerId: String): FeatureRef? {
        // ShortX ShowToast: string message = 1.
        val message = ProtoFields(any.value).string(1) ?: return null
        return sourceFeature(
            targetTypeId = "android.toast.show",
            importerId = importerId,
            sourceType = any.typeUrl,
            raw = Base64.getEncoder().encodeToString(any.value),
            extra = mapOf("text" to ConfigValue.StringValue(message)),
        )
    }

    private fun shortName(typeUrl: String): String = typeUrl
        .substringAfterLast('/')
        .substringAfterLast('.')
        .substringAfterLast('$')
}

/** Tiny field reader for individual ShortX action messages. */
internal class ProtoFields(bytes: ByteArray) {
    private val fields = Wire(bytes).fields()

    fun string(number: Int): String? = fields
        .firstOrNull { it.number == number && it.wire == 2 }
        ?.bytes
        ?.toString(Charsets.UTF_8)
        ?.takeIf { it.isNotEmpty() }

    fun varint(number: Int): Long? = fields
        .firstOrNull { it.number == number && it.wire == 0 }
        ?.varint
}
