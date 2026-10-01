package com.yagay.yauto.importer.shortx

import com.yagay.yauto.core.importer.sourceFeature
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import java.util.Base64
import kotlinx.serialization.json.*

/**
 * Native ShortX protobuf Any payload mappings.
 *
 * Keep this deliberately conservative: recognizing a type name is not sufficient to convert it.
 * Each native mapping below also decodes the documented protobuf field layout required by the
 * YAuto feature. Unknown fields remain inside source.raw so future versions can remap them without
 * data loss.
 */
internal object ShortXMappings {
    fun nativeAction(any: AnyStub, importerId: String): FeatureRef? {
        if (any.isJson) return nativeJsonAction(any, importerId)
        val type = shortName(any.typeUrl)
        val fields = runCatching { ProtoFields(any.value) }.getOrNull() ?: return null
        if (fields.varint(97)?.let { it != 0L } == true || fields.has(96)) return null
        return when (type) {
            "ShowToast" -> showToast(any, importerId, fields)
            "Delay" -> delay(any, importerId, fields)
            "LaunchApp" -> launchApp(any, importerId, fields)
            "WriteClipboard" -> writeClipboard(any, importerId, fields)
            else -> null
        }
    }

    fun enabled(any: AnyStub): Boolean = if (any.isJson) {
        val obj = Json.parseToJsonElement(any.value.toString(Charsets.UTF_8)).jsonObject
        (obj["isDisabled"] as? JsonPrimitive)?.booleanOrNull != true
    } else ProtoFields(any.value).varint(98) != 1L

    fun note(any: AnyStub): String? = if (any.isJson) {
        (Json.parseToJsonElement(any.value.toString(Charsets.UTF_8)).jsonObject["note"] as? JsonPrimitive)?.contentOrNull
    } else ProtoFields(any.value).string(99)

