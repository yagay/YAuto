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
 * Each native mapping below also decodes the verified protobuf field layout required by the
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
            "InputText" -> inputText(any, importerId, fields)
            "InputTap" -> inputTap(any, importerId, fields)
            "InputSwipe" -> inputSwipe(any, importerId, fields)
            "FindAndClickViewById" -> clickViewId(any, importerId, fields)
            "SetWifiEnabled" -> booleanToggle(any, importerId, fields, "android.wifi.set")
            "SetBTEnabled" -> booleanToggle(any, importerId, fields, "android.bluetooth.set")
            "SetNFCEnabled" -> booleanToggle(any, importerId, fields, "android.nfc.set")
            "SetLocationEnabled" -> booleanToggle(any, importerId, fields, "android.location.enabled.set")
            "SetAPMModeEnabled" -> booleanToggle(any, importerId, fields, "android.airplane_mode.set")
            "SetFlashLightEnabled" -> booleanToggle(any, importerId, fields, "android.torch.set")
            "SetDataEnabled" -> setDataEnabled(any, importerId, fields)
            "SetDarkModeEnabled" -> setDarkMode(any, importerId, fields)
            "SetMasterSync" -> setMasterSync(any, importerId, fields)
            "SetRingerMode" -> setRingerMode(any, importerId, fields)
            "SetScreenRotate" -> setScreenRotate(any, importerId, fields)
            "SetScreenTimeout" -> setScreenTimeout(any, importerId, fields)
            "WakeupScreen" -> noFieldAction(any, importerId, fields, "android.screen.wake")
            "SleepScreen" -> noFieldAction(any, importerId, fields, "system.screen.sleep")
            "TTS" -> tts(any, importerId, fields)
            "OpenUrl" -> openUrl(any, importerId, fields)
            "ShellCommand" -> shellCommand(any, importerId, fields)
            "InjectKeyCode" -> injectKeyCode(any, importerId, fields)
            "SetAutoBrightness" -> setAutoBrightness(any, importerId, fields)
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
        val raw = any.value.toString(Charsets.UTF_8)
        return when (shortName(any.typeUrl)) {
            "ShowToast" -> {
                val message = (obj["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                sourceFeature("android.toast.show", importerId, any.typeUrl, raw,
                    extra = mapOf("text" to ConfigValue.StringValue(message)))
            }
            "Delay" -> {
                val value = (obj["timeString"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                    ?: (obj["time"] as? JsonPrimitive)?.doubleOrNull
                    ?: return null
                val unit = jsonTimeUnit(obj["timeUnit"] as? JsonPrimitive) ?: 0L
                val millis = durationMs(value, unit) ?: return null
                sourceFeature("core.delay", importerId, any.typeUrl, raw,
                    extra = mapOf("durationMs" to ConfigValue.NumberValue(millis)))
            }
            "LaunchApp" -> {
                val appPkg = obj["appPkg"] as? JsonObject ?: return null
                val pkg = (appPkg["pkgName"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
                val user = (appPkg["userId"] as? JsonPrimitive)?.intOrNull ?: 0
                if (user != 0) return null
                sourceFeature("android.app.launch", importerId, any.typeUrl, raw,
                    extra = mapOf("package" to ConfigValue.StringValue(pkg)))
            }
            "WriteClipboard" -> {
                val filePath = (obj["filePath"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (filePath.isNotBlank()) return null
                val text = (obj["text"] as? JsonPrimitive)?.contentOrNull ?: return null
                sourceFeature("android.clipboard.set", importerId, any.typeUrl, raw,
                    extra = mapOf("text" to ConfigValue.StringValue(text)))
            }
            "InputText" -> {
                val text = (obj["text"] as? JsonPrimitive)?.contentOrNull ?: return null
                sourceFeature("accessibility.input_text", importerId, any.typeUrl, raw,
                    extra = mapOf("text" to ConfigValue.StringValue(text)))
            }
            "InputTap" -> {
                val x = jsonNumeric(obj, "xs", "x") ?: return null
                val y = jsonNumeric(obj, "ys", "y") ?: return null
                if (x < 0 || y < 0) return null
                sourceFeature("accessibility.gesture.tap", importerId, any.typeUrl, raw,
                    extra = mapOf(
                        "x" to ConfigValue.NumberValue(x),
                        "y" to ConfigValue.NumberValue(y),
                        "durationMs" to ConfigValue.NumberValue(40.0),
                    ))
            }
            "InputSwipe" -> {
                val x1 = jsonNumeric(obj, "startXS", "startX") ?: return null
                val y1 = jsonNumeric(obj, "startYS", "startY") ?: return null
                val x2 = jsonNumeric(obj, "endXS", "endX") ?: return null
                val y2 = jsonNumeric(obj, "endYS", "endY") ?: return null
                val duration = jsonNumeric(obj, "swipeTimeS", "swipeTime") ?: return null
                if (listOf(x1, y1, x2, y2).any { it < 0 } || duration <= 0) return null
                sourceFeature("accessibility.gesture.swipe", importerId, any.typeUrl, raw,
                    extra = mapOf(
                        "x1" to ConfigValue.NumberValue(x1), "y1" to ConfigValue.NumberValue(y1),
                        "x2" to ConfigValue.NumberValue(x2), "y2" to ConfigValue.NumberValue(y2),
                        "durationMs" to ConfigValue.NumberValue(duration),
                    ))
            }
            "FindAndClickViewById" -> {
                val viewId = (obj["viewId"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
                if ((obj["isRegex"] as? JsonPrimitive)?.booleanOrNull == true) return null
                val timeout = (obj["timeout"] as? JsonPrimitive)?.longOrNull ?: 0L
                if (timeout != 0L) return null
                sourceFeature("accessibility.click_view_id", importerId, any.typeUrl, raw,
                    extra = mapOf("viewId" to ConfigValue.StringValue(viewId)))
            }
            "SetWifiEnabled" -> jsonToggle(obj, any, importerId, "android.wifi.set", "enable")
            "SetBTEnabled" -> jsonToggle(obj, any, importerId, "android.bluetooth.set", "enable")
            "SetNFCEnabled" -> jsonToggle(obj, any, importerId, "android.nfc.set", "enable")
            "SetLocationEnabled" -> jsonToggle(obj, any, importerId, "android.location.enabled.set", "enable")
            "SetAPMModeEnabled" -> jsonToggle(obj, any, importerId, "android.airplane_mode.set", "isEnable")
            "SetFlashLightEnabled" -> jsonToggle(obj, any, importerId, "android.torch.set", "enable")
            "SetDataEnabled" -> {
                if ((obj["hasSpecificSlotId"] as? JsonPrimitive)?.booleanOrNull == true) return null
                jsonToggle(obj, any, importerId, "android.mobile_data.set", "enable")
            }
            "SetDarkModeEnabled" -> {
                val enabled = (obj["enable"] as? JsonPrimitive)?.booleanOrNull ?: return null
                sourceFeature("android.display.dark_mode.set", importerId, any.typeUrl, raw,
                    extra = mapOf("mode" to ConfigValue.StringValue(if (enabled) "dark" else "light")))
            }
            "SetMasterSync" -> {
                val value = jsonOnOffToggle(obj["sync"] as? JsonPrimitive) ?: return null
                if (value == 2) return null
                sourceFeature("android.sync.master.set", importerId, any.typeUrl, raw,
                    extra = mapOf("enabled" to ConfigValue.BooleanValue(value == 0)))
            }
            "SetRingerMode" -> {
                val mode = jsonRingerMode(obj["mode"] as? JsonPrimitive) ?: return null
                if (mode !in 0..2) return null
                sourceFeature("android.audio.ringer_mode.set", importerId, any.typeUrl, raw,
                    extra = mapOf("mode" to ConfigValue.StringValue(listOf("silent", "vibrate", "normal")[mode])))
            }
            "SetScreenRotate" -> {
                val degree = jsonRotation(obj["degree"] as? JsonPrimitive) ?: return null
                if (degree !in 0..3) return null
                val rotation = listOf("0", "90", "180", "270")[degree]
                sourceFeature("android.display.rotation.set", importerId, any.typeUrl, raw,
                    extra = mapOf("rotation" to ConfigValue.StringValue(rotation)))
            }
            "SetScreenTimeout" -> {
                val timeout = (obj["timeoutMillis"] as? JsonPrimitive)?.longOrNull ?: return null
                if (timeout <= 0) return null
                sourceFeature("android.display.screen_timeout.set", importerId, any.typeUrl, raw,
                    extra = mapOf("timeoutMs" to ConfigValue.NumberValue(timeout.toDouble())))
            }
            "WakeupScreen" -> sourceFeature("android.screen.wake", importerId, any.typeUrl, raw)
            "SleepScreen" -> sourceFeature("system.screen.sleep", importerId, any.typeUrl, raw)
            "TTS" -> {
                val text = (obj["text"] as? JsonPrimitive)?.contentOrNull ?: return null
                sourceFeature("android.tts.speak", importerId, any.typeUrl, raw,
                    extra = mapOf("text" to ConfigValue.StringValue(text)))
            }
            "OpenUrl" -> {
                if (!(obj["browserPkg"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()) return null
                val url = (obj["url"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
                sourceFeature("android.uri.open", importerId, any.typeUrl, raw,
                    extra = mapOf("uri" to ConfigValue.StringValue(url)))
            }
            "ShellCommand" -> {
                val command = (obj["command"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
                sourceFeature("system.shell.execute", importerId, any.typeUrl, raw,
                    extra = mapOf("command" to ConfigValue.StringValue(command)))
            }
            "InjectKeyCode" -> {
                if ((obj["doublePress"] as? JsonPrimitive)?.booleanOrNull == true) return null
                val keyCode = (obj["keyCode"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..1000 } ?: return null
                val longPress = (obj["longPress"] as? JsonPrimitive)?.booleanOrNull == true
                sourceFeature("android.input.keyevent", importerId, any.typeUrl, raw,
                    extra = mapOf(
                        "key" to ConfigValue.StringValue("custom"),
                        "customKeyCode" to ConfigValue.NumberValue(keyCode.toDouble()),
                        "longPress" to ConfigValue.BooleanValue(longPress),
                    ))
            }
            "SetAutoBrightness" -> {
                val enable = (obj["enable"] as? JsonPrimitive)?.booleanOrNull ?: return null
                if (!enable) return null
                sourceFeature("android.display.brightness.set", importerId, any.typeUrl, raw,
                    extra = mapOf("mode" to ConfigValue.StringValue("auto")))
            }
            else -> null
        }
    }

    fun suggestedActionFeature(typeUrl: String): String? = when (shortName(typeUrl)) {
        "ShowToast" -> "android.toast.show"
        "Delay" -> "core.delay"
        "LaunchApp", "LaunchAppByPkg" -> "android.app.launch"
        "WriteClipboard" -> "android.clipboard.set"
        "ReadClipboard" -> "android.clipboard.get"
        "ShellCommand" -> "system.shell.execute"
        "StopApp", "StopAppByPkg", "StopCurrentApp" -> "android.app.force_stop"
        "InputText" -> "accessibility.input_text"
        "InputTap" -> "accessibility.gesture.tap"
        "InputSwipe" -> "accessibility.gesture.swipe"
        "FindAndClickViewById" -> "accessibility.click_view_id"
        "FindAndClickViewByText", "FindAndClickMatchedView" -> "accessibility.click_text"
        "SetWifiEnabled" -> "android.wifi.set"
        "SetBTEnabled" -> "android.bluetooth.set"
        "SetNFCEnabled" -> "android.nfc.set"
        "SetLocationEnabled" -> "android.location.enabled.set"
        "SetAPMModeEnabled" -> "android.airplane_mode.set"
        "SetDataEnabled" -> "android.mobile_data.set"
        "SetDarkModeEnabled" -> "android.display.dark_mode.set"
        "SetMasterSync" -> "android.sync.master.set"
        "SetRingerMode" -> "android.audio.ringer_mode.set"
        "SetScreenRotate" -> "android.display.rotation.set"
        "SetScreenTimeout" -> "android.display.screen_timeout.set"
        "SetAutoBrightness", "SetBrightness", "ScreenBrightness" -> "android.display.brightness.set"
        "SetFlashLightEnabled" -> "android.torch.set"
        "WakeupScreen" -> "android.screen.wake"
        "SleepScreen" -> "system.screen.sleep"
        "TTS" -> "android.tts.speak"
        "OpenUrl" -> "android.uri.open"
        "InjectKeyCode", "InjectCombineKeyCode" -> "android.input.keyevent"
        "MediaPlayback", "MediaPlaybackAction" -> "android.media.transport"
        "SetDNDEnabled", "ToggleDND" -> "android.dnd.set"
        "Vibrate" -> "android.vibrate"
        "HttpRequest" -> "android.http.request"
        "SetWallpaper" -> "android.wallpaper.set"
        "PostNotification" -> "android.notification.show"
        "RemoveNotification", "RemoveNotificationForPackage", "RemoveNotificationForPackageByPkg" -> "android.notification.dismiss"
        "ClickNotification" -> "android.notification.open"
        "ClickNotificationActionButton" -> "android.notification.action"
        "SetAppEnabled", "SetAppEnabledByPkg", "DisableApp", "DisableAppByPkg" -> "android.app.enabled.set"
        "SetAppInactive", "SetAppInactiveByPkg" -> "android.app.inactive.set"
        "SetAppSuspend", "SetAppSuspendByPkg" -> "android.app.suspended.set"
        "GetAppInfo" -> "android.app.package_info"
        "SetVolume" -> "android.audio.volume.set"
        "AdjustVolume" -> "android.audio.volume.adjust"
        "ShareContent" -> "android.file.share"
        "SendSMS" -> "android.sms.compose"
        "LockDeviceNow" -> "system.screen.sleep"
        "ExpandNotification" -> "system.notifications.expand"
        "PlayRingtone" -> "android.audio.play"
        else -> null
    }

    private fun showToast(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        val message = fields.string(1) ?: return null
        return binaryFeature(any, importerId, "android.toast.show",
            mapOf("text" to ConfigValue.StringValue(message)))
    }

    private fun delay(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        val value = fields.string(2)?.toDoubleOrNull() ?: fields.varint(1)?.toDouble() ?: return null
        val unit = fields.varint(5) ?: 0L
        val millis = durationMs(value, unit) ?: return null
        return binaryFeature(any, importerId, "core.delay",
            mapOf("durationMs" to ConfigValue.NumberValue(millis)))
    }

    private fun launchApp(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        val appPkg = fields.bytes(1)?.let(::ProtoFields) ?: return null
        val packageName = appPkg.string(1)?.takeIf { it.isNotBlank() } ?: return null
        val userId = appPkg.varint(2) ?: 0L
        if (userId != 0L) return null
        return binaryFeature(any, importerId, "android.app.launch",
            mapOf("package" to ConfigValue.StringValue(packageName)))
    }

    private fun writeClipboard(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.string(2).isNullOrBlank()) return null
        val text = fields.string(1) ?: return null
        return binaryFeature(any, importerId, "android.clipboard.set",
            mapOf("text" to ConfigValue.StringValue(text)))
    }

    private fun inputText(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        val text = fields.string(1) ?: return null
        return binaryFeature(any, importerId, "accessibility.input_text",
            mapOf("text" to ConfigValue.StringValue(text)))
    }

    private fun inputTap(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
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

    private fun inputSwipe(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
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

    private fun clickViewId(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        val viewId = fields.string(1)?.takeIf { it.isNotBlank() } ?: return null
        if ((fields.varint(2) ?: 0L) != 0L) return null
        if ((fields.varint(3) ?: 0L) != 0L) return null
        return binaryFeature(any, importerId, "accessibility.click_view_id",
            mapOf("viewId" to ConfigValue.StringValue(viewId)))
    }

    private fun booleanToggle(any: AnyStub, importerId: String, fields: ProtoFields, target: String): FeatureRef? {
        if (!fields.onlyBusinessFields(1)) return null
        val enabled = fields.varint(1)?.let { it != 0L } ?: return null
        return binaryFeature(any, importerId, target, mapOf("enabled" to ConfigValue.BooleanValue(enabled)))
    }

    private fun setDataEnabled(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1, 2, 3)) return null
        if ((fields.varint(2) ?: 0L) != 0L) return null
        val enabled = fields.varint(1)?.let { it != 0L } ?: return null
        return binaryFeature(any, importerId, "android.mobile_data.set", mapOf("enabled" to ConfigValue.BooleanValue(enabled)))
    }

    private fun setDarkMode(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1)) return null
        val enabled = fields.varint(1)?.let { it != 0L } ?: return null
        return binaryFeature(any, importerId, "android.display.dark_mode.set",
            mapOf("mode" to ConfigValue.StringValue(if (enabled) "dark" else "light")))
    }

    private fun setMasterSync(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1)) return null
        val mode = fields.varint(1)?.toInt() ?: return null
        if (mode !in 0..1) return null
        return binaryFeature(any, importerId, "android.sync.master.set",
            mapOf("enabled" to ConfigValue.BooleanValue(mode == 0)))
    }

    private fun setRingerMode(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1)) return null
        val mode = fields.varint(1)?.toInt() ?: return null
        if (mode !in 0..2) return null
        return binaryFeature(any, importerId, "android.audio.ringer_mode.set",
            mapOf("mode" to ConfigValue.StringValue(listOf("silent", "vibrate", "normal")[mode])))
    }

    private fun setScreenRotate(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1)) return null
        val degree = fields.varint(1)?.toInt() ?: return null
        if (degree !in 0..3) return null
        return binaryFeature(any, importerId, "android.display.rotation.set",
            mapOf("rotation" to ConfigValue.StringValue(listOf("0", "90", "180", "270")[degree])))
    }

    private fun setScreenTimeout(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1)) return null
        val timeout = fields.varint(1)?.takeIf { it > 0L } ?: return null
        return binaryFeature(any, importerId, "android.display.screen_timeout.set",
            mapOf("timeoutMs" to ConfigValue.NumberValue(timeout.toDouble())))
    }

    private fun noFieldAction(any: AnyStub, importerId: String, fields: ProtoFields, target: String): FeatureRef? {
        if (!fields.onlyBusinessFields()) return null
        return binaryFeature(any, importerId, target, emptyMap())
    }

    private fun tts(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1)) return null
        val text = fields.string(1) ?: return null
        return binaryFeature(any, importerId, "android.tts.speak", mapOf("text" to ConfigValue.StringValue(text)))
    }

    private fun openUrl(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1, 2)) return null
        if (!fields.string(2).isNullOrBlank()) return null
        val url = fields.string(1)?.takeIf { it.isNotBlank() } ?: return null
        return binaryFeature(any, importerId, "android.uri.open", mapOf("uri" to ConfigValue.StringValue(url)))
    }

    private fun shellCommand(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1, 2)) return null
        val command = fields.string(1)?.takeIf { it.isNotBlank() } ?: return null
        return binaryFeature(any, importerId, "system.shell.execute", mapOf("command" to ConfigValue.StringValue(command)))
    }

    private fun injectKeyCode(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1, 2, 3)) return null
        if ((fields.varint(3) ?: 0L) != 0L) return null
        val keyCode = fields.varint(1)?.toInt()?.takeIf { it in 0..1000 } ?: return null
        val longPress = (fields.varint(2) ?: 0L) != 0L
        return binaryFeature(any, importerId, "android.input.keyevent",
            mapOf(
                "key" to ConfigValue.StringValue("custom"),
                "customKeyCode" to ConfigValue.NumberValue(keyCode.toDouble()),
                "longPress" to ConfigValue.BooleanValue(longPress),
            ))
    }

    private fun setAutoBrightness(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1)) return null
        val enabled = fields.varint(1)?.let { it != 0L } ?: return null
        if (!enabled) return null // Disabling auto without a source brightness value is not lossless.
        return binaryFeature(any, importerId, "android.display.brightness.set",
            mapOf("mode" to ConfigValue.StringValue("auto")))
    }

    private fun jsonToggle(obj: JsonObject, any: AnyStub, importerId: String, target: String, key: String): FeatureRef? {
        val enabled = (obj[key] as? JsonPrimitive)?.booleanOrNull ?: return null
        return sourceFeature(target, importerId, any.typeUrl, any.value.toString(Charsets.UTF_8),
            extra = mapOf("enabled" to ConfigValue.BooleanValue(enabled)))
    }

    private fun jsonOnOffToggle(value: JsonPrimitive?): Int? {
        value ?: return null
        value.intOrNull?.let { return it }
        return when (value.contentOrNull?.substringAfterLast('_')?.lowercase()) {
            "on" -> 0
            "off" -> 1
            "toggle" -> 2
            else -> null
        }
    }

    private fun jsonRingerMode(value: JsonPrimitive?): Int? {
        value ?: return null
        value.intOrNull?.let { return it }
        val text = value.contentOrNull?.lowercase().orEmpty()
        return when {
            "silent" in text -> 0
            "vibrate" in text -> 1
            "normal" in text -> 2
            "switch" in text -> 3
            else -> null
        }
    }

    private fun jsonRotation(value: JsonPrimitive?): Int? {
        value ?: return null
        value.intOrNull?.let { return it }
        val text = value.contentOrNull?.uppercase().orEmpty()
        return when {
            "ANY" in text || "AUTO" in text -> 4
            "270" in text -> 3
            "180" in text -> 2
            "90" in text -> 1
            "0" in text -> 0
            else -> null
        }
    }

    private fun protoNumber(fields: ProtoFields, stringField: Int, deprecatedField: Int): Double? {
        fields.string(stringField)?.let { return it.toDoubleOrNull()?.takeIf(Double::isFinite) }
        return (fields.varint(deprecatedField) ?: 0L).toDouble()
    }

    private fun jsonNumeric(obj: JsonObject, preferred: String, deprecated: String): Double? {
        val preferredValue = (obj[preferred] as? JsonPrimitive)?.contentOrNull
        if (!preferredValue.isNullOrBlank()) return preferredValue.toDoubleOrNull()?.takeIf(Double::isFinite)
        return (obj[deprecated] as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite) ?: 0.0
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

internal class ProtoFields(bytes: ByteArray) {
    private val fields = Wire(bytes).fields()
    fun has(number: Int): Boolean = fields.any { it.number == number }

    fun onlyBusinessFields(vararg allowed: Int): Boolean {
        val allowedSet = allowed.toSet()
        return fields.asSequence().filter { it.number < 96 }.all { it.number in allowedSet }
    }

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
