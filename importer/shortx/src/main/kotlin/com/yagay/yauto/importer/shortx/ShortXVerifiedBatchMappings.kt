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
    private fun bool(key: String, value: Boolean) = key to ConfigValue.BooleanValue(value)

    private fun toggle(any: AnyStub, importerId: String, target: String): FeatureRef =
        feature(any, importerId, target, mapOf(bool("toggleCurrent", true)))

    private fun sourceNumber(raw: String): Double? {
        val n = raw.toDoubleOrNull() ?: return null
        return n.takeIf { it.isFinite() && it >= 0.0 && it <= 100_000.0 }
    }

    private fun inputTap(any: AnyStub, importerId: String, xs: String, ys: String): FeatureRef? {
        val x = sourceNumber(xs) ?: return null
        val y = sourceNumber(ys) ?: return null
        return feature(any, importerId, "accessibility.gesture.tap", mapOf(
            "x" to ConfigValue.NumberValue(x), "y" to ConfigValue.NumberValue(y)
        ))
    }

    private fun inputSwipe(any: AnyStub, importerId: String, v: List<String>): FeatureRef? {
        if (v.size != 5) return null
        val coordinates = v.take(4).map { sourceNumber(it) ?: return null }
        val duration = v[4].toLongOrNull() ?: return null
        if (duration !in 1L..60_000L) return null
        return feature(any, importerId, "accessibility.gesture.swipe", mapOf(
            "x1" to ConfigValue.NumberValue(coordinates[0]),
            "y1" to ConfigValue.NumberValue(coordinates[1]),
            "x2" to ConfigValue.NumberValue(coordinates[2]),
            "y2" to ConfigValue.NumberValue(coordinates[3]),
            "durationMs" to ConfigValue.NumberValue(duration.toDouble()),
        ))
    }

    private fun volumeDirection(n: Int): String? = when (n) {
        0 -> "same"; 1 -> "raise"; -1 -> "lower"; -100 -> "mute"; 100 -> "unmute"; 101 -> "toggle_mute"
        else -> null
    }

    private fun volume(any: AnyStub, importerId: String, raw: Int, showUi: Boolean): FeatureRef? =
        volumeDirection(raw)?.let { direction ->
            feature(any, importerId, "android.audio.volume.adjust", mapOf(
                text("direction", direction),
                bool("showUi", showUi),
                bool("global", true),
            ))
        }

    private fun sourceRegexReplace(any: AnyStub, importerId: String,
                                   source: String, pattern: String, replacement: String): FeatureRef? {
        if (pattern.isEmpty()) return null
        return feature(any, importerId, "data.regex.replace", mapOf(
            text("text", source), text("pattern", pattern), text("replacement", replacement),
            text("resultVariable", "replaceResult"),
        ))
    }

    private fun writeClipboard(any: AnyStub, importerId: String, value: String): FeatureRef =
        feature(any, importerId, "android.clipboard.write", mapOf(text("text", value)))

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
            types.any { it !in 0..1 } || (!hide && types.toSet() != setOf(0, 1))) return null
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

    private fun brightness(any: AnyStub, importerId: String, value: Int): FeatureRef? {
        if (value !in 0..255) return null
        // Native YAuto executor rounds 255 * percent / 100 to an integer.
        // Passing the exact fraction round-trips all 256 ShortX brightness levels.
        return feature(any, importerId, "android.display.brightness.set", mapOf(
            text("mode", "manual"),
            "percent" to ConfigValue.NumberValue(value.toDouble() * 100.0 / 255.0),
        ))
    }

    private val componentName = Regex("[A-Za-z_][A-Za-z0-9_.]*/[A-Za-z_.$][A-Za-z0-9_.$]*")
    private fun clickTile(any: AnyStub, importerId: String, target: String, longClick: Boolean): FeatureRef? {
        if (longClick || !componentName.matches(target)) return null
        return feature(any, importerId, "android.qs_tile.click", mapOf(text("component", target)))
    }

    private val pkgName = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+")
    private fun appAction(any: AnyStub, importerId: String, pkg: String, user: Int, op: String,
                          enabled: Boolean?): FeatureRef? {
        if (!pkgName.matches(pkg) || user !in 0..99) return null
        val (target, option) = when (op) {
            "stop" -> "android.app.force_stop" to null
            "enable" -> "android.app.enabled.set" to "enabled"
            "suspend" -> "android.app.suspended.set" to "suspended"
            "inactive" -> "android.app.inactive.set" to "inactive"
            else -> return null
        }
        val extra = mapOf(text("package", pkg), number("userId", user)) +
            (if (option == null) emptyMap() else mapOf(option to ConfigValue.BooleanValue(enabled ?: true)))
        return feature(any, importerId, target, extra)
    }

    private fun binaryApp(any: AnyStub, importerId: String, fields: ProtoFields,
                          byPkg: Boolean, op: String): FeatureRef? {
        val boolField = when (op) { "enable", "suspend" -> 3; else -> null }
        val pairBoolField = when (op) { "enable", "suspend" -> 2; else -> null }
        val fieldNumber = if (byPkg) pairBoolField else boolField
        if (!fields.onlyBusinessFields(*(if (byPkg) {
                if (fieldNumber == null) intArrayOf(1) else intArrayOf(1, fieldNumber)
            } else {
                if (fieldNumber == null) intArrayOf(1, 2) else intArrayOf(1, 2, fieldNumber)
            }))) return null
        if (!byPkg && fields.has(2)) return null // no package set, even malformed entries
        val items = fields.allBytes(1)
        if (items.size != 1) return null // do not silently drop other target apps
        val app = runCatching { ProtoFields(items.single()) }.getOrNull() ?: return null
        if (!app.onlyBusinessFields(1, 2)) return null
        val pkg = app.string(1) ?: return null
        val user = if (byPkg) {
            val userString = app.string(2) ?: return null
            userString.toIntOrNull() ?: return null
        } else {
            val id = app.varint(2) ?: if (!app.has(2)) 0L else return null
            if (id !in 0L..99L) return null
            id.toInt()
        }
        val enabled = if (fieldNumber == null) true else {
            val raw = fields.varint(fieldNumber) ?: if (!fields.has(fieldNumber)) 0L else return null
            if (raw !in 0L..1L) return null
            raw == 1L
        }
        return appAction(any, importerId, pkg, user, op, enabled)
    }

    private fun jsonApp(any: AnyStub, importerId: String, obj: JsonObject,
                        byPkg: Boolean, op: String): FeatureRef? {
        val listKey = if (byPkg) "pkgAndUsers" else "appPkg"
        val boolKey = when (op) { "enable" -> "enable"; "suspend" -> "suspend"; else -> null }
        val keys = setOf(listKey) + (if (byPkg) emptySet() else setOf("pkgSets")) +
            (if (boolKey == null) emptySet() else setOf(boolKey))
        if (obj.keys.any { it !in sourceMetadata && it !in keys }) return null
        if (!byPkg && "pkgSets" in obj) {
            val sets = obj["pkgSets"] as? JsonArray ?: return null
            if (sets.isNotEmpty()) return null
        }
        val items = obj[listKey] as? JsonArray ?: return null
        if (items.size != 1) return null
        val item = items.single() as? JsonObject ?: return null
        val pkg: String
        val user: Int
        if (byPkg) {
            if (item.keys.any { it !in setOf("first", "second") }) return null
            pkg = (item["first"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            user = (item["second"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return null
        } else {
            if (item.keys.any { it !in setOf("pkgName", "userId") }) return null
            pkg = (item["pkgName"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            user = if ("userId" in item) (item["userId"] as? JsonPrimitive)?.intOrNull ?: return null else 0
        }
        val enabled = if (boolKey == null) true else
            if (boolKey in obj) (obj[boolKey] as? JsonPrimitive)?.booleanOrNull ?: return null else false
        return appAction(any, importerId, pkg, user, op, enabled)
    }

    private fun decodePackedInsets(bytes: ByteArray): List<Long>? =
        if (bytes.size in 1..10 && bytes.all { it == 0.toByte() || it == 1.toByte() }) {
            bytes.map { it.toLong() }
        } else null

    /** Protobuf defaults are accepted only when the message has no unknown business fields. */
    fun binary(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        return when (sourceName(any)) {
            "ToggleWifi" -> if (fields.onlyBusinessFields()) toggle(any, importerId, "android.wifi.set") else null
            "ToggleBT" -> if (fields.onlyBusinessFields()) toggle(any, importerId, "android.bluetooth.set") else null
            "ToggleNFC" -> if (fields.onlyBusinessFields()) toggle(any, importerId, "android.nfc.set") else null
            "ToggleLocation" -> if (fields.onlyBusinessFields()) toggle(any, importerId, "android.location.enabled.set") else null
            "ToggleDarkMode" -> if (fields.onlyBusinessFields()) feature(any, importerId, "android.display.dark_mode.set",
                mapOf(text("mode", "toggle"))) else null
            "SetHotSpotEnabled" -> if (fields.onlyBusinessFields(1)) {
                val state = fields.varint(1) ?: if (!fields.has(1)) 0L else return null
                if (state in 0L..1L) feature(any, importerId, "android.network.tether.set",
                    mapOf(text("type", "wifi"), bool("enabled", state == 1L))) else null
            } else null
            "InputText" -> if (fields.onlyBusinessFields(1)) fields.string(1)?.let {
                feature(any, importerId, "accessibility.input_text", mapOf(text("text", it)))
            } else null
            "InputTap" -> if (fields.onlyBusinessFields(1, 2)) {
                val x = fields.string(1) ?: return null
                val y = fields.string(2) ?: return null
                inputTap(any, importerId, x, y)
            } else null
            "InputSwipe" -> if (fields.onlyBusinessFields(1, 2, 3, 4, 5)) {
                val coords = (1..5).map { fields.string(it) ?: return null }
                inputSwipe(any, importerId, coords)
            } else null
            "WriteClipboard" -> if (fields.onlyBusinessFields(1, 2) && !fields.has(2)) {
                fields.string(1)?.let { writeClipboard(any, importerId, it) }
            } else null
            "ReplaceRegex" -> if (fields.onlyBusinessFields(1, 2, 3)) {
                val input = fields.string(1) ?: return null
                val pattern = fields.string(2) ?: return null
                val replacement = fields.string(3) ?: return null
                sourceRegexReplace(any, importerId, input, pattern, replacement)
            } else null
            "AdjustVolume" -> if (fields.onlyBusinessFields(1, 2)) {
                val direction = fields.varint(1) ?: if (!fields.has(1)) 0L else return null
                val ui = fields.varint(2) ?: if (!fields.has(2)) 0L else return null
                if (direction !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() || ui !in 0L..1L) null
                else volume(any, importerId, direction.toInt(), ui == 1L)
            } else null
            "ReadClipboard" ->
                if (fields.onlyBusinessFields()) feature(any, importerId, "android.clipboard.read",
                    mapOf(text("resultVariable", "clipboardContent"))) else null
            "DisconnectCurrentWifi" ->
                if (fields.onlyBusinessFields()) feature(any, importerId, "android.wifi.network.disconnect", emptyMap()) else null
            "ToggleAutoBrightness" ->
                if (fields.onlyBusinessFields()) feature(any, importerId, "android.display.brightness.set",
                    mapOf(text("mode", "toggle_auto"))) else null
            "StopApp" -> binaryApp(any, importerId, fields, false, "stop")
            "StopAppByPkg" -> binaryApp(any, importerId, fields, true, "stop")
            "SetAppEnabled" -> binaryApp(any, importerId, fields, false, "enable")
            "SetAppEnabledByPkg" -> binaryApp(any, importerId, fields, true, "enable")
            "SetAppSuspend" -> binaryApp(any, importerId, fields, false, "suspend")
            "SetAppSuspendByPkg" -> binaryApp(any, importerId, fields, true, "suspend")
            "SetAppInactive" -> binaryApp(any, importerId, fields, false, "inactive")
            "SetAppInactiveByPkg" -> binaryApp(any, importerId, fields, true, "inactive")
            "SetBrightness" -> if (fields.onlyBusinessFields(1)) {
                val raw = fields.varint(1) ?: if (!fields.has(1)) 0L else return null
                if (raw in 0L..255L) brightness(any, importerId, raw.toInt()) else null
            } else null
            "ClickTile" -> if (fields.onlyBusinessFields(1, 2)) {
                val tile = fields.bytes(1)?.let { runCatching { ProtoFields(it) }.getOrNull() } ?: return null
                if (!tile.onlyBusinessFields(1, 2)) return null
                val spec = tile.string(1) ?: return null
                val long = fields.varint(2) ?: if (!fields.has(2)) 0L else return null
                if (long !in 0L..1L) null else clickTile(any, importerId, spec, long == 1L)
            } else null
            "ShowHideInsets" -> if (fields.onlyBusinessFields(1, 2)) {
                val hide = fields.varint(1) ?: if (!fields.has(1)) 0L else return null
                if (hide !in 0L..1L) return null
                val direct = fields.allVarints(2)
                val packed = fields.allBytes(2).flatMap { bytes ->
                    decodePackedInsets(bytes) ?: return null
                }
                val types = direct + packed
                if (types.any { it !in 0L..1L }) null
                else insets(any, importerId, hide == 1L, types.map(Long::toInt))
            } else null
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
            "ToggleWifi" -> if (allowed()) toggle(any, importerId, "android.wifi.set") else null
            "ToggleBT" -> if (allowed()) toggle(any, importerId, "android.bluetooth.set") else null
            "ToggleNFC" -> if (allowed()) toggle(any, importerId, "android.nfc.set") else null
            "ToggleLocation" -> if (allowed()) toggle(any, importerId, "android.location.enabled.set") else null
            "ToggleDarkMode" -> if (allowed()) feature(any, importerId, "android.display.dark_mode.set",
                mapOf(text("mode", "toggle"))) else null
            "SetHotSpotEnabled" -> if (allowed("enable")) {
                val enabled = (obj["enable"] as? JsonPrimitive)?.booleanOrNull
                    ?: if ("enable" in obj) return null else false
                feature(any, importerId, "android.network.tether.set",
                    mapOf(text("type", "wifi"), bool("enabled", enabled)))
            } else null
            "InputText" -> if (allowed("text")) {
                (obj["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let {
                    feature(any, importerId, "accessibility.input_text", mapOf(text("text", it)))
                }
            } else null
            "InputTap" -> if (allowed("xs", "ys")) {
                val x = (obj["xs"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                val y = (obj["ys"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                inputTap(any, importerId, x, y)
            } else null
            "InputSwipe" -> if (allowed("startXS", "startYS", "endXS", "endYS", "swipeTimeS")) {
                val values = listOf("startXS", "startYS", "endXS", "endYS", "swipeTimeS").map {
                    (obj[it] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null
                }
                inputSwipe(any, importerId, values)
            } else null
            "WriteClipboard" -> if (allowed("text", "filePath") &&
                (obj["filePath"] == null || (obj["filePath"] as? JsonPrimitive)?.contentOrNull == "")) {
                (obj["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let {
                    writeClipboard(any, importerId, it)
                }
            } else null
            "ReplaceRegex" -> if (allowed("string", "regex", "replacement")) {
                val input = (obj["string"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                val pattern = (obj["regex"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                val replacement = (obj["replacement"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                sourceRegexReplace(any, importerId, input, pattern, replacement)
            } else null
            "AdjustVolume" -> if (allowed("direction", "showUI")) {
                val d = (obj["direction"] as? JsonPrimitive)?.intOrNull ?: if ("direction" in obj) return null else 0
                val ui = (obj["showUI"] as? JsonPrimitive)?.booleanOrNull ?: if ("showUI" in obj) return null else false
                volume(any, importerId, d, ui)
            } else null
            "ReadClipboard" -> if (allowed()) feature(any, importerId, "android.clipboard.read",
                mapOf(text("resultVariable", "clipboardContent"))) else null
            "DisconnectCurrentWifi" -> if (allowed()) feature(any, importerId, "android.wifi.network.disconnect", emptyMap()) else null
            "ToggleAutoBrightness" -> if (allowed()) feature(any, importerId, "android.display.brightness.set",
                mapOf(text("mode", "toggle_auto"))) else null
            "StopApp" -> jsonApp(any, importerId, obj, false, "stop")
            "StopAppByPkg" -> jsonApp(any, importerId, obj, true, "stop")
            "SetAppEnabled" -> jsonApp(any, importerId, obj, false, "enable")
            "SetAppEnabledByPkg" -> jsonApp(any, importerId, obj, true, "enable")
            "SetAppSuspend" -> jsonApp(any, importerId, obj, false, "suspend")
            "SetAppSuspendByPkg" -> jsonApp(any, importerId, obj, true, "suspend")
            "SetAppInactive" -> jsonApp(any, importerId, obj, false, "inactive")
            "SetAppInactiveByPkg" -> jsonApp(any, importerId, obj, true, "inactive")
            "SetBrightness" -> if (allowed("value")) {
                val value = if ("value" in obj) field("value") else 0
                value?.let { brightness(any, importerId, it) }
            } else null
            "ClickTile" -> if (allowed("tile", "isLongClick")) {
                val tile = obj["tile"] as? JsonObject ?: return null
                if (tile.keys.any { it !in setOf("tileSpec", "label") }) return null
                val spec = (tile["tileSpec"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                val long = (obj["isLongClick"] as? JsonPrimitive)?.booleanOrNull
                    ?: if ("isLongClick" in obj) return null else false
                clickTile(any, importerId, spec, long)
            } else null
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
