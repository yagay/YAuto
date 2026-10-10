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
        if (ShortXVerifiedBatchMappings.ownsSource(type)) {
            return ShortXVerifiedBatchMappings.binary(any, importerId, fields)
        }
        ShortXVerifiedBatchMappings.binary(any, importerId, fields)?.let { return it }
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
            "ShowRecentApps" -> nativeShowRecentApps(any, importerId, fields)
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

    internal fun nativeShowRecentApps(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1)) return null
        // The default protobuf enum value is On=0; reject malformed/non-varint fields.
        val state = fields.varint(1) ?: if (fields.has(1)) return null else 0L
        if (state != 0L) return null
        return binaryFeature(any, importerId, "accessibility.recents.show", emptyMap())
    }

    internal fun noFieldAction(any: AnyStub, importerId: String, fields: ProtoFields, target: String): FeatureRef? {
        if (!fields.onlyBusinessFields()) return null
        return binaryFeature(any, importerId, target, emptyMap())
    }

    internal fun tts(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1)) return null
        val text = fields.string(1) ?: return null
        return binaryFeature(any, importerId, "android.tts.speak", mapOf("text" to ConfigValue.StringValue(text)))
    }

    internal fun openUrl(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1, 2)) return null
        if (!fields.string(2).isNullOrBlank()) return null
        val url = fields.string(1)?.takeIf { it.isNotBlank() } ?: return null
        return binaryFeature(any, importerId, "android.uri.open", mapOf("uri" to ConfigValue.StringValue(url)))
    }

    internal fun clickText(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
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

    internal fun shellCommand(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1, 2)) return null
        val command = fields.string(1)?.takeIf { it.isNotBlank() } ?: return null
        return binaryFeature(any, importerId, "android.shell.execute", mapOf("command" to ConfigValue.StringValue(command)))
    }

    internal fun injectKeyCode(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
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

    internal fun setAutoBrightness(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1)) return null
        val state = fields.varint(1) ?: if (!fields.has(1)) 0L else return null
        if (state !in 0L..1L) return null
        return binaryFeature(any, importerId, "android.display.brightness.set",
            mapOf("mode" to ConfigValue.StringValue(if (state == 1L) "auto" else "manual_keep")))
    }

    internal fun jsonToggle(obj: JsonObject, any: AnyStub, importerId: String, target: String, key: String): FeatureRef? {
        val enabled = (obj[key] as? JsonPrimitive)?.booleanOrNull ?: return null
        return sourceFeature(target, importerId, any.typeUrl, any.value.toString(Charsets.UTF_8),
            extra = mapOf("enabled" to ConfigValue.BooleanValue(enabled)))
    }

    internal fun jsonOnOffAny(value: JsonPrimitive?): String? {
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

    internal fun jsonOnOffToggle(value: JsonPrimitive?): Int? {
        value ?: return null
        value.intOrNull?.let { return it }
        return when (value.contentOrNull?.substringAfterLast('_')?.lowercase()) {
            "on" -> 0
            "off" -> 1
            "toggle" -> 2
            else -> null
        }
    }

    internal fun jsonRingerMode(value: JsonPrimitive?): Int? {
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

    internal fun jsonRotation(value: JsonPrimitive?): Int? {
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

    internal fun protoNumber(fields: ProtoFields, stringField: Int, deprecatedField: Int): Double? {
        fields.string(stringField)?.let { return it.toDoubleOrNull()?.takeIf(Double::isFinite) }
        return (fields.varint(deprecatedField) ?: 0L).toDouble()
    }

    internal fun jsonNumeric(obj: JsonObject, preferred: String, deprecated: String): Double? {
        val preferredValue = (obj[preferred] as? JsonPrimitive)?.contentOrNull
        if (!preferredValue.isNullOrBlank()) return preferredValue.toDoubleOrNull()?.takeIf(Double::isFinite)
        return (obj[deprecated] as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite) ?: 0.0
    }

    internal fun withFactTag(feature: FeatureRef, tag: String?): FeatureRef {
        val value = tag?.trim().orEmpty()
        if (value.isBlank()) return feature
        return feature.copy(config = feature.config + ("tag" to ConfigValue.StringValue(value)))
    }

    internal fun jsonFactTag(obj: JsonObject): String? =
        (obj["tag"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)

    internal fun binaryFeature(any: AnyStub, importerId: String, target: String, extra: Map<String, ConfigValue>) =
        sourceFeature(
            targetTypeId = target,
            importerId = importerId,
            sourceType = any.typeUrl,
            raw = Base64.getEncoder().encodeToString(any.value),
            extra = extra,
        )

    internal fun durationMs(value: Double, unit: Long): Double? {
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

    internal fun jsonTimeUnit(value: JsonPrimitive?): Long? {
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

    internal fun shortXKeyGesture(value: Int): String? = when (value) {
        0 -> "single_press"
        1 -> "double_press"
        2 -> "triple_press"
        3 -> "long_press"
        else -> null
    }

    internal fun shortName(typeUrl: String): String = typeUrl
        .substringAfterLast('/')
        .substringAfterLast('.')
        .substringAfterLast('$')
}
