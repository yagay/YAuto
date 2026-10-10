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
    private val batchOwnedSources = setOf(
        "ToggleWifi", "ToggleBT", "ToggleNFC", "ToggleLocation", "ToggleDarkMode", "ToggleData",
        "SetHotSpotEnabled", "InputText", "InputTap", "InputSwipe",
        "WriteClipboard", "ReplaceRegex", "AdjustVolume",
        "LaunchAppByPkg", "RemoveTasks", "RemoveTasksByPkg",
        "StartActivityIntentUri", "StartActivityUrlSchema", "EnableUniversalCopy", "EnableViewIdViewer",
        "ShowDrawBoard", "ParseQRCode", "ShowDanmu", "MatchRegex", "GetCurrentLocationInfo",
    )
    fun ownsSource(name: String): Boolean = name in batchOwnedSources

    internal val sourceMetadata = setOf(
        "@type", "type", "typeUrl", "type_url", "id", "isDisabled", "note", "actionOnError",
    )
    internal fun sourceName(any: AnyStub): String =
        any.typeUrl.substringAfterLast('/').substringAfterLast('.').substringAfterLast('$')

    internal fun feature(any: AnyStub, importerId: String, target: String, extras: Map<String, ConfigValue>): FeatureRef =
        sourceFeature(
            targetTypeId = target, importerId = importerId, sourceType = any.typeUrl,
            raw = if (any.isJson) any.value.toString(Charsets.UTF_8)
                  else Base64.getEncoder().encodeToString(any.value),
            extra = extras,
        )

    internal fun text(key: String, value: String) = key to ConfigValue.StringValue(value)
    internal fun bool(key: String, value: Boolean) = key to ConfigValue.BooleanValue(value)

    internal fun toggle(any: AnyStub, importerId: String, target: String): FeatureRef =
        feature(any, importerId, target, mapOf(bool("toggleCurrent", true)))

    internal fun targetsBinary(fields: ProtoFields, byPair: Boolean, allowSets: Boolean = false): List<String>? {
        if (!fields.onlyBusinessFields(*(if (allowSets) intArrayOf(1, 2) else intArrayOf(1)))) return null
        if (allowSets && fields.has(2)) return null
        val items = fields.allBytes(1)
        if (items.isEmpty() || items.size > 32) return null
        val result = items.map { bytes ->
            val child = runCatching { ProtoFields(bytes) }.getOrNull() ?: return null
            if (!child.onlyBusinessFields(1, 2)) return null
            val pkg = child.string(1) ?: return null
            val user = if (byPair) child.string(2)?.toIntOrNull() ?: return null
            else if (!child.has(2)) 0 else child.varint(2)?.toInt() ?: return null
            if (user != 0 || !pkgName.matches(pkg)) return null
            pkg
        }
        if (result.distinct().size != result.size) return null
        return result
    }

    internal fun targetsJson(obj: JsonObject, byPair: Boolean, allowSets: Boolean = false): List<String>? {
        val key = if (byPair) "pkgAndUsers" else "appPkg"
        if (obj.keys.any { it !in sourceMetadata && it != key && (!allowSets || it != "pkgSets") }) return null
        if (allowSets && obj["pkgSets"] != null) {
            if ((obj["pkgSets"] as? JsonArray)?.isNotEmpty() != false) return null
        }
        val items = obj[key] as? JsonArray ?: return null
        if (items.isEmpty() || items.size > 32) return null
        val result = items.map { value ->
            val child = value as? JsonObject ?: return null
            val legalKeys = if (byPair) setOf("first", "second") else setOf("pkgName", "userId")
            if (child.keys.any { it !in legalKeys }) return null
            val pkgKey = if (byPair) "first" else "pkgName"
            val pkg = (child[pkgKey] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val user = if (byPair) (child["second"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return null
            else if ("userId" in child) (child["userId"] as? JsonPrimitive)?.intOrNull ?: return null else 0
            if (user != 0 || !pkgName.matches(pkg)) return null
            pkg
        }
        if (result.distinct().size != result.size) return null
        return result
    }

    internal fun taskRemove(any: AnyStub, importerId: String, packages: List<String>?): FeatureRef? =
        packages?.takeIf { it.isNotEmpty() }?.let {
            feature(any, importerId, "android.tasks.remove",
                mapOf(text("packages", it.joinToString("\n")), bool("allMatching", true)))
        }

    internal fun launchSingle(any: AnyStub, importerId: String, packages: List<String>?): FeatureRef? =
        packages?.singleOrNull()?.let {
            feature(any, importerId, "android.app.launch", mapOf(text("package", it)))
        }

    internal fun noTheme(any: AnyStub, importerId: String, fields: ProtoFields, target: String): FeatureRef? {
        if (!fields.onlyBusinessFields(1)) return null
        val theme = fields.varint(1) ?: if (!fields.has(1)) 0L else return null
        if (theme != 0L) return null
        return feature(any, importerId, target, emptyMap())
    }

    internal fun noThemeJson(any: AnyStub, importerId: String, obj: JsonObject, target: String): FeatureRef? {
        if (obj.keys.any { it !in sourceMetadata && it != "themeMode" }) return null
        val mode = (obj["themeMode"] as? JsonPrimitive)?.intOrNull
            ?: if ("themeMode" in obj) return null else 0
        return if (mode == 0) feature(any, importerId, target, emptyMap()) else null
    }

    internal fun sourceNumber(raw: String): Double? {
        val n = raw.toDoubleOrNull() ?: return null
        return n.takeIf { it.isFinite() && it >= 0.0 && it <= 100_000.0 }
    }

    internal fun coordinateField(fields: ProtoFields, number: Int): String? =
        fields.string(number) ?: fields.varint(number)?.toString()

    internal fun inputTap(any: AnyStub, importerId: String, xs: String, ys: String): FeatureRef? {
        val x = sourceNumber(xs) ?: return null
        val y = sourceNumber(ys) ?: return null
        return feature(any, importerId, "accessibility.gesture.tap", mapOf(
            "x" to ConfigValue.NumberValue(x), "y" to ConfigValue.NumberValue(y)
        ))
    }

    internal fun inputSwipe(any: AnyStub, importerId: String, v: List<String>): FeatureRef? {
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

    internal fun volumeDirection(n: Int): String? = when (n) {
        0 -> "same"; 1 -> "raise"; -1 -> "lower"; -100 -> "mute"; 100 -> "unmute"; 101 -> "toggle_mute"
        else -> null
    }

    internal fun volume(any: AnyStub, importerId: String, raw: Int, showUi: Boolean): FeatureRef? =
        volumeDirection(raw)?.let { direction ->
            feature(any, importerId, "android.audio.volume.adjust", mapOf(
                text("direction", direction),
                bool("showUi", showUi),
                bool("global", true),
            ))
        }

    internal fun sourceRegexReplace(any: AnyStub, importerId: String,
                                   source: String, pattern: String, replacement: String): FeatureRef? {
        if (pattern.isEmpty()) return null
        return feature(any, importerId, "data.regex.replace", mapOf(
            text("text", source), text("pattern", pattern), text("replacement", replacement),
            text("resultVariable", "replaceResult"),
        ))
    }

    internal fun locationInfo(any: AnyStub, importerId: String, preference: Int, timeout: Long): FeatureRef? {
        if (preference !in 0..2 || timeout !in 1000L..120000L) return null
        val provider = when (preference) { 0 -> "best"; 1 -> "network"; else -> "gps" }
        return feature(any, importerId, "android.location.current.query", mapOf(
            text("provider", provider), text("resultVariable", "shortxLocation"),
            bool("shortxContextOutput", true),
            "timeoutMillis" to ConfigValue.NumberValue(timeout.toDouble())
        ))
    }

    internal fun regexMatch(any: AnyStub, importerId: String, value: String,
                           pattern: String, mode: Int): FeatureRef? {
        if (mode !in 0..1 || pattern.isEmpty()) return null
        return feature(any, importerId, "data.regex.matches", mapOf(
            text("text", value), text("pattern", pattern),
            text("matchMode", if (mode == 0) "full" else "contains"),
            text("resultVariable", "isMatch"), text("matchedTextVariable", "matchResult"),
        ))
    }

    internal fun writeClipboard(any: AnyStub, importerId: String, value: String): FeatureRef =
        feature(any, importerId, "android.clipboard.write", mapOf(text("text", value)))

    internal fun number(key: String, value: Int) = key to ConfigValue.NumberValue(value.toDouble())

    internal fun scrollLocation(value: Int): String? = when (value) {
        0 -> "top"
        1 -> "bottom"
        2 -> "top_force"
        3 -> "bottom_force"
        4 -> "forward"
        5 -> "backward"
        else -> null
    }

    internal fun scroll(any: AnyStub, importerId: String, mode: Int): FeatureRef? =
        scrollLocation(mode)?.let {
            feature(any, importerId, "accessibility.scroll_to", mapOf(text("location", it)))
        }

    internal fun fiveG(any: AnyStub, importerId: String, state: Int, slot: Int): FeatureRef? {
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

    internal fun vibrate(any: AnyStub, importerId: String, first: Int, gap: Int, last: Int): FeatureRef? {
        // Android's VibrationEffect waveform alternates OFF, ON, OFF, ON.
        if (first !in 1..60_000 || gap !in 0..60_000 || last !in 1..60_000 ||
            first.toLong() + gap + last > 120_000L) return null
        return feature(any, importerId, "android.vibration.pattern",
            mapOf(text("timings", "0,$first,$gap,$last")))
    }

    internal fun insets(any: AnyStub, importerId: String, hide: Boolean, types: List<Int>): FeatureRef? {
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

    internal fun lock(any: AnyStub, importerId: String) =
        feature(any, importerId, "accessibility.global_action", mapOf(text("action", "lock_screen")))

    internal fun brightness(any: AnyStub, importerId: String, value: Int): FeatureRef? {
        if (value !in 0..255) return null
        // Native YAuto executor rounds 255 * percent / 100 to an integer.
        // Passing the exact fraction round-trips all 256 ShortX brightness levels.
        return feature(any, importerId, "android.display.brightness.set", mapOf(
            text("mode", "manual"),
            "percent" to ConfigValue.NumberValue(value.toDouble() * 100.0 / 255.0),
        ))
    }

    private val componentName = Regex("[A-Za-z_][A-Za-z0-9_.]*/[A-Za-z_.$][A-Za-z0-9_.$]*")
    internal fun clickTile(any: AnyStub, importerId: String, target: String, longClick: Boolean): FeatureRef? {
        if (longClick || !componentName.matches(target)) return null
        return feature(any, importerId, "android.qs_tile.click", mapOf(text("component", target)))
    }

    private val pkgName = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+")
    internal fun appAction(any: AnyStub, importerId: String, pkg: String, user: Int, op: String,
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

    internal fun binaryApp(any: AnyStub, importerId: String, fields: ProtoFields,
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

    internal fun jsonApp(any: AnyStub, importerId: String, obj: JsonObject,
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

    internal fun decodePackedInsets(bytes: ByteArray): List<Long>? =
        if (bytes.size in 1..10 && bytes.all { it == 0.toByte() || it == 1.toByte() }) {
            bytes.map { it.toLong() }
        } else null

    /** Protobuf defaults are accepted only when the message has no unknown business fields. */
    fun binary(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        return when (sourceName(any)) {
            "LaunchAppByPkg" -> launchSingle(any, importerId, targetsBinary(fields, byPair = true))
            "RemoveTasks" -> taskRemove(any, importerId, targetsBinary(fields, byPair = false, allowSets = true))
            "RemoveTasksByPkg" -> taskRemove(any, importerId, targetsBinary(fields, byPair = true))
            "StartActivityUrlSchema" -> if (fields.onlyBusinessFields(1, 2)) {
                val uid = fields.varint(2) ?: if (!fields.has(2)) 0L else return null
                if (uid != 0L) null else fields.string(1)?.takeIf { it.isNotBlank() && it.contains(':') }?.let {
                    feature(any, importerId, "android.intent_uri.launch",
                        mapOf(text("intentUri", it), bool("urlSchemeMode", true)))
                }
            } else null
            "GetCurrentLocationInfo" -> if (fields.onlyBusinessFields(1, 2)) {
                val preference = fields.varint(1) ?: if (!fields.has(1)) 0L else return null
                val timeout = fields.varint(2) ?: return null
                if (preference !in 0L..2L) null
                else locationInfo(any, importerId, preference.toInt(), timeout)
            } else null
            "ShowDrawBoard" -> noTheme(any, importerId, fields, "surface.draw_board.show")?.let {
                it.copy(config = it.config + mapOf(
                    text("surfaceId", "shortx.draw_board"), text("gravity", "center")))
            }
            "MatchRegex" -> if (fields.onlyBusinessFields(1, 2, 3)) {
                val value = fields.string(1) ?: return null
                val regex = fields.string(2) ?: return null
                val mode = fields.varint(3) ?: if (!fields.has(3)) 0L else return null
                if (mode !in 0L..1L) null else regexMatch(any, importerId, value, regex, mode.toInt())
            } else null
            "StartActivityIntentUri" -> if (fields.onlyBusinessFields(1)) {
                fields.string(1)?.takeIf(String::isNotBlank)?.let {
                    feature(any, importerId, "android.intent_uri.launch", mapOf(text("intentUri", it)))
                }
            } else null
            "EnableUniversalCopy" -> noTheme(any, importerId, fields, "accessibility.universal_copy.show")
            "EnableViewIdViewer" -> noTheme(any, importerId, fields, "accessibility.view_id_viewer.show")
            "ParseQRCode" -> if (fields.onlyBusinessFields(1)) {
                fields.string(1)?.takeIf(String::isNotBlank)?.let {
                    feature(any, importerId, "android.qr.decode", mapOf(
                        text("image", it), text("resultVariable", "qrCodeText"), bool("textOnly", true)))
                }
            } else null
            "ShowDanmu" -> if (fields.onlyBusinessFields(1, 2) && !fields.has(2)) {
                fields.string(1)?.takeIf(String::isNotBlank)?.let {
                    feature(any, importerId, "surface.danmu.show", mapOf(
                        text("surfaceId", "shortx.danmu"), text("text", it), text("gravity", "top")))
                }
            } else null
            "ToggleData" -> if (fields.onlyBusinessFields(1, 2)) {
                val specific = fields.varint(1) ?: if (!fields.has(1)) 0L else return null
                val slot = fields.varint(2) ?: if (!fields.has(2)) 0L else return null
                if (specific == 0L && slot == 0L) toggle(any, importerId, "android.mobile_data.set")
                else null // A specific SIM must never be silently treated as the default.
            } else null
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
            "InputTap" -> if (fields.onlyBusinessFields(1, 2, 3, 4)) {
                val extended = fields.has(3) || fields.has(4)
                if (extended && (fields.has(1) || fields.has(2))) return null
                val (a, b) = if (extended) 3 to 4 else 1 to 2
                val x = coordinateField(fields, a) ?: return null
                val y = coordinateField(fields, b) ?: return null
                inputTap(any, importerId, x, y)
            } else null
            "InputSwipe" -> if (fields.onlyBusinessFields(1, 2, 3, 4, 5, 11, 12, 13, 14, 15)) {
                val extended = (11..15).any(fields::has)
                if (extended && (1..5).any(fields::has)) return null
                val indices = if (extended) 11..15 else 1..5
                val coords = indices.map { coordinateField(fields, it) ?: return null }
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

    internal fun enumNumber(value: JsonPrimitive?): Int? = value?.intOrNull
        ?: value?.contentOrNull?.substringAfterLast('_')?.let { name ->
            when (name.lowercase()) {
                "top" -> 0; "bottom" -> 1; "topforce" -> 2
                "bottomforce" -> 3; "forward" -> 4; "backward" -> 5
                else -> null
            }
        }

    internal fun toggleState(value: JsonPrimitive?): Int? =
        if (value == null) 0 else value.intOrNull ?: when (value.contentOrNull?.substringAfterLast('_')?.lowercase()) {
            "on" -> 0; "off" -> 1; "toggle" -> 2
            else -> null
        }

    fun json(any: AnyStub, importerId: String, obj: JsonObject): FeatureRef? = jsonImpl(any, importerId, obj)

}
