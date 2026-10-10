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
        if (fields.has(96)) return null
        return when (type) {
            "NoAction" -> noAction(any, importerId, fields)
            "MediaPlayback" -> mediaPlayback(any, importerId, fields)
            "SetVolume" -> setVolume(any, importerId, fields)
            "ShowToast" -> showToast(any, importerId, fields)
            "Delay" -> delay(any, importerId, fields)
            "LaunchApp" -> launchApp(any, importerId, fields)
            "WriteClipboard" -> writeClipboard(any, importerId, fields)
            "InputText" -> inputText(any, importerId, fields)
            "InputTap" -> inputTap(any, importerId, fields)
            "InputSwipe" -> inputSwipe(any, importerId, fields)
            "FindAndClickViewById" -> clickViewId(any, importerId, fields)
            "FindAndClickViewByText" -> clickText(any, importerId, fields)
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
            "ExpandNotification" -> expandNotification(any, importerId, fields)
            "AreaScreenshot" -> noFieldAction(any, importerId, fields, "android.screenshot.area_select")
            "ShowGlobalActionsMenu" -> noFieldAction(any, importerId, fields, "android.global_actions.show")
            "StopAudioRecording" -> noFieldAction(any, importerId, fields, "android.audio.record.stop")
            "GetScreenOnTime" -> nativeGetScreenOnTime(any, importerId, fields)
            "SetStatusBarIcon" -> nativeStatusBarIcon(any, importerId, fields, "show")
            "RemoveStatusBarIcon" -> nativeStatusBarIcon(any, importerId, fields, "remove")
            "StopService" -> nativeStopServices(any, importerId, fields)
            "StartAppProcess" -> nativeStartAppProcess(any, importerId, fields)
            "StartAppProcessByPkg" -> nativeStartAppProcessByPkg(any, importerId, fields)
            "ShowStatusBarChip" -> nativeShowStatusChip(any, importerId, fields)
            "HideStatusBarClip" -> noFieldAction(any, importerId, fields, "android.status_chip.control")
                ?.let { it.copy(config = it.config + mapOf(
                    "mode" to ConfigValue.StringValue("hide"),
                    "chipId" to ConfigValue.StringValue("shortx"),
                )) }
            "RequestAudioFocus" -> requestAudioFocus(any, importerId, fields)
            "PlayRingtone" -> playRingtone(any, importerId, fields)
            else -> null
        }
    }

    fun nativeFact(any: AnyStub, importerId: String): FeatureRef? {
        if (!factEnabled(any)) return null
        if (any.isJson) {
            val obj = Json.parseToJsonElement(any.value.toString(Charsets.UTF_8)) as? JsonObject ?: return null
            if (obj["customContextDataKey"] != null && obj["customContextDataKey"] !is JsonNull) return null
            val raw = any.value.toString(Charsets.UTF_8)
            val mapped = when (shortName(any.typeUrl)) {
                "AnyFact" -> sourceFeature("core.event.any", importerId, any.typeUrl, raw)
                "ScreenOn" -> sourceFeature("android.event.screen_on", importerId, any.typeUrl, raw)
                "ScreenOff" -> sourceFeature("android.event.screen_off", importerId, any.typeUrl, raw)
                "UserPresent" -> sourceFeature("android.event.user_present", importerId, any.typeUrl, raw)
                "ChargerPlug" -> sourceFeature("android.event.power_connected", importerId, any.typeUrl, raw)
                "ChargerUnplug" -> sourceFeature("android.event.power_disconnected", importerId, any.typeUrl, raw)
                "AppAdded" -> sourceFeature("android.event.package_added", importerId, any.typeUrl, raw)
                "BTStatusChanged" -> jsonOnOffAny(obj["ooa"] as? JsonPrimitive)?.let {
                    sourceFeature("android.event.bluetooth_state", importerId, any.typeUrl, raw, extra = mapOf("state" to ConfigValue.StringValue(it)))
                }
                "WifiStatusChanged" -> jsonOnOffAny(obj["ooa"] as? JsonPrimitive)?.let {
                    val state = when (it) { "on" -> "enabled"; "off" -> "disabled"; else -> "any" }
                    sourceFeature("android.event.wifi_adapter_state_filtered", importerId, any.typeUrl, raw, extra = mapOf("state" to ConfigValue.StringValue(state)))
                }
                "NFCStatusChanged" -> jsonOnOffAny(obj["ooa"] as? JsonPrimitive)?.let {
                    sourceFeature("android.event.nfc_state_changed", importerId, any.typeUrl, raw, extra = mapOf("state" to ConfigValue.StringValue(it)))
                }
                "LocationStatusChanged" -> jsonOnOffAny(obj["ooa"] as? JsonPrimitive)?.let {
                    sourceFeature("android.event.location_mode_changed", importerId, any.typeUrl, raw, extra = mapOf("state" to ConfigValue.StringValue(it)))
                }
                "DarkModeStatusChanged" -> jsonOnOffAny(obj["ooa"] as? JsonPrimitive)?.let {
                    sourceFeature("android.event.dark_mode_changed", importerId, any.typeUrl, raw, extra = mapOf("state" to ConfigValue.StringValue(it)))
                }
                "APMStatusChanged" -> jsonOnOffAny(obj["ooa"] as? JsonPrimitive)?.let {
                    sourceFeature("android.event.airplane_mode_changed", importerId, any.typeUrl, raw, extra = mapOf("state" to ConfigValue.StringValue(it)))
                }
                "KeyEvent" -> (obj["keyCode"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..1000 }?.let { keyCode ->
                    sourceFeature(
                        "android.event.hardware_key",
                        importerId,
                        any.typeUrl,
                        raw,
                        extra = mapOf(
                            "keyCode" to ConfigValue.NumberValue(keyCode.toDouble()),
                            "action" to ConfigValue.StringValue("up"),
                            "initialOnly" to ConfigValue.BooleanValue(true),
                        ),
                    )
                }
                "AdvancedKeyEvent" -> {
                    val intercept = (obj["isInterceptMode"] as? JsonPrimitive)?.booleanOrNull ?: false
                    val keyCode = (obj["keyCode"] as? JsonPrimitive)?.intOrNull
                    val gesture = (obj["gesture"] as? JsonPrimitive)?.intOrNull?.let(::shortXKeyGesture)
                    if (intercept || keyCode == null || keyCode !in 0..1000 || gesture == null) null
                    else sourceFeature(
                        "android.event.hardware_key_gesture",
                        importerId,
                        any.typeUrl,
                        raw,
                        extra = mapOf(
                            "keyCode" to ConfigValue.NumberValue(keyCode.toDouble()),
                            "gesture" to ConfigValue.StringValue(gesture),
                        ),
                    )
                }
                "CombineKeyEvent" -> {
                    val keyCode1 = (obj["keyCode1"] as? JsonPrimitive)?.intOrNull
                    val keyCode2 = (obj["keyCode2"] as? JsonPrimitive)?.intOrNull
                    if (keyCode1 == null || keyCode2 == null || keyCode1 !in 0..1000 || keyCode2 !in 0..1000) null
                    else sourceFeature(
                        "android.event.hardware_key_combo",
                        importerId,
                        any.typeUrl,
                        raw,
                        extra = mapOf(
                            "keyCode1" to ConfigValue.NumberValue(keyCode1.toDouble()),
                            "keyCode2" to ConfigValue.NumberValue(keyCode2.toDouble()),
                        ),
                    )
                }
                "HeadsetPlug" -> (obj["isPlug"] as? JsonPrimitive)?.booleanOrNull?.let {
                    sourceFeature(
                        "android.event.headset_changed", importerId, any.typeUrl, raw,
                        extra = mapOf(
                            "state" to ConfigValue.StringValue(if (it) "connected" else "disconnected"),
                            "category" to ConfigValue.StringValue("any"),
                        ),
                    )
                }
                "VPNConnected" -> sourceFeature(
                    "android.event.network_profile_changed", importerId, any.typeUrl, raw,
                    extra = mapOf(
                        "connected" to ConfigValue.StringValue("connected"),
                        "transport" to ConfigValue.StringValue("vpn"),
                        "validated" to ConfigValue.StringValue("any"),
                        "metered" to ConfigValue.StringValue("any"),
                    ),
                )
                "AppRemoved" -> if (jsonArrayEmpty(obj, "apps") && jsonArrayEmpty(obj, "pkgSets")) {
                    sourceFeature("android.event.package_removed", importerId, any.typeUrl, raw)
                } else null
                "AppUpdated" -> if (jsonArrayEmpty(obj, "apps") && jsonArrayEmpty(obj, "pkgSets")) {
                    sourceFeature("android.event.package_replaced", importerId, any.typeUrl, raw)
                } else null
                else -> null
            }
            return mapped?.let { withFactTag(it, jsonFactTag(obj)) }
        }

        val fields = runCatching { ProtoFields(any.value) }.getOrNull() ?: return null
        if (fields.has(98)) return null
        val mapped = when (shortName(any.typeUrl)) {
            "AnyFact" -> noBusinessFact(any, importerId, fields, "core.event.any")
            "ScreenOn" -> noBusinessFact(any, importerId, fields, "android.event.screen_on")
            "ScreenOff" -> noBusinessFact(any, importerId, fields, "android.event.screen_off")
            "UserPresent" -> noBusinessFact(any, importerId, fields, "android.event.user_present")
            "ChargerPlug" -> noBusinessFact(any, importerId, fields, "android.event.power_connected")
            "ChargerUnplug" -> noBusinessFact(any, importerId, fields, "android.event.power_disconnected")
            "AppAdded" -> noBusinessFact(any, importerId, fields, "android.event.package_added")
            "BTStatusChanged" -> onOffAnyFact(any, importerId, fields, "android.event.bluetooth_state")
            "WifiStatusChanged" -> onOffAnyFact(any, importerId, fields, "android.event.wifi_adapter_state_filtered", wifiAdapter = true)
            "NFCStatusChanged" -> onOffAnyFact(any, importerId, fields, "android.event.nfc_state_changed")
            "LocationStatusChanged" -> onOffAnyFact(any, importerId, fields, "android.event.location_mode_changed")
            "DarkModeStatusChanged" -> onOffAnyFact(any, importerId, fields, "android.event.dark_mode_changed")
            "APMStatusChanged" -> onOffAnyFact(any, importerId, fields, "android.event.airplane_mode_changed")
            "KeyEvent" -> {
                if (!fields.onlyBusinessFields(1)) null
                else fields.varint(1)?.toInt()?.takeIf { it in 0..1000 }?.let { keyCode ->
                    binaryFeature(
                        any,
                        importerId,
                        "android.event.hardware_key",
                        mapOf(
                            "keyCode" to ConfigValue.NumberValue(keyCode.toDouble()),
                            "action" to ConfigValue.StringValue("up"),
                            "initialOnly" to ConfigValue.BooleanValue(true),
                        ),
                    )
                }
            }
            "AdvancedKeyEvent" -> {
                if (!fields.onlyBusinessFields(1, 2, 3) || (fields.varint(3) ?: 0L) != 0L) null
                else {
                    val keyCode = fields.varint(1)?.toInt()
                    val gesture = fields.varint(2)?.toInt()?.let(::shortXKeyGesture)
                    if (keyCode == null || keyCode !in 0..1000 || gesture == null) null
                    else binaryFeature(
                        any,
                        importerId,
                        "android.event.hardware_key_gesture",
                        mapOf(
                            "keyCode" to ConfigValue.NumberValue(keyCode.toDouble()),
                            "gesture" to ConfigValue.StringValue(gesture),
                        ),
                    )
                }
            }
            "CombineKeyEvent" -> {
                if (!fields.onlyBusinessFields(1, 2)) null
                else {
                    val keyCode1 = fields.varint(1)?.toInt()
                    val keyCode2 = fields.varint(2)?.toInt()
                    if (keyCode1 == null || keyCode2 == null || keyCode1 !in 0..1000 || keyCode2 !in 0..1000) null
                    else binaryFeature(
                        any,
                        importerId,
                        "android.event.hardware_key_combo",
                        mapOf(
                            "keyCode1" to ConfigValue.NumberValue(keyCode1.toDouble()),
                            "keyCode2" to ConfigValue.NumberValue(keyCode2.toDouble()),
                        ),
                    )
                }
            }
            "HeadsetPlug" -> {
                if (!fields.onlyBusinessFields(1)) null
                else fields.varint(1)?.let {
                    binaryFeature(
                        any, importerId, "android.event.headset_changed",
                        mapOf(
                            "state" to ConfigValue.StringValue(if (it != 0L) "connected" else "disconnected"),
                            "category" to ConfigValue.StringValue("any"),
                        ),
                    )
                }
            }
            "VPNConnected" -> noBusinessFact(
                any, importerId, fields, "android.event.network_profile_changed",
                mapOf(
                    "connected" to ConfigValue.StringValue("connected"),
                    "transport" to ConfigValue.StringValue("vpn"),
                    "validated" to ConfigValue.StringValue("any"),
                    "metered" to ConfigValue.StringValue("any"),
                ),
            )
            "AppRemoved" -> noBusinessFact(any, importerId, fields, "android.event.package_removed")
            "AppUpdated" -> noBusinessFact(any, importerId, fields, "android.event.package_replaced")
            else -> null
        }
        return mapped?.let { withFactTag(it, fields.string(97)) }
    }

    fun nativeCondition(any: AnyStub, importerId: String): FeatureRef? {
        if (!conditionEnabled(any)) return null
        if (any.isJson) return nativeJsonCondition(any, importerId)

        val fields = runCatching { ProtoFields(any.value) }.getOrNull() ?: return null
        if (fields.has(97)) return null
        return when (shortName(any.typeUrl)) {
            "RequireFactTag" -> {
                if (!fields.onlyBusinessFields(1)) null
                else fields.string(1)?.takeIf { it.isNotBlank() }?.let { tag ->
                    binaryFeature(any, importerId, "core.condition.event_tag", mapOf("tag" to ConfigValue.StringValue(tag)))
                }
            }
            "TRUE", "True" -> noBusinessCondition(any, importerId, fields, "core.boolean", mapOf("value" to ConfigValue.BooleanValue(true)))
            "FALSE", "False" -> noBusinessCondition(any, importerId, fields, "core.boolean", mapOf("value" to ConfigValue.BooleanValue(false)))
            "ScreenIsOn" -> noBusinessCondition(any, importerId, fields, "android.condition.screen", mapOf("value" to ConfigValue.BooleanValue(true)))
            "VPNIsConnected" -> noBusinessCondition(
                any, importerId, fields, "android.condition.network_profile",
                mapOf(
                    "connected" to ConfigValue.BooleanValue(true),
                    "transport" to ConfigValue.StringValue("vpn"),
                    "validated" to ConfigValue.StringValue("any"),
                    "metered" to ConfigValue.StringValue("any"),
                ),
            )
            "ChargeState" -> {
                if (!fields.onlyBusinessFields(1)) null
                else fields.varint(1)?.let { value ->
                    binaryFeature(any, importerId, "android.condition.charging", mapOf("value" to ConfigValue.BooleanValue(value != 0L)))
                }
            }
            "RequireWifiConnected" -> {
                if (!fields.onlyBusinessFields(1)) null
                else binaryFeature(
                    any, importerId, "android.condition.wifi_network",
                    mapOf(
                        "connected" to ConfigValue.StringValue("connected"),
                        "ssid" to ConfigValue.StringValue(fields.string(1).orEmpty()),
                    ),
                )
            }
            "RequireWifiDisconnected" -> noBusinessCondition(
                any, importerId, fields, "android.condition.wifi_network",
                mapOf("connected" to ConfigValue.StringValue("disconnected")),
            )
            "KeyguardIsLocked" -> noBusinessCondition(
                any, importerId, fields, "android.condition.keyguard_locked",
                mapOf("value" to ConfigValue.BooleanValue(true)),
            )
            "ScreenOrientationIsPort" -> noBusinessCondition(
                any, importerId, fields, "android.condition.orientation",
                mapOf("orientation" to ConfigValue.StringValue("portrait")),
            )
            "IsInCall" -> noBusinessCondition(
                any, importerId, fields, "android.condition.phone_call_state",
                mapOf("state" to ConfigValue.StringValue("offhook")),
            )
            "IsRinging" -> noBusinessCondition(
                any, importerId, fields, "android.condition.phone_call_state",
                mapOf("state" to ConfigValue.StringValue("ringing")),
            )
            "IsHeadsetPlug" -> {
                if (!fields.onlyBusinessFields(1)) null
                else fields.varint(1)?.let { value ->
                    binaryFeature(any, importerId, "android.condition.headset_connected", mapOf("value" to ConfigValue.BooleanValue(value != 0L)))
                }
            }
            "RequireAPMMode" -> {
                if (!fields.onlyBusinessFields(1)) null
                else fields.varint(1)?.let { value ->
                    binaryFeature(any, importerId, "android.condition.airplane_mode", mapOf("value" to ConfigValue.BooleanValue(value != 0L)))
                }
            }
            "RequireRingerMode" -> {
                if (!fields.onlyBusinessFields(1)) null
                else when (fields.varint(1)?.toInt()) {
                    0 -> binaryFeature(any, importerId, "android.condition.ringer_mode", mapOf("mode" to ConfigValue.StringValue("silent")))
                    1 -> binaryFeature(any, importerId, "android.condition.ringer_mode", mapOf("mode" to ConfigValue.StringValue("vibrate")))
                    2 -> binaryFeature(any, importerId, "android.condition.ringer_mode", mapOf("mode" to ConfigValue.StringValue("normal")))
                    else -> null
                }
            }
            "RequireIMEVisibility" -> {
                if (!fields.onlyBusinessFields(1)) null
                else fields.varint(1)?.let { value ->
                    binaryFeature(
                        any,
                        importerId,
                        "android.condition.ime_visible",
                        mapOf("value" to ConfigValue.BooleanValue(value != 0L)),
                    )
                }
            }
            "RequireNotificationPanelExpanded" -> {
                if (!fields.onlyBusinessFields(1)) null
                else fields.varint(1)?.let { value ->
                    binaryFeature(
                        any,
                        importerId,
                        "android.condition.notification_panel_expanded",
                        mapOf("value" to ConfigValue.BooleanValue(value != 0L)),
                    )
                }
            }
            else -> null
        }
    }

    fun factEnabled(any: AnyStub): Boolean {
        if (any.isJson) {
            val obj = Json.parseToJsonElement(any.value.toString(Charsets.UTF_8)) as? JsonObject ?: return true
            return (obj["isDisabled"] as? JsonPrimitive)?.booleanOrNull != true
        }
        return runCatching { ProtoFields(any.value).varint(101) != 1L }.getOrDefault(true)
    }

    fun conditionEnabled(any: AnyStub): Boolean {
        if (any.isJson) {
            val obj = Json.parseToJsonElement(any.value.toString(Charsets.UTF_8)) as? JsonObject ?: return true
            return (obj["isDisabled"] as? JsonPrimitive)?.booleanOrNull != true
        }
        return runCatching { ProtoFields(any.value).varint(96) != 1L }.getOrDefault(true)
    }

    fun conditionInverted(any: AnyStub): Boolean {
        if (any.isJson) {
            val obj = Json.parseToJsonElement(any.value.toString(Charsets.UTF_8)) as? JsonObject ?: return false
            return (obj["isInvert"] as? JsonPrimitive)?.booleanOrNull == true
        }
        return runCatching { ProtoFields(any.value).varint(98) == 1L }.getOrDefault(false)
    }

    fun actionBreaksOnError(any: AnyStub): Boolean {
        if (any.isJson) {
            val obj = Json.parseToJsonElement(any.value.toString(Charsets.UTF_8)) as? JsonObject ?: return false
            val value = obj["actionOnError"] as? JsonPrimitive ?: return false
            return value.intOrNull == 1 ||
                value.contentOrNull?.substringAfterLast('_')?.equals("break", ignoreCase = true) == true
        }
        return runCatching { ProtoFields(any.value).varint(97) == 1L }.getOrDefault(false)
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
        if (obj["customContextDataKey"] != null && obj["customContextDataKey"] !is JsonNull) return null
        val raw = any.value.toString(Charsets.UTF_8)
        return when (shortName(any.typeUrl)) {
            "NoAction" -> {
                if (!jsonBusinessKeysOnly(obj, "icon")) return null
                sourceFeature("core.noop", importerId, any.typeUrl, raw)
            }
            "MediaPlayback" -> {
                if (!jsonBusinessKeysOnly(obj, "action")) return null
                val mode = (obj["action"] as? JsonPrimitive)?.let {
                    it.intOrNull ?: shortXMediaPlaybackNameToNumber(it.contentOrNull.orEmpty())
                } ?: 0
                val cmd = shortXMediaPlaybackCommand(mode) ?: return null
                sourceFeature("android.media.transport", importerId, any.typeUrl, raw,
                    extra = mapOf("command" to ConfigValue.StringValue(cmd)))
            }
            "SetVolume" -> {
                if (!jsonBusinessKeysOnly(obj, "type", "index")) return null
                val type = (obj["type"] as? JsonPrimitive)?.intOrNull ?: return null
                val level = (obj["index"] as? JsonPrimitive)?.intOrNull ?: return null
                val stream = shortXStreamFromAndroidType(type) ?: return null
                if (level !in 0..1000) return null
                sourceFeature("android.audio.volume.set", importerId, any.typeUrl, raw,
                    extra = mapOf(
                        "stream" to ConfigValue.StringValue(stream),
                        "unit" to ConfigValue.StringValue("index"),
                        "index" to ConfigValue.NumberValue(level.toDouble()),
                    ))
            }
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
            "SetStatusBarIcon" -> nativeJsonStatusBarIcon(obj, any, importerId, raw, "show")
            "RemoveStatusBarIcon" -> nativeJsonStatusBarIcon(obj, any, importerId, raw, "remove")
            "StopService" -> nativeJsonStopServices(obj, any, importerId, raw)
            "StartService" -> nativeJsonStartService(obj, any, importerId, raw)
            "GetScreenOnTime" -> nativeJsonGetScreenOnTime(obj, any, importerId, raw)
            "StartAppProcess" -> nativeJsonStartAppProcess(obj, any, importerId, raw)
            "StartAppProcessByPkg" -> nativeJsonStartAppProcessByPkg(obj, any, importerId, raw)
            "ShowStatusBarChip" -> nativeJsonShowStatusChip(obj, any, importerId, raw)
            "HideStatusBarClip" -> if (jsonBusinessKeysSafe(obj, emptySet()))
                sourceFeature("android.status_chip.control", importerId, any.typeUrl, raw,
                    extra = mapOf(
                        "mode" to ConfigValue.StringValue("hide"),
                        "chipId" to ConfigValue.StringValue("shortx"),
                    ))
                else null
            "ShowGlobalActionsMenu" -> if (jsonBusinessKeysSafe(obj, emptySet()))
                sourceFeature("android.global_actions.show", importerId, any.typeUrl, raw)
                else null
            "StopAudioRecording" -> if (jsonBusinessKeysSafe(obj, emptySet()))
                sourceFeature("android.audio.record.stop", importerId, any.typeUrl, raw)
                else null
            "AreaScreenshot" -> if (obj.keys.all { it in setOf("@type", "type", "typeUrl", "type_url", "id", "isDisabled", "note", "actionOnError") })
                sourceFeature("android.screenshot.area_select", importerId, any.typeUrl, raw)
                else null
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
                sourceFeature("android.shell.execute", importerId, any.typeUrl, raw,
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
            "ExpandNotification" -> sourceFeature(
                "android.status_bar.control", importerId, any.typeUrl, raw,
                extra = mapOf("mode" to ConfigValue.StringValue("notifications")),
            )
            "RequestAudioFocus" -> {
                val request = (obj["isRequest"] as? JsonPrimitive)?.booleanOrNull ?: return null
                sourceFeature(
                    if (request) "android.audio.focus.request" else "android.audio.focus.abandon",
                    importerId,
                    any.typeUrl,
                    raw,
                    extra = if (request) mapOf("gain" to ConfigValue.StringValue("gain")) else emptyMap(),
                )
            }
            "PlayRingtone" -> {
                val ringtone = obj["ringtone"] as? JsonObject ?: return null
                val uri = (ringtone["uri"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
                sourceFeature(
                    "android.audio.play", importerId, any.typeUrl, raw,
                    extra = mapOf(
                        "source" to ConfigValue.StringValue(uri),
                        "volume" to ConfigValue.NumberValue(100.0),
                        "loop" to ConfigValue.BooleanValue(false),
                        "waitForCompletion" to ConfigValue.BooleanValue(false),
                    ),
                )
            }
            else -> null
        }
    }

    private fun nativeJsonCondition(any: AnyStub, importerId: String): FeatureRef? {
        val obj = Json.parseToJsonElement(any.value.toString(Charsets.UTF_8)) as? JsonObject ?: return null
        if (obj["customContextDataKey"] != null && obj["customContextDataKey"] !is JsonNull) return null
        val raw = any.value.toString(Charsets.UTF_8)
        return when (shortName(any.typeUrl)) {
            "RequireFactTag" -> (obj["tag"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { tag ->
                sourceFeature("core.condition.event_tag", importerId, any.typeUrl, raw, extra = mapOf("tag" to ConfigValue.StringValue(tag)))
            }
            "TRUE", "True" -> sourceFeature("core.boolean", importerId, any.typeUrl, raw, extra = mapOf("value" to ConfigValue.BooleanValue(true)))
            "FALSE", "False" -> sourceFeature("core.boolean", importerId, any.typeUrl, raw, extra = mapOf("value" to ConfigValue.BooleanValue(false)))
            "ScreenIsOn" -> sourceFeature("android.condition.screen", importerId, any.typeUrl, raw, extra = mapOf("value" to ConfigValue.BooleanValue(true)))
            "VPNIsConnected" -> sourceFeature(
                "android.condition.network_profile", importerId, any.typeUrl, raw,
                extra = mapOf(
                    "connected" to ConfigValue.BooleanValue(true),
                    "transport" to ConfigValue.StringValue("vpn"),
                    "validated" to ConfigValue.StringValue("any"),
                    "metered" to ConfigValue.StringValue("any"),
                ),
            )
            "ChargeState" -> (obj["requireIsCharge"] as? JsonPrimitive)?.booleanOrNull?.let {
                sourceFeature("android.condition.charging", importerId, any.typeUrl, raw, extra = mapOf("value" to ConfigValue.BooleanValue(it)))
            }
            "RequireWifiConnected" -> sourceFeature(
                "android.condition.wifi_network", importerId, any.typeUrl, raw,
                extra = mapOf(
                    "connected" to ConfigValue.StringValue("connected"),
                    "ssid" to ConfigValue.StringValue((obj["requiredSSID"] as? JsonPrimitive)?.contentOrNull.orEmpty()),
                ),
            )
            "RequireWifiDisconnected" -> sourceFeature(
                "android.condition.wifi_network", importerId, any.typeUrl, raw,
                extra = mapOf("connected" to ConfigValue.StringValue("disconnected")),
            )
            "KeyguardIsLocked" -> sourceFeature("android.condition.keyguard_locked", importerId, any.typeUrl, raw, extra = mapOf("value" to ConfigValue.BooleanValue(true)))
            "ScreenOrientationIsPort" -> sourceFeature("android.condition.orientation", importerId, any.typeUrl, raw, extra = mapOf("orientation" to ConfigValue.StringValue("portrait")))
            "IsInCall" -> sourceFeature("android.condition.phone_call_state", importerId, any.typeUrl, raw, extra = mapOf("state" to ConfigValue.StringValue("offhook")))
            "IsRinging" -> sourceFeature("android.condition.phone_call_state", importerId, any.typeUrl, raw, extra = mapOf("state" to ConfigValue.StringValue("ringing")))
            "IsHeadsetPlug" -> (obj["isPlug"] as? JsonPrimitive)?.booleanOrNull?.let {
                sourceFeature("android.condition.headset_connected", importerId, any.typeUrl, raw, extra = mapOf("value" to ConfigValue.BooleanValue(it)))
            }
            "RequireAPMMode" -> (obj["isAPMEnable"] as? JsonPrimitive)?.booleanOrNull?.let {
                sourceFeature("android.condition.airplane_mode", importerId, any.typeUrl, raw, extra = mapOf("value" to ConfigValue.BooleanValue(it)))
            }
            "RequireRingerMode" -> {
                val mode = jsonRingerMode(obj["mode"] as? JsonPrimitive)
                val name = when (mode) { 0 -> "silent"; 1 -> "vibrate"; 2 -> "normal"; else -> null }
                name?.let { sourceFeature("android.condition.ringer_mode", importerId, any.typeUrl, raw, extra = mapOf("mode" to ConfigValue.StringValue(it))) }
            }
            "RequireIMEVisibility" -> (obj["isShown"] as? JsonPrimitive)?.booleanOrNull?.let {
                sourceFeature(
                    "android.condition.ime_visible",
                    importerId,
                    any.typeUrl,
                    raw,
                    extra = mapOf("value" to ConfigValue.BooleanValue(it)),
                )
            }
            "RequireNotificationPanelExpanded" -> (obj["isExpand"] as? JsonPrimitive)?.booleanOrNull?.let {
                sourceFeature(
                    "android.condition.notification_panel_expanded",
                    importerId,
                    any.typeUrl,
                    raw,
                    extra = mapOf("value" to ConfigValue.BooleanValue(it)),
                )
            }
            else -> null
        }
    }

    fun suggestedConditionFeature(typeUrl: String): String? = when (shortName(typeUrl)) {
        "CurrentPkgList", "CurrentPkgListByPkg", "CurrentActivity" -> "android.condition.app_foreground"
        "BatteryPercent" -> "android.condition.battery_level"
        "AvailableMemory" -> "android.condition.memory_available"
        "ConnectedWifiSignal", "RequireWifiConnected", "RequireWifiDisconnected" -> "android.condition.wifi_network"
        "AppIsRunning", "AppIsNotRunning", "ProcessIsRunning" -> "android.condition.app_process_running"
        "EvaluateScreenOnTime" -> "android.condition.screen_on_time"
        "ScreenIsOn" -> "android.condition.screen"
        "VPNIsConnected" -> "android.condition.network_profile"
        "ChargeState" -> "android.condition.charging"
        "PlugState" -> "android.condition.charging_source"
        "RequireMobileDataEnabled" -> "android.condition.mobile_data_enabled"
        "AppHasNotification" -> "android.condition.notification_active"
        "KeyguardIsLocked" -> "android.condition.keyguard_locked"
        "ScreenOrientationIsPort", "RequireScreenRotate", "RequireWindowRotation" -> "android.condition.orientation"
        "IsInCall", "IsRinging" -> "android.condition.phone_call_state"
        "IsHeadsetPlug" -> "android.condition.headset_connected"
        "RequireAPMMode" -> "android.condition.airplane_mode"
        "RequireRingerMode" -> "android.condition.ringer_mode"
        "RequireIMEVisibility" -> "android.condition.ime_visible"
        "RequireNotificationPanelExpanded" -> "android.condition.notification_panel_expanded"
        else -> null
    }

    fun suggestedEventFeature(typeUrl: String): String? = when (shortName(typeUrl)) {
        "ScreenOn" -> "android.event.screen_on"
        "ScreenOff" -> "android.event.screen_off"
        "UserPresent", "UserPresentAtTheFirstTime" -> "android.event.user_present"
        "BatteryLevelChanged", "BatteryTemperatureChanged" -> "android.event.battery_changed"
        "ChargerPlug" -> "android.event.power_connected"
        "ChargerUnplug" -> "android.event.power_disconnected"
        "AppBecomeFg" -> "android.event.app_foreground"
        "AppBecomeBg" -> "android.event.app_background"
        "BTStatusChanged" -> "android.event.bluetooth_state"
        "WifiStatusChanged" -> "android.event.wifi_adapter_state_filtered"
        "WifiConnectedTo", "WifiDisconnectedFrom", "ConnectedWifiSignalLevelChanged" -> "android.event.wifi_changed"
        "NFCStatusChanged" -> "android.event.nfc_state_changed"
        "LocationStatusChanged" -> "android.event.location_mode_changed"
        "DarkModeStatusChanged" -> "android.event.dark_mode_changed"
        "APMStatusChanged" -> "android.event.airplane_mode_changed"
        "NotificationPosted" -> "android.event.notification_posted"
        "NotificationRemoved" -> "android.event.notification_removed"
        "AppAdded" -> "android.event.package_added"
        "AppRemoved" -> "android.event.package_removed"
        "AppUpdated" -> "android.event.package_replaced"
        "Broadcast" -> "android.event.broadcast"
        "VPNConnected", "VPNDisconnected" -> "android.event.network_profile_changed"
        "CallStateChanged" -> "android.event.phone_state_changed"
        "ClipboardContentChanged" -> "android.event.clipboard_changed"
        "HeadsetPlug" -> "android.event.headset_changed"
        "NFCTagDiscover" -> "android.event.nfc_tag"
        "KeyEvent" -> "android.event.hardware_key"
        "AdvancedKeyEvent" -> "android.event.hardware_key_gesture"
        "CombineKeyEvent" -> "android.event.hardware_key_combo"
        "UsbDeviceAttached", "UsbDeviceDetached" -> "android.event.usb_device_changed"
        "ShakeDevice" -> "android.event.shake"
        "LightSensor", "ProximitySensor", "AccelerometerSensor" -> "android.event.sensor_value"
        else -> null
    }

    fun suggestedActionFeature(typeUrl: String): String? = when (shortName(typeUrl)) {
        "ShowToast" -> "android.toast.show"
        "NoAction" -> "core.noop"
        "Delay" -> "core.delay"
        "LaunchApp", "LaunchAppByPkg" -> "android.app.launch"
        "WriteClipboard" -> "android.clipboard.set"
        "ReadClipboard" -> "android.clipboard.get"
        "ShellCommand" -> "android.shell.execute"
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
        "SendSMS" -> "android.sms.send"
        "StartService", "StopService" -> "android.service.control"
        "LockDeviceNow" -> "system.screen.sleep"
        "ExpandNotification" -> "android.status_bar.control"
        "PlayRingtone" -> "android.audio.play"
        "RequestAudioFocus" -> "android.audio.focus.request"
        "GetScreenOnTime" -> "android.screen_on_time.get"
        "MatchRegex" -> "data.regex.matches"
        "ReplaceRegex" -> "data.regex.replace"
        else -> null
    }

    private fun expandNotification(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields()) return null
        return binaryFeature(
            any,
            importerId,
            "android.status_bar.control",
            mapOf("mode" to ConfigValue.StringValue("notifications")),
        )
    }

    private fun requestAudioFocus(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1)) return null
        val request = fields.varint(1)?.let { it != 0L } ?: return null
        return binaryFeature(
            any,
            importerId,
            if (request) "android.audio.focus.request" else "android.audio.focus.abandon",
            if (request) mapOf("gain" to ConfigValue.StringValue("gain")) else emptyMap(),
        )
    }

    private fun playRingtone(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
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

    private fun noBusinessFact(
        any: AnyStub,
        importerId: String,
        fields: ProtoFields,
        target: String,
        extra: Map<String, ConfigValue> = emptyMap(),
    ): FeatureRef? {
        if (!fields.onlyBusinessFields()) return null
        return binaryFeature(any, importerId, target, extra)
    }

    private fun onOffAnyFact(
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

    private fun noBusinessCondition(
        any: AnyStub,
        importerId: String,
        fields: ProtoFields,
        target: String,
        extra: Map<String, ConfigValue>,
    ): FeatureRef? {
        if (!fields.onlyBusinessFields()) return null
        return binaryFeature(any, importerId, target, extra)
    }

    private fun jsonArrayEmpty(obj: JsonObject, key: String): Boolean =
        (obj[key] as? JsonArray)?.isEmpty() != false

    private fun noAction(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1)) return null
        return binaryFeature(any, importerId, "core.noop", emptyMap())
    }

    private fun mediaPlayback(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1)) return null
        val mode = (fields.varint(1) ?: 0L).takeIf { it in 0L..6L }?.toInt() ?: return null
        val command = shortXMediaPlaybackCommand(mode) ?: return null
        return binaryFeature(any, importerId, "android.media.transport",
            mapOf("command" to ConfigValue.StringValue(command)))
    }

    private fun setVolume(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
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

    private fun jsonBusinessKeysOnly(obj: JsonObject, vararg allowed: String): Boolean =
        obj.keys.all { it in SOURCE_METADATA_KEYS || it in allowed }

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
    fun nativeChipInteraction(any: AnyStub, importerId: String, chipId: String): ShortXChipInteractionMapping? {
        if (!Regex("[a-z][a-z0-9_]{0,23}").matches(chipId)) return null
        if (shortName(any.typeUrl) != "ShowStatusBarChip") return null
        if (any.isJson) {
            val obj = runCatching { Json.parseToJsonElement(any.value.toString(Charsets.UTF_8)) as? JsonObject }
                .getOrNull() ?: return null
            if (!jsonBusinessKeysSafe(obj, setOf("text", "icon", "clickAction", "longClickAction")) ||
                obj["customContextDataKey"] != null && obj["customContextDataKey"] !is JsonNull) return null
            val title = (obj["text"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it.length <= 48 }
                ?: return null
            val icon = (obj["icon"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val res = nativeAndroidDrawableName(icon)
            if (icon.isNotBlank() && res == null) return null
            fun parsed(key: String): List<AnyStub>? {
                val data = obj[key] as? JsonArray ?: return if (obj[key] == null) emptyList() else null
                if (data.size > 24) return null
                return data.mapIndexed { index, element ->
                    val value = element as? JsonObject ?: return null
                    val type = sequenceOf("@type", "typeUrl", "type_url", "type")
                        .mapNotNull { (value[it] as? JsonPrimitive)?.contentOrNull }
                        .firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
                    AnyStub(type, value.toString().encodeToByteArray(), isJson = true)
                }
            }
            val click = parsed("clickAction") ?: return null
            val long = parsed("longClickAction") ?: return null
            if (click.size + long.size > 24) return null
            return ShortXChipInteractionMapping(
                sourceFeature("android.status_chip.control", importerId, any.typeUrl,
                    any.value.toString(Charsets.UTF_8), extra = chipDisplayConfig(chipId, title, res)),
                click, long,
            )
        }
        val data = runCatching { ProtoFields(any.value) }.getOrNull() ?: return null
        if (!data.onlyBusinessFields(1, 2, 3, 4) || data.has(96)) return null
        val title = data.string(1)?.takeIf { it.isNotBlank() && it.length <= 48 } ?: return null
        val icon = data.string(2).orEmpty()
        val res = nativeAndroidDrawableName(icon)
        if (icon.isNotBlank() && res == null) return null
        fun parsed(field: Int): List<AnyStub>? {
            val chunks = data.allBytes(field)
            if (chunks.size > 24) return null
            return chunks.map { bytes ->
                val nested = runCatching { ProtoFields(bytes) }.getOrNull() ?: return null
                if (!nested.onlyBusinessFields(1, 2)) return null
                val type = nested.string(1)?.takeIf { it.isNotBlank() } ?: return null
                val raw = nested.bytes(2) ?: return null
                AnyStub(type, raw)
            }
        }
        val click = parsed(3) ?: return null
        val long = parsed(4) ?: return null
        if (click.size + long.size > 24) return null
        return ShortXChipInteractionMapping(
            binaryFeature(any, importerId, "android.status_chip.control", chipDisplayConfig(chipId, title, res)),
            click, long,
        )
    }

    private fun chipDisplayConfig(chipId: String, title: String, drawable: String?) = mapOf(
        "mode" to ConfigValue.StringValue("show"),
        "chipId" to ConfigValue.StringValue(chipId),
        "text" to ConfigValue.StringValue(title),
        "iconMode" to ConfigValue.StringValue(if (drawable == null) "none" else "android_drawable"),
        "icon" to ConfigValue.StringValue(drawable.orEmpty()),
    )

    /** ShortX AppPkg repeated #1 and package-set references #2. No UI launch. */
    private fun nativeStartAppProcess(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1, 2)) return null
        val values = fields.allBytes(1).map { raw ->
            val nested = runCatching { ProtoFields(raw) }.getOrNull() ?: return null
            if (!nested.onlyBusinessFields(1, 2)) return null
            val pkg = nested.string(1)?.takeIf(::nativeProcessPackageValid) ?: return null
            val user = (nested.varint(2) ?: 0L).takeIf { it in 0L..99L } ?: return null
            pkg to user
        }
        val users = values.map { it.second }.distinct()
        if (users.size > 1) return null // One YAuto action has one user ID.
        val names = values.map { it.first }.distinct()
        val packageSets = fields.allStrings(2).distinct()
        if (packageSets.any { !Regex("[A-Za-z_][A-Za-z0-9_.:-]{0,95}").matches(it) }) return null
        if ((names.isEmpty() && packageSets.isEmpty()) || names.size + packageSets.size > 24) return null
        return binaryFeature(any, importerId, "android.app.process.start", mapOf(
            "packages" to ConfigValue.StringValue(names.joinToString("\n")),
            "packageSets" to ConfigValue.StringValue(packageSets.joinToString("\n")),
            "userId" to ConfigValue.NumberValue((users.singleOrNull() ?: 0L).toDouble()),
        ))
    }

    /** ShortX pkgAndUsers repeated StringPair; different user IDs stay lossless source nodes. */
    private fun nativeStartAppProcessByPkg(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1)) return null
        val targets = fields.allBytes(1).map { raw ->
            val nested = runCatching { ProtoFields(raw) }.getOrNull() ?: return null
            if (!nested.onlyBusinessFields(1, 2)) return null
            val pkg = nested.string(1)?.takeIf(::nativeProcessPackageValid) ?: return null
            val uid = nested.string(2)?.toLongOrNull()?.takeIf { it in 0L..99L } ?: return null
            pkg to uid
        }
        if (targets.isEmpty() || targets.size > 24 || targets.map { it.second }.distinct().size != 1) return null
        return binaryFeature(any, importerId, "android.app.process.start", mapOf(
            "packages" to ConfigValue.StringValue(targets.map { it.first }.distinct().joinToString("\n")),
            "userId" to ConfigValue.NumberValue(targets.first().second.toDouble()),
        ))
    }

    /** Preserve embedded click/long-click Any action chains until their execution is identical. */
    private fun nativeShowStatusChip(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1, 2, 3, 4) || fields.has(3) || fields.has(4)) return null
        val text = fields.string(1)?.takeIf { it.isNotBlank() && it.length <= 48 } ?: return null
        val icon = fields.string(2).orEmpty()
        val frameworkName = nativeAndroidDrawableName(icon)
        if (icon.isNotBlank() && frameworkName == null) return null
        return binaryFeature(any, importerId, "android.status_chip.control", mapOf(
            "mode" to ConfigValue.StringValue("show"),
            "chipId" to ConfigValue.StringValue("shortx"),
            "text" to ConfigValue.StringValue(text),
            "iconMode" to ConfigValue.StringValue(if (frameworkName == null) "none" else "android_drawable"),
            "icon" to ConfigValue.StringValue(frameworkName.orEmpty()),
        ))
    }

    private fun nativeJsonStartAppProcess(
        obj: JsonObject, any: AnyStub, importerId: String, raw: String,
    ): FeatureRef? {
        if (!jsonBusinessKeysSafe(obj, setOf("appPkg", "pkgSets"))) return null
        val targets = (obj["appPkg"] as? JsonArray)?.map { rawTarget ->
            val target = rawTarget as? JsonObject ?: return null
            if (target.keys.any { it !in setOf("pkgName", "userId") }) return null
            val pkg = (target["pkgName"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf(::nativeProcessPackageValid) ?: return null
            val user = (target["userId"] as? JsonPrimitive)?.longOrNull ?: 0L
            if (user !in 0L..99L) return null
            pkg to user
        }.orEmpty()
        val sets = (obj["pkgSets"] as? JsonArray)?.map { item ->
            (item as? JsonPrimitive)?.contentOrNull?.takeIf {
                Regex("[A-Za-z_][A-Za-z0-9_.:-]{0,95}").matches(it)
            } ?: return null
        }.orEmpty()
        if (targets.map { it.second }.distinct().size > 1 ||
            (targets.isEmpty() && sets.isEmpty()) || targets.size + sets.size > 24) return null
        return sourceFeature("android.app.process.start", importerId, any.typeUrl, raw, extra = mapOf(
            "packages" to ConfigValue.StringValue(targets.map { it.first }.distinct().joinToString("\n")),
            "packageSets" to ConfigValue.StringValue(sets.distinct().joinToString("\n")),
            "userId" to ConfigValue.NumberValue((targets.firstOrNull()?.second ?: 0L).toDouble()),
        ))
    }

    private fun nativeJsonStartAppProcessByPkg(
        obj: JsonObject, any: AnyStub, importerId: String, raw: String,
    ): FeatureRef? {
        if (!jsonBusinessKeysSafe(obj, setOf("pkgAndUsers"))) return null
        val targets = (obj["pkgAndUsers"] as? JsonArray)?.map { item ->
            val pair = item as? JsonObject ?: return null
            if (pair.keys.any { it !in setOf("first", "second") }) return null
            val pkg = (pair["first"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf(::nativeProcessPackageValid) ?: return null
            val user = (pair["second"] as? JsonPrimitive)?.contentOrNull
                ?.toLongOrNull()?.takeIf { it in 0L..99L } ?: return null
            pkg to user
        } ?: return null
        if (targets.isEmpty() || targets.size > 24 || targets.map { it.second }.distinct().size != 1) return null
        return sourceFeature("android.app.process.start", importerId, any.typeUrl, raw, extra = mapOf(
            "packages" to ConfigValue.StringValue(targets.map { it.first }.distinct().joinToString("\n")),
            "userId" to ConfigValue.NumberValue(targets.first().second.toDouble()),
        ))
    }

    private fun nativeJsonShowStatusChip(obj: JsonObject, any: AnyStub, importerId: String, raw: String): FeatureRef? {
        if (!jsonBusinessKeysSafe(obj, setOf("text", "icon", "clickAction", "longClickAction")) ||
            (obj["clickAction"] as? JsonArray)?.isNotEmpty() == true ||
            (obj["longClickAction"] as? JsonArray)?.isNotEmpty() == true) return null
        val text = (obj["text"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it.length <= 48 }
            ?: return null
        val icon = (obj["icon"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val frameworkName = nativeAndroidDrawableName(icon)
        if (icon.isNotEmpty() && frameworkName == null) return null
        return sourceFeature("android.status_chip.control", importerId, any.typeUrl, raw, extra = mapOf(
            "mode" to ConfigValue.StringValue("show"),
            "chipId" to ConfigValue.StringValue("shortx"),
            "text" to ConfigValue.StringValue(text),
            "iconMode" to ConfigValue.StringValue(if (frameworkName == null) "none" else "android_drawable"),
            "icon" to ConfigValue.StringValue(frameworkName.orEmpty()),
        ))
    }

    private fun nativeStatusBarIcon(
        any: AnyStub, importerId: String, fields: ProtoFields, mode: String,
    ): FeatureRef? {
        if (!fields.onlyBusinessFields(*(if (mode == "show") intArrayOf(1, 2) else intArrayOf(1)))) return null
        val slot = importedYAutoStatusSlot(fields.string(1)) ?: return null
        val input = fields.string(2)
        val icon = if (mode == "show") importedStatusIcon(input) else "info"
        val drawable = if (mode == "show" && icon == null) nativeAndroidDrawableName(input.orEmpty()) else null
        if (mode == "show" && icon == null && drawable == null) return null
        return binaryFeature(any, importerId, "android.status_icon.control", mapOf(
            "mode" to ConfigValue.StringValue(mode),
            "slot" to ConfigValue.StringValue(slot),
            "icon" to ConfigValue.StringValue(icon ?: "info"),
            "iconSource" to ConfigValue.StringValue(if (drawable == null) "built_in" else "android_drawable"),
            "drawable" to ConfigValue.StringValue(drawable.orEmpty()),
        ))
    }

    private fun nativeJsonStatusBarIcon(
        obj: JsonObject, any: AnyStub, importerId: String, raw: String, mode: String,
    ): FeatureRef? {
        if (!jsonBusinessKeysSafe(obj, if (mode == "show") setOf("slot", "icon") else setOf("slot"))) return null
        val slot = importedYAutoStatusSlot((obj["slot"] as? JsonPrimitive)?.contentOrNull) ?: return null
        val input = (obj["icon"] as? JsonPrimitive)?.contentOrNull
        val icon = if (mode == "show") importedStatusIcon(input) else "info"
        val drawable = if (mode == "show" && icon == null) nativeAndroidDrawableName(input.orEmpty()) else null
        if (mode == "show" && icon == null && drawable == null) return null
        return sourceFeature("android.status_icon.control", importerId, any.typeUrl, raw,
            extra = mapOf(
                "mode" to ConfigValue.StringValue(mode),
                "slot" to ConfigValue.StringValue(slot),
                "icon" to ConfigValue.StringValue(icon ?: "info"),
                "iconSource" to ConfigValue.StringValue(if (drawable == null) "built_in" else "android_drawable"),
                "drawable" to ConfigValue.StringValue(drawable.orEmpty()),
            ),
        )
    }

    /**
     * Support only an unambiguous flattened service component. Other AppComponent layouts,
     * selectors and unknown business fields remain raw compatibility nodes.
     */
    private fun nativeStopServices(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1)) return null
        val components = fields.allBytes(1).map { bytes ->
            val nested = runCatching { ProtoFields(bytes) }.getOrNull() ?: return null
            if (!nested.onlyBusinessFields(1)) return null
            nested.string(1)?.takeIf(::importedServiceComponentValid) ?: return null
        }.distinct()
        if (components.isEmpty() || components.size > 32) return null
        return binaryFeature(any, importerId, "android.service.control",
            mapOf(
                "mode" to ConfigValue.StringValue("stop"),
                "components" to ConfigValue.StringValue(components.joinToString("\n")),
            ),
        )
    }

    /**
     * ShortX's documented StopService JSON uses AppComponent {
     *   pkg: {pkgName, userId}, className
     * }. We only flatten explicit components whose user IDs agree.
     * Legacy flattened test fixtures are still accepted.
     */
    private fun nativeJsonStopServices(
        obj: JsonObject, any: AnyStub, importerId: String, raw: String,
    ): FeatureRef? {
        if (!jsonBusinessKeysSafe(obj, setOf("services"))) return null
        val items = obj["services"] as? JsonArray ?: return null
        if (items.isEmpty() || items.size > 32) return null
        val targets = items.map { item ->
            val nested = item as? JsonObject ?: return null
            if (nested.keys == setOf("component")) {
                val component = (nested["component"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf(::importedServiceComponentValid) ?: return null
                component to 0L
            } else {
                if (nested.keys != setOf("pkg", "className")) return null
                val pkg = nested["pkg"] as? JsonObject ?: return null
                if (!pkg.keys.all { it in setOf("pkgName", "userId") }) return null
                val packageName = (pkg["pkgName"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf(::nativeProcessPackageValid) ?: return null
                val parsedUser = (pkg["userId"] as? JsonPrimitive)?.longOrNull
                if ("userId" in pkg && parsedUser == null) return null
                val userId = parsedUser ?: 0L
                if (userId !in 0L..999L) return null
                val className = (nested["className"] as? JsonPrimitive)?.contentOrNull ?: return null
                val component = nativeServiceComponent(packageName, className) ?: return null
                component to userId
            }
        }
        if (targets.map { it.second }.distinct().size != 1) return null
        return sourceFeature("android.service.control", importerId, any.typeUrl, raw,
            extra = mapOf(
                "mode" to ConfigValue.StringValue("stop"),
                "components" to ConfigValue.StringValue(targets.map { it.first }.distinct().joinToString("\n")),
                "userId" to ConfigValue.NumberValue(targets.first().second.toDouble()),
            ),
        )
    }

    /** Direct service Intent subset: reject implicit targets, unknown keys and unsupported extras. */
    private fun nativeJsonGetScreenOnTime(
        obj: JsonObject, any: AnyStub, importerId: String, raw: String,
    ): FeatureRef? {
        if (!jsonBusinessKeysSafe(obj, setOf("from"))) return null
        val fromValue = (obj["from"] as? JsonPrimitive)?.intOrNull
        if ("from" in obj && fromValue == null) return null
        val from = when (fromValue ?: 0) {
            0 -> "last_screen_off"
            1 -> "system_ready"
            else -> return null
        }
        return sourceFeature("android.screen_on_time.get", importerId, any.typeUrl, raw,
            extra = mapOf(
                "from" to ConfigValue.StringValue(from),
                "resultVariable" to ConfigValue.StringValue("screenOnTime"),
            ))
    }

    private fun nativeGetScreenOnTime(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1)) return null
        val from = when (fields.varint(1) ?: 0L) {
            0L -> "last_screen_off"
            1L -> "system_ready"
            else -> return null
        }
        return binaryFeature(any, importerId, "android.screen_on_time.get",
            mapOf(
                "from" to ConfigValue.StringValue(from),
                "resultVariable" to ConfigValue.StringValue("screenOnTime"),
            ))
    }

    private fun nativeJsonStartService(
        obj: JsonObject, any: AnyStub, importerId: String, raw: String,
    ): FeatureRef? {
        if (!jsonBusinessKeysSafe(obj, setOf("intent", "userId", "isForegroundService"))) return null
        val intent = obj["intent"] as? JsonObject ?: return null
        if (!intent.keys.all { it in setOf("pkgName", "className", "action", "data", "flags", "extras") }) return null
        val packageName = (intent["pkgName"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf(::nativeProcessPackageValid) ?: return null
        val className = (intent["className"] as? JsonPrimitive)?.contentOrNull ?: return null
        val component = nativeServiceComponent(packageName, className) ?: return null
        val parsedUser = (obj["userId"] as? JsonPrimitive)?.longOrNull
        if ("userId" in obj && parsedUser == null) return null
        val userId = parsedUser ?: 0L
        if (userId !in 0L..999L) return null
        val parsedForeground = (obj["isForegroundService"] as? JsonPrimitive)?.booleanOrNull
        if ("isForegroundService" in obj && parsedForeground == null) return null
        val foreground = parsedForeground ?: false
        val action = (intent["action"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val dataUri = (intent["data"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (action.isNotBlank() && !Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*").matches(action)) return null
        if (dataUri.isNotBlank() && (dataUri.length > 2_048 ||
                !Regex("[A-Za-z][A-Za-z0-9+.-]*:.*").matches(dataUri))) return null
        val parsedFlags = (intent["flags"] as? JsonPrimitive)?.longOrNull
        if ("flags" in intent && parsedFlags == null) return null
        val flags = parsedFlags ?: 0L
        if (flags !in 0L..4294967295L) return null
        val extras = intent["extras"] as? JsonArray
        if (intent["extras"] != null && extras == null) return null
        if (extras != null && (extras.size > 24 || !extras.all(::shortXServiceExtraSafe) ||
                extras.map { ((it as JsonObject)["key"] as JsonPrimitive).content }.distinct().size != extras.size)) return null
        val extrasJson = extras?.toString().orEmpty()
        return sourceFeature("android.service.control", importerId, any.typeUrl, raw,
            extra = mapOf(
                "mode" to ConfigValue.StringValue(if (foreground) "start_foreground" else "start"),
                "component" to ConfigValue.StringValue(component),
                "userId" to ConfigValue.NumberValue(userId.toDouble()),
                "intentAction" to ConfigValue.StringValue(action),
                "dataUri" to ConfigValue.StringValue(dataUri),
                "intentFlags" to ConfigValue.NumberValue(flags.toDouble()),
                "intentExtrasJson" to ConfigValue.StringValue(extrasJson),
            ),
        )
    }

    private fun shortXServiceExtraSafe(item: JsonElement): Boolean {
        val obj = item as? JsonObject ?: return false
        if (obj.keys != setOf("key", "type", "value")) return false
        val key = (obj["key"] as? JsonPrimitive)?.contentOrNull ?: return false
        val type = (obj["type"] as? JsonPrimitive)?.intOrNull ?: return false
        val value = (obj["value"] as? JsonPrimitive)?.contentOrNull ?: return false
        if (!Regex("[A-Za-z_][A-Za-z0-9_.-]{0,127}").matches(key) || value.length > 4096 ||
            value.contains('\n') || value.contains('\r') || value.contains('\u0000')) return false
        return when (type) {
            0 -> value.toIntOrNull() != null
            1 -> value.toLongOrNull() != null
            2 -> true
            3 -> value == "true" || value == "false"
            4 -> value.toFloatOrNull()?.isFinite() == true
            5 -> value.toDoubleOrNull()?.isFinite() == true
            else -> false
        }
    }

    private fun jsonBusinessKeysSafe(obj: JsonObject, keys: Set<String>): Boolean =
        obj.keys.all { key -> key in keys || key in SOURCE_METADATA_KEYS }

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

    private fun clickText(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1)) return null
        val text = fields.string(1)?.takeIf(String::isNotBlank) ?: return null
        return binaryFeature(
            any,
            importerId,
            "accessibility.click_text",
            mapOf(
                "text" to ConfigValue.StringValue(text),
                "exact" to ConfigValue.BooleanValue(false),
            ),
        )
    }

    private fun shellCommand(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1, 2)) return null
        val command = fields.string(1)?.takeIf { it.isNotBlank() } ?: return null
        return binaryFeature(any, importerId, "android.shell.execute", mapOf("command" to ConfigValue.StringValue(command)))
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

    private fun jsonOnOffAny(value: JsonPrimitive?): String? {
        value ?: return "any"
        value.intOrNull?.let {
            return when (it) { 0 -> "on"; 1 -> "off"; 2 -> "any"; else -> null }
        }
        return when (value.contentOrNull?.substringAfterLast('_')?.lowercase()) {
            "on" -> "on"
            "off" -> "off"
            "any" -> "any"
            else -> null
        }
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

    private fun withFactTag(feature: FeatureRef, tag: String?): FeatureRef {
        val value = tag?.trim().orEmpty()
        if (value.isBlank()) return feature
        return feature.copy(config = feature.config + ("tag" to ConfigValue.StringValue(value)))
    }

    private fun jsonFactTag(obj: JsonObject): String? =
        (obj["tag"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)

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

    private fun shortXKeyGesture(value: Int): String? = when (value) {
        0 -> "single_press"
        1 -> "double_press"
        2 -> "triple_press"
        3 -> "long_press"
        else -> null
    }

    private fun shortName(typeUrl: String): String = typeUrl
        .substringAfterLast('/')
        .substringAfterLast('.')
        .substringAfterLast('$')
}


internal fun shortXMediaPlaybackCommand(value: Int): String? = when (value) {
    0 -> "play"
    1 -> "pause"
    2 -> "next"
    3 -> "previous"
    4 -> "fast_forward"
    5 -> "rewind"
    6 -> "stop"
    else -> null
}

internal fun shortXMediaPlaybackNameToNumber(value: String): Int? = when (value) {
    "MediaPlaybackAction_Play" -> 0
    "MediaPlaybackAction_Pause" -> 1
    "MediaPlaybackAction_SkipToNext" -> 2
    "MediaPlaybackAction_SkipToPrevious" -> 3
    "MediaPlaybackAction_FastForward" -> 4
    "MediaPlaybackAction_Rewind" -> 5
    "MediaPlaybackAction_Stop" -> 6
    else -> null
}

/** Android stream type IDs explicitly supported by the YAuto native audio executor. */
internal fun shortXStreamFromAndroidType(type: Int): String? = when (type) {
    0 -> "voice_call"
    1 -> "system"
    2 -> "ring"
    3 -> "media"
    4 -> "alarm"
    5 -> "notification"
    else -> null
}

/** Strictly preserve an existing private slot rather than mapping a stock SystemUI slot. */
internal fun importedYAutoStatusSlot(value: String?): String? {
    val input = value ?: return null
    if (!input.startsWith("yauto_")) return null
    return input.removePrefix("yauto_").takeIf { Regex("[a-z][a-z0-9_]{0,23}").matches(it) }
}

internal fun nativeProcessPackageValid(value: String): Boolean =
    value.length in 3..180 && Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+").matches(value)

internal fun nativeAndroidDrawableName(value: String): String? =
    value.takeIf { it.startsWith("android:drawable/") }
        ?.removePrefix("android:drawable/")
        ?.takeIf { Regex("[a-z][a-z0-9_]{0,63}").matches(it) }

internal fun importedStatusIcon(value: String?): String? = when (value) {
    // Only explicit Android framework resource identities are semantically equivalent
    // to the platform drawable selected by AndroidStatusIconFeaturePack.
    "android:drawable/ic_dialog_info" -> "info"
    "android:drawable/ic_dialog_alert" -> "warning"
    "android:drawable/ic_lock_idle_lock" -> "lock"
    "android:drawable/ic_menu_upload" -> "upload"
    "android:drawable/ic_menu_save" -> "save"
    else -> null
}

internal fun nativeServiceComponent(packageName: String, className: String): String? {
    val cls = when {
        className.startsWith(".") -> className
        className.startsWith(packageName + ".") -> className
        else -> return null
    }
    val flattened = packageName + "/" + cls
    return flattened.takeIf(::importedServiceComponentValid)
}

internal fun importedServiceComponentValid(value: String): Boolean =
    Regex("""[A-Za-z_][A-Za-z0-9_.]*/[A-Za-z_.$][A-Za-z0-9_.$]*""").matches(value)

private val SOURCE_METADATA_KEYS = setOf(
    "@type", "type", "typeUrl", "type_url", "id", "isDisabled", "note", "actionOnError",
)

internal class ProtoFields(bytes: ByteArray) {
    private val fields = Wire(bytes).fields()
    fun has(number: Int): Boolean = fields.any { it.number == number }

    fun onlyBusinessFields(vararg allowed: Int): Boolean {
        val allowedSet = allowed.toSet()
        return fields.asSequence().filter { it.number < 96 }.all { it.number in allowedSet }
    }

    /** Strict control-flow check: unlike leaf actions, structural nodes cannot retain source
     * metadata or future fields. Only explicitly defaulted control flags (97/98 = 0) are safe.
     */
    fun onlyStructuralFields(vararg allowed: Int): Boolean {
        val accepted = allowed.toSet()
        return fields.all { field ->
            field.number in accepted ||
                ((field.number == 97 || field.number == 98) && field.wire == 0 && field.varint == 0L)
        }
    }

    fun string(number: Int): String? = bytes(number)
        ?.toString(Charsets.UTF_8)
        ?.takeIf { it.isNotEmpty() }

    fun bytes(number: Int): ByteArray? = fields
        .firstOrNull { it.number == number && it.wire == 2 }
        ?.bytes

    fun allBytes(number: Int): List<ByteArray> = fields
        .asSequence()
        .filter { it.number == number && it.wire == 2 }
        .mapNotNull { it.bytes }
        .toList()

    fun allStrings(number: Int): List<String> = allBytes(number)
        .mapNotNull { it.toString(Charsets.UTF_8).takeIf(String::isNotEmpty) }

    fun varint(number: Int): Long? = fields
        .firstOrNull { it.number == number && it.wire == 0 }
        ?.varint
}

internal data class ShortXChipInteractionMapping(
    val feature: FeatureRef,
    val click: List<AnyStub>,
    val longClick: List<AnyStub>,
)