    private fun nativeJsonAction(any: AnyStub, importerId: String): FeatureRef? {
        val obj = Json.parseToJsonElement(any.value.toString(Charsets.UTF_8)) as? JsonObject ?: return null
        if (obj["customContextDataKey"] != null || (obj["actionOnError"] as? JsonPrimitive)?.contentOrNull?.let { it !in setOf("0", "") } == true) return null
        return when (shortName(any.typeUrl)) {
            "ShowToast" -> {
                val message = (obj["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                sourceFeature("android.toast.show", importerId, any.typeUrl, any.value.toString(Charsets.UTF_8),
                    extra = mapOf("text" to ConfigValue.StringValue(message)))
            }
            "Delay" -> {
                val value = (obj["timeString"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                    ?: (obj["time"] as? JsonPrimitive)?.doubleOrNull
                    ?: return null
                val unit = jsonTimeUnit(obj["timeUnit"] as? JsonPrimitive) ?: 0L
                val millis = durationMs(value, unit) ?: return null
                sourceFeature("core.delay", importerId, any.typeUrl, any.value.toString(Charsets.UTF_8),
                    extra = mapOf("durationMs" to ConfigValue.NumberValue(millis)))
            }
            "LaunchApp" -> {
                val appPkg = obj["appPkg"] as? JsonObject ?: return null
                val pkg = (appPkg["pkgName"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
                val user = (appPkg["userId"] as? JsonPrimitive)?.intOrNull ?: 0
                if (user != 0) return null
                sourceFeature("android.app.launch", importerId, any.typeUrl, any.value.toString(Charsets.UTF_8),
                    extra = mapOf("package" to ConfigValue.StringValue(pkg)))
            }
            "WriteClipboard" -> {
                val filePath = (obj["filePath"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (filePath.isNotBlank()) return null
                val text = (obj["text"] as? JsonPrimitive)?.contentOrNull ?: return null
                sourceFeature("android.clipboard.set", importerId, any.typeUrl, any.value.toString(Charsets.UTF_8),
                    extra = mapOf("text" to ConfigValue.StringValue(text)))
            }
            else -> null
        }
    }

    fun suggestedActionFeature(typeUrl: String): String? = when (shortName(typeUrl)) {
        "ShowToast" -> "android.toast.show"
        "Delay" -> "core.delay"
        "LaunchApp", "LaunchAppByPkg" -> "android.app.launch"
        "WriteClipboard" -> "android.clipboard.set"
        "ShellCommand" -> "system.shell.execute"
        "StopApp" -> "android.app.force_stop"
        else -> null
    }

    private fun showToast(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        // ShortX Actions.proto: ShowToast.message = 1.
        val message = fields.string(1) ?: return null
        return binaryFeature(any, importerId, "android.toast.show",
            mapOf("text" to ConfigValue.StringValue(message)))
    }

    private fun delay(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        // Actions.proto: time(deprecated)=1, timeString=2, TimeUnit=5 (MS/S/M/H/D).
        val value = fields.string(2)?.toDoubleOrNull() ?: fields.varint(1)?.toDouble() ?: return null
        val unit = fields.varint(5) ?: 0L
        val millis = durationMs(value, unit) ?: return null
        return binaryFeature(any, importerId, "core.delay",
            mapOf("durationMs" to ConfigValue.NumberValue(millis)))
    }

    private fun launchApp(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        // Actions.proto: LaunchApp.appPkg = 1; Common.proto: AppPkg.pkgName = 1, userId = 2.
        val appPkg = fields.bytes(1)?.let(::ProtoFields) ?: return null
        val packageName = appPkg.string(1)?.takeIf { it.isNotBlank() } ?: return null
        val userId = appPkg.varint(2) ?: 0L
        if (userId != 0L) return null // Current YAuto launch action targets the current Android user only.
        return binaryFeature(any, importerId, "android.app.launch",
            mapOf("package" to ConfigValue.StringValue(packageName)))
    }

    private fun writeClipboard(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        // Actions.proto: WriteClipboard.text = 1, filePath = 2. File clipboard entries stay compat.
        if (!fields.string(2).isNullOrBlank()) return null
        val text = fields.string(1) ?: return null
        return binaryFeature(any, importerId, "android.clipboard.set",
            mapOf("text" to ConfigValue.StringValue(text)))
    }

    private fun binaryFeature(any: AnyStub, importerId: String, target: String, extra: Map<String, ConfigValue>) =
        sourceFeature(
            targetTypeId = target,
            importerId = importerId,
            sourceType = any.typeUrl,
            raw = Base64.getEncoder().encodeToString(any.value),
            extra = extra,
        )

    private fun durationMs(value: Double, unit: Long): Double? {
        if (!value.isFinite() || value < 0) return null
        val factor = when (unit) {
            0L -> 1.0
            1L -> 1_000.0
            2L -> 60_000.0
            3L -> 3_600_000.0
            4L -> 86_400_000.0
            else -> return null
        }
        val result = value * factor
        return result.takeIf { it.isFinite() && it <= Long.MAX_VALUE.toDouble() }
    }

    private fun jsonTimeUnit(value: JsonPrimitive?): Long? {
        value ?: return 0L
        value.longOrNull?.let { return it.takeIf { unit -> unit in 0L..4L } }
        return when (value.contentOrNull?.substringAfterLast('_')?.uppercase()) {
            "MS" -> 0L
            "S" -> 1L
            "M" -> 2L
            "H" -> 3L
            "D" -> 4L
            else -> null
        }
    }

    private fun shortName(typeUrl: String): String = typeUrl
        .substringAfterLast('/')
        .substringAfterLast('.')
        .substringAfterLast('$')
}

/** Tiny field reader for individual ShortX action messages. */
internal class ProtoFields(bytes: ByteArray) {
    private val fields = Wire(bytes).fields()
    fun has(number: Int): Boolean = fields.any { it.number == number }

    fun string(number: Int): String? = bytes(number)
        ?.toString(Charsets.UTF_8)
        ?.takeIf { it.isNotEmpty() }

    fun bytes(number: Int): ByteArray? = fields
        .firstOrNull { it.number == number && it.wire == 2 }
        ?.bytes

    fun varint(number: Int): Long? = fields
        .firstOrNull { it.number == number && it.wire == 0 }
        ?.varint
}
