package com.yagay.yauto.importer.shortx

import com.yagay.yauto.core.importer.sourceFeature
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import java.util.Base64
import kotlinx.serialization.json.*

/** Verified batch JSON translation shares exact decoders with protobuf action mappings. */
    /** Ignore neither unknown business properties nor malformed enum values. */
internal fun ShortXVerifiedBatchMappings.jsonImpl(any: AnyStub, importerId: String, obj: JsonObject): FeatureRef? {
        fun allowed(vararg keys: String): Boolean =
            obj.keys.all { it in sourceMetadata || it in keys }
        fun field(name: String): Int? = (obj[name] as? JsonPrimitive)?.intOrNull
        return when (sourceName(any)) {
            "LaunchAppByPkg" -> launchSingle(any, importerId, targetsJson(obj, byPair = true))
            "RemoveTasks" -> taskRemove(any, importerId, targetsJson(obj, byPair = false, allowSets = true))
            "RemoveTasksByPkg" -> taskRemove(any, importerId, targetsJson(obj, byPair = true))
            "StartActivityUrlSchema" -> if (allowed("urlSchema", "userId")) {
                val uid = (obj["userId"] as? JsonPrimitive)?.intOrNull ?: if ("userId" in obj) return null else 0
                val raw = (obj["urlSchema"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                if (uid != 0 || ':' !in raw) null else feature(any, importerId,
                    "android.intent_uri.launch", mapOf(text("intentUri", raw), bool("urlSchemeMode", true)))
            } else null
            "GetCurrentLocationInfo" -> if (allowed("providerPreference", "timeoutMillis")) {
                val prefRaw = (obj["providerPreference"] as? JsonPrimitive)?.content
                val preference = when (prefRaw) {
                    null, "0", "Auto", "CurrentLocationProviderPreference_Auto" -> 0
                    "1", "NetworkFirst", "CurrentLocationProviderPreference_NetworkFirst" -> 1
                    "2", "GpsFirst", "CurrentLocationProviderPreference_GpsFirst" -> 2
                    else -> return null
                }
                val timeout = (obj["timeoutMillis"] as? JsonPrimitive)?.longOrNull ?: return null
                locationInfo(any, importerId, preference, timeout)
            } else null
            "ShowDrawBoard" -> noThemeJson(any, importerId, obj, "surface.draw_board.show")?.let {
                it.copy(config = it.config + mapOf(
                    text("surfaceId", "shortx.draw_board"), text("gravity", "center")))
            }
            "MatchRegex" -> if (allowed("string", "regex", "matchOptions")) {
                val value = (obj["string"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                val pattern = (obj["regex"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                val raw = (obj["matchOptions"] as? JsonPrimitive)
                val mode = when (raw?.content) {
                    null, "0", "Match", "RegexMatchOptions_Match" -> 0
                    "1", "ContainsMatchIn", "RegexMatchOptions_ContainsMatchIn" -> 1
                    else -> return null
                }
                regexMatch(any, importerId, value, pattern, mode)
            } else null
            "StartActivityIntentUri" -> if (allowed("intentUri")) {
                (obj["intentUri"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)?.let {
                    feature(any, importerId, "android.intent_uri.launch", mapOf(text("intentUri", it)))
                }
            } else null
            "EnableUniversalCopy" -> noThemeJson(any, importerId, obj, "accessibility.universal_copy.show")
            "EnableViewIdViewer" -> noThemeJson(any, importerId, obj, "accessibility.view_id_viewer.show")
            "ParseQRCode" -> if (allowed("imagePath")) {
                (obj["imagePath"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)?.let {
                    feature(any, importerId, "android.qr.decode", mapOf(
                        text("image", it), text("resultVariable", "qrCodeText"), bool("textOnly", true)))
                }
            } else null
            "ShowDanmu" -> if (allowed("text", "icon") &&
                (obj["icon"] == null || (obj["icon"] as? JsonPrimitive)?.contentOrNull == "")) {
                (obj["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)?.let {
                    feature(any, importerId, "surface.danmu.show", mapOf(
                        text("surfaceId", "shortx.danmu"), text("text", it), text("gravity", "top")))
                }
            } else null
            "ToggleData" -> if (allowed("hasSpecificSlotId", "slotId")) {
                val specific = (obj["hasSpecificSlotId"] as? JsonPrimitive)?.booleanOrNull
                    ?: if ("hasSpecificSlotId" in obj) return null else false
                val slot = (obj["slotId"] as? JsonPrimitive)?.intOrNull
                    ?: if ("slotId" in obj) return null else 0
                if (!specific && slot == 0) toggle(any, importerId, "android.mobile_data.set") else null
            } else null
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
