package com.yagay.yauto.importer.shortx

import com.yagay.yauto.core.importer.sourceFeature
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import java.util.Base64
import kotlinx.serialization.json.*


/** Strict native ShortX JSON payload conversion; preserves source raw data. */
internal fun ShortXMappings.nativeJsonAction(any: AnyStub, importerId: String): FeatureRef? {
        val obj = Json.parseToJsonElement(any.value.toString(Charsets.UTF_8)) as? JsonObject ?: return null
        if (obj["customContextDataKey"] != null && obj["customContextDataKey"] !is JsonNull) return null
        val raw = any.value.toString(Charsets.UTF_8)
        if (ShortXVerifiedBatchMappings.ownsSource(shortName(any.typeUrl))) {
            return ShortXVerifiedBatchMappings.json(any, importerId, obj)
        }
        ShortXVerifiedBatchMappings.json(any, importerId, obj)?.let { return it }
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
            "ShowRecentApps" -> {
                // ShortX uses OnOffToggle: 0=open, 1=close, 2=toggle.
                // An Accessibility RECENTS action only opens the overview.
                // Unsupported modes must remain source-preserving compatibility actions.
                if (!jsonBusinessKeysOnly(obj, "state")) null
                else {
                    val state = (obj["state"] as? JsonPrimitive)?.let { value ->
                        value.intOrNull ?: when (value.contentOrNull?.substringAfterLast('_')?.lowercase()) {
                            "on" -> 0
                            "off" -> 1
                            "toggle" -> 2
                            else -> null
                        }
                    } ?: 0
                    if (state == 0) sourceFeature("accessibility.recents.show", importerId, any.typeUrl, raw)
                    else null
                }
            }
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
                if (!jsonBusinessKeysOnly(obj, "enable")) return null
                val enable = (obj["enable"] as? JsonPrimitive)?.booleanOrNull
                    ?: if ("enable" in obj) return null else false
                sourceFeature("android.display.brightness.set", importerId, any.typeUrl, raw,
                    extra = mapOf("mode" to ConfigValue.StringValue(if (enable) "auto" else "manual_keep")))
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

internal fun ShortXMappings.nativeJsonCondition(any: AnyStub, importerId: String): FeatureRef? {
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

