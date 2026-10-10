package com.yagay.yauto.importer.shortx

import com.yagay.yauto.core.importer.sourceFeature
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import java.util.Base64
import kotlinx.serialization.json.*


/** Native ShortX basic action conversion helpers. */
internal fun ShortXMappings.expandNotification(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields()) return null
        return binaryFeature(
            any,
            importerId,
            "android.status_bar.control",
            mapOf("mode" to ConfigValue.StringValue("notifications")),
        )
    }

internal fun ShortXMappings.requestAudioFocus(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1)) return null
        val request = fields.varint(1)?.let { it != 0L } ?: return null
        return binaryFeature(
            any,
            importerId,
            if (request) "android.audio.focus.request" else "android.audio.focus.abandon",
            if (request) mapOf("gain" to ConfigValue.StringValue("gain")) else emptyMap(),
        )
    }

internal fun ShortXMappings.playRingtone(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1)) return null
        val ringtone = fields.bytes(1)?.let(::ProtoFields) ?: return null
        val uri = ringtone.string(2)?.takeIf { it.isNotBlank() } ?: return null
        return binaryFeature(
            any,
            importerId,
            "android.audio.play",
            mapOf(
                "source" to ConfigValue.StringValue(uri),
                "volume" to ConfigValue.NumberValue(100.0),
                "loop" to ConfigValue.BooleanValue(false),
                "waitForCompletion" to ConfigValue.BooleanValue(false),
            ),
        )
    }

internal fun ShortXMappings.noBusinessFact(
        any: AnyStub,
        importerId: String,
        fields: ProtoFields,
        target: String,
        extra: Map<String, ConfigValue> = emptyMap(),
    ): FeatureRef? {
        if (!fields.onlyBusinessFields()) return null
        return binaryFeature(any, importerId, target, extra)
    }

internal fun ShortXMappings.onOffAnyFact(
        any: AnyStub,
        importerId: String,
        fields: ProtoFields,
        target: String,
        wifiAdapter: Boolean = false,
    ): FeatureRef? {
        if (!fields.onlyBusinessFields(1)) return null
        val mode = when (fields.varint(1)?.toInt() ?: 2) {
            0 -> if (wifiAdapter) "enabled" else "on"
            1 -> if (wifiAdapter) "disabled" else "off"
            2 -> "any"
            else -> return null
        }
        return binaryFeature(any, importerId, target, mapOf("state" to ConfigValue.StringValue(mode)))
    }

internal fun ShortXMappings.noBusinessCondition(
        any: AnyStub,
        importerId: String,
        fields: ProtoFields,
        target: String,
        extra: Map<String, ConfigValue>,
    ): FeatureRef? {
        if (!fields.onlyBusinessFields()) return null
        return binaryFeature(any, importerId, target, extra)
    }

internal fun ShortXMappings.jsonArrayEmpty(obj: JsonObject, key: String): Boolean =
        (obj[key] as? JsonArray)?.isEmpty() != false

internal fun ShortXMappings.noAction(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1)) return null
        return binaryFeature(any, importerId, "core.noop", emptyMap())
    }

internal fun ShortXMappings.mediaPlayback(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1)) return null
        val mode = (fields.varint(1) ?: 0L).takeIf { it in 0L..6L }?.toInt() ?: return null
        val command = shortXMediaPlaybackCommand(mode) ?: return null
        return binaryFeature(any, importerId, "android.media.transport",
            mapOf("command" to ConfigValue.StringValue(command)))
    }

internal fun ShortXMappings.setVolume(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1, 2)) return null
        val type = fields.varint(1)?.takeIf { it in 0L..5L }?.toInt() ?: return null
        val index = fields.varint(2)?.takeIf { it in 0L..1000L }?.toInt() ?: return null
        val stream = shortXStreamFromAndroidType(type) ?: return null
        if (index !in 0..1000) return null
        return binaryFeature(any, importerId, "android.audio.volume.set", mapOf(
            "stream" to ConfigValue.StringValue(stream),
            "unit" to ConfigValue.StringValue("index"),
            "index" to ConfigValue.NumberValue(index.toDouble()),
        ))
    }

internal fun ShortXMappings.jsonBusinessKeysOnly(obj: JsonObject, vararg allowed: String): Boolean =
        obj.keys.all { it in SOURCE_METADATA_KEYS || it in allowed }

internal fun ShortXMappings.showToast(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        val message = fields.string(1) ?: return null
        return binaryFeature(any, importerId, "android.toast.show",
            mapOf("text" to ConfigValue.StringValue(message)))
    }

internal fun ShortXMappings.delay(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        val value = fields.string(2)?.toDoubleOrNull() ?: fields.varint(1)?.toDouble() ?: return null
        val unit = fields.varint(5) ?: 0L
        val millis = durationMs(value, unit) ?: return null
        return binaryFeature(any, importerId, "core.delay",
            mapOf("durationMs" to ConfigValue.NumberValue(millis)))
    }

internal fun ShortXMappings.launchApp(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        val appPkg = fields.bytes(1)?.let(::ProtoFields) ?: return null
        val packageName = appPkg.string(1)?.takeIf { it.isNotBlank() } ?: return null
        val userId = appPkg.varint(2) ?: 0L
        if (userId != 0L) return null
        return binaryFeature(any, importerId, "android.app.launch",
            mapOf("package" to ConfigValue.StringValue(packageName)))
    }

