package com.yagay.yauto.importer.shortx

import com.yagay.yauto.core.importer.sourceFeature
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import java.util.Base64
import kotlinx.serialization.json.*

/**
 * ShortX actions that use existing YAuto executors, with shared binary/JSON semantics.
 * A similar feature name or suggestion is never enough: reject unrepresentable modes
 * and fields so the original ShortX action remains a compatibility node.
 */
internal object ShortXVerifiedBatchMappings {
    private val sourceMetadata = setOf(
        "@type", "type", "typeUrl", "type_url", "id", "isDisabled", "note", "actionOnError",
    )
    private fun sourceName(any: AnyStub): String =
        any.typeUrl.substringAfterLast('/').substringAfterLast('.').substringAfterLast('$')

    private fun feature(any: AnyStub, importerId: String, target: String, extras: Map<String, ConfigValue>): FeatureRef =
        sourceFeature(
            targetTypeId = target, importerId = importerId, sourceType = any.typeUrl,
            raw = if (any.isJson) any.value.toString(Charsets.UTF_8)
                  else Base64.getEncoder().encodeToString(any.value),
            extra = extras,
        )

    private fun text(key: String, value: String) = key to ConfigValue.StringValue(value)
    private fun number(key: String, value: Int) = key to ConfigValue.NumberValue(value.toDouble())

    private fun scrollLocation(value: Int): String? = when (value) {
        0 -> "top"
        1 -> "bottom"
        2 -> "top_force"
        3 -> "bottom_force"
        4 -> "forward"
        5 -> "backward"
        else -> null
    }

    private fun scroll(any: AnyStub, importerId: String, mode: Int): FeatureRef? =
        scrollLocation(mode)?.let {
            feature(any, importerId, "accessibility.scroll_to", mapOf(text("location", it)))
        }

    private fun fiveG(any: AnyStub, importerId: String, state: Int, slot: Int): FeatureRef? {
        if (slot !in 0..1) return null
        val operation = when (state) {
            0 -> "enable"
            1 -> "disable"
            2 -> "toggle"
            else -> return null
        }
        return feature(any, importerId, "android.telephony.5g.toggle",
            mapOf(text("operation", operation), number("slotId", slot)))
    }

    private fun vibrate(any: AnyStub, importerId: String, first: Int, gap: Int, last: Int): FeatureRef? {
        // Android's VibrationEffect waveform alternates OFF, ON, OFF, ON.
        if (first !in 1..60_000 || gap !in 0..60_000 || last !in 1..60_000 ||
            first.toLong() + gap + last > 120_000L) return null
        return feature(any, importerId, "android.vibration.pattern",
            mapOf(text("timings", "0,$first,$gap,$last")))
    }

    private fun insets(any: AnyStub, importerId: String, hide: Boolean, types: List<Int>): FeatureRef? {
        if (types.isEmpty() || types.distinct().size != types.size ||
            types.any { it !in 0..1 }) return null
        val mode = if (!hide) "show_all" else when (types.toSet()) {
            setOf(0, 1) -> "hide_all"
            setOf(0) -> "hide_status"
            setOf(1) -> "hide_navigation"
            else -> return null
        }
        return feature(any, importerId, "android.insets.immersive.set", mapOf(text("mode", mode)))
    }

    private fun lock(any: AnyStub, importerId: String) =
        feature(any, importerId, "accessibility.global_action", mapOf(text("action", "lock_screen")))

    /** Protobuf defaults are accepted only when the message has no unknown business fields. */
    fun binary(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        return when (sourceName(any)) {
            "LockDeviceNow" ->
                if (fields.onlyBusinessFields()) lock(any, importerId) else null
            "ScrollViewTo" ->
                if (fields.onlyBusinessFields(1)) fields.varint(1)?.toInt()?.let {
                    scroll(any, importerId, it)
                } ?: if (!fields.has(1)) scroll(any, importerId, 0) else null else null
            "Toggle5G" -> {
                if (!fields.onlyBusinessFields(1, 2)) null
                else {
                    val operation = fields.varint(1) ?: if (!fields.has(1)) 0L else return null
                    val slot = fields.varint(2) ?: if (!fields.has(2)) 0L else return null
                    if (operation !in 0L..2L || slot !in 0L..1L) null
                    else fiveG(any, importerId, operation.toInt(), slot.toInt())
                }
            }
            "Vibrate" -> {
                if (!fields.onlyBusinessFields(1, 2, 3)) null
                else {
                    val first = fields.varint(1) ?: return null
                    val gap = fields.varint(2) ?: return null
                    val last = fields.varint(3) ?: return null
                    if (first !in 1L..60_000L || gap !in 0L..60_000L || last !in 1L..60_000L) null
                    else vibrate(any, importerId, first.toInt(), gap.toInt(), last.toInt())
                }
            }
            // ShowHideInsets.type is a repeated enum. ProtoFields needs to expose every element,
            // including packed variants, before it can be mapped without discarding selections.
            else -> null
        }
    }

    private fun enumNumber(value: JsonPrimitive?): Int? = value?.intOrNull
        ?: value?.contentOrNull?.substringAfterLast('_')?.let { name ->
            when (name.lowercase()) {
                "top" -> 0; "bottom" -> 1; "topforce" -> 2
                "bottomforce" -> 3; "forward" -> 4; "backward" -> 5
                else -> null
            }
        }

    private fun toggleState(value: JsonPrimitive?): Int? =
        if (value == null) 0 else value.intOrNull ?: when (value.contentOrNull?.substringAfterLast('_')?.lowercase()) {
            "on" -> 0; "off" -> 1; "toggle" -> 2
            else -> null
        }

    /** Ignore neither unknown business properties nor malformed enum values. */
    fun json(any: AnyStub, importerId: String, obj: JsonObject): FeatureRef? {
        fun allowed(vararg keys: String): Boolean =
            obj.keys.all { it in sourceMetadata || it in keys }
        fun field(name: String): Int? = (obj[name] as? JsonPrimitive)?.intOrNull
        return when (sourceName(any)) {
            "LockDeviceNow" -> if (allowed()) lock(any, importerId) else null
            "ScrollViewTo" ->
                if (allowed("location")) {
                    val location = if ("location" in obj) enumNumber(obj["location"] as? JsonPrimitive) else 0
                    location?.let { scroll(any, importerId, it) }
                } else null
            "Toggle5G" ->
                if (allowed("onOff", "slotId")) {
                    val state = toggleState(obj["onOff"] as? JsonPrimitive)
                    val slot = if ("slotId" in obj) field("slotId") else 0
                    if (state != null && slot != null) fiveG(any, importerId, state, slot) else null
                } else null
            "Vibrate" ->
                if (allowed("vib1", "vib2", "vib3")) {
                    val a = field("vib1")
                    val b = field("vib2")
                    val c = field("vib3")
                    if (a != null && b != null && c != null) vibrate(any, importerId, a, b, c) else null
                } else null
            "ShowHideInsets" ->
                if (allowed("isHide", "type")) {
                    val hidden = (obj["isHide"] as? JsonPrimitive)?.booleanOrNull ?: if ("isHide" in obj) return null else false
                    val types = obj["type"] as? JsonArray ?: return null
                    val values = types.map { type ->
                        val p = type as? JsonPrimitive ?: return null
                        p.intOrNull ?: when (p.contentOrNull?.substringAfterLast('_')?.lowercase()) {
                            "statusbar" -> 0; "navbar" -> 1; else -> return null
                        }
                    }
                    insets(any, importerId, hidden, values)
                } else null
            else -> null
        }
    }
}