internal fun ShortXMappings.writeClipboard(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.string(2).isNullOrBlank()) return null
        val text = fields.string(1) ?: return null
        return binaryFeature(any, importerId, "android.clipboard.set",
            mapOf("text" to ConfigValue.StringValue(text)))
    }

internal fun ShortXMappings.inputText(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        val text = fields.string(1) ?: return null
        return binaryFeature(any, importerId, "accessibility.input_text",
            mapOf("text" to ConfigValue.StringValue(text)))
    }

internal fun ShortXMappings.inputTap(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        val x = protoNumber(fields, 3, 1) ?: return null
        val y = protoNumber(fields, 4, 2) ?: return null
        if (x < 0 || y < 0) return null
        return binaryFeature(any, importerId, "accessibility.gesture.tap",
            mapOf(
                "x" to ConfigValue.NumberValue(x),
                "y" to ConfigValue.NumberValue(y),
                "durationMs" to ConfigValue.NumberValue(40.0),
            ))
    }

internal fun ShortXMappings.inputSwipe(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        val x1 = protoNumber(fields, 11, 1) ?: return null
        val y1 = protoNumber(fields, 12, 2) ?: return null
        val x2 = protoNumber(fields, 13, 3) ?: return null
        val y2 = protoNumber(fields, 14, 4) ?: return null
        val duration = protoNumber(fields, 15, 5) ?: return null
        if (listOf(x1, y1, x2, y2).any { it < 0 } || duration <= 0) return null
        return binaryFeature(any, importerId, "accessibility.gesture.swipe",
            mapOf(
                "x1" to ConfigValue.NumberValue(x1), "y1" to ConfigValue.NumberValue(y1),
                "x2" to ConfigValue.NumberValue(x2), "y2" to ConfigValue.NumberValue(y2),
                "durationMs" to ConfigValue.NumberValue(duration),
            ))
    }

internal fun ShortXMappings.clickViewId(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        val viewId = fields.string(1)?.takeIf { it.isNotBlank() } ?: return null
        if ((fields.varint(2) ?: 0L) != 0L) return null
        if ((fields.varint(3) ?: 0L) != 0L) return null
        return binaryFeature(any, importerId, "accessibility.click_view_id",
            mapOf("viewId" to ConfigValue.StringValue(viewId)))
    }

internal fun ShortXMappings.booleanToggle(any: AnyStub, importerId: String, fields: ProtoFields, target: String): FeatureRef? {
        if (!fields.onlyBusinessFields(1)) return null
        val enabled = fields.varint(1)?.let { it != 0L } ?: return null
        return binaryFeature(any, importerId, target, mapOf("enabled" to ConfigValue.BooleanValue(enabled)))
    }

internal fun ShortXMappings.setDataEnabled(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1, 2, 3)) return null
        if ((fields.varint(2) ?: 0L) != 0L) return null
        val enabled = fields.varint(1)?.let { it != 0L } ?: return null
        return binaryFeature(any, importerId, "android.mobile_data.set", mapOf("enabled" to ConfigValue.BooleanValue(enabled)))
    }

internal fun ShortXMappings.setDarkMode(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1)) return null
        val enabled = fields.varint(1)?.let { it != 0L } ?: return null
        return binaryFeature(any, importerId, "android.display.dark_mode.set",
            mapOf("mode" to ConfigValue.StringValue(if (enabled) "dark" else "light")))
    }

internal fun ShortXMappings.setMasterSync(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1)) return null
        val mode = fields.varint(1)?.toInt() ?: return null
        if (mode !in 0..1) return null
        return binaryFeature(any, importerId, "android.sync.master.set",
            mapOf("enabled" to ConfigValue.BooleanValue(mode == 0)))
    }

internal fun ShortXMappings.setRingerMode(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1)) return null
        val mode = fields.varint(1)?.toInt() ?: return null
        if (mode !in 0..2) return null
        return binaryFeature(any, importerId, "android.audio.ringer_mode.set",
            mapOf("mode" to ConfigValue.StringValue(listOf("silent", "vibrate", "normal")[mode])))
    }

internal fun ShortXMappings.setScreenRotate(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1)) return null
        val degree = fields.varint(1)?.toInt() ?: return null
        if (degree !in 0..3) return null
        return binaryFeature(any, importerId, "android.display.rotation.set",
            mapOf("rotation" to ConfigValue.StringValue(listOf("0", "90", "180", "270")[degree])))
    }

internal fun ShortXMappings.setScreenTimeout(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1)) return null
        val timeout = fields.varint(1)?.takeIf { it > 0L } ?: return null
        return binaryFeature(any, importerId, "android.display.screen_timeout.set",
            mapOf("timeoutMs" to ConfigValue.NumberValue(timeout.toDouble())))
    }


    /**
     * ShortX slots must already belong to YAuto; otherwise rewriting an Android-owned or
     * third-party slot into YAuto's private prefix would silently change the imported action.
     *
     * Allow only the five equivalent platform drawable aliases supported by our native action.
     */
    /**
     * Return the actual callback chains rather than dropping them. The caller must
     * create separate, chip-ID-filtered YAuto automations for click and long-click.
     * Only ShortX Rule action sites use this mapping; functions/DA with independent
     * scope remain compatibility nodes when they carry callbacks.
     */
