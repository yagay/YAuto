package com.yagay.yauto.importer.macrodroid

import com.yagay.yauto.core.importer.SourceFeatureKind
import com.yagay.yauto.core.importer.SourceFeatureMapper
import com.yagay.yauto.core.importer.sourceFeature
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * MacroDroid mappings are intentionally conservative. A source item is converted to a native
 * YAuto feature only when both the class type and all behaviorally relevant fields are understood.
 * Otherwise the importer keeps the complete source payload as a compatibility node.
 */
object MacroDroidMappings {
    val mapper = SourceFeatureMapper { sourceType, kind ->
        when (kind) {
            SourceFeatureKind.ACTION -> when (sourceType) {
                "PauseAction" -> "core.delay"
                "ToastAction" -> "android.toast.show"
                "LaunchActivityAction" -> "android.app.launch"
                "SetVariableAction" -> "variable.set"
                "SetClipboardAction" -> "android.clipboard.set"
                "OpenWebPageAction" -> "android.uri.open"
                "SetVolumeAction" -> "android.audio.media_volume.set"
                "SetBrightnessAction" -> "android.display.brightness.set"
                "SendIntentAction" -> "android.intent.send"
                "NotificationAction" -> "android.notification.show"
                "CarModeAction" -> "android.car_mode.set"
                "DayDreamAction" -> "android.display.dream.start"
                "InvertColoursAction" -> "android.display.color_inversion.set"
                "HeadsUpNotificationsAction" -> "android.notification.heads_up.set"
                "AmbientDisplayAction" -> "android.display.ambient_display.set"
                "ExpandCollapseStatusBarAction" -> "android.status_bar.control"
                "ConnectivityCheckAction" -> "android.network.connectivity.check"
                "OpenCallLogAction" -> "android.call_log.open"
                "SetRingtoneAction", "SetNotificationSoundAction" -> "android.audio.default_sound.set"
                else -> null
            }
            SourceFeatureKind.EVENT -> when (sourceType) {
                "ScreenOnOffTrigger" -> "android.event.screen_on"
                "DeviceBootTrigger" -> "android.event.boot"
                "ScreenUnlockedTrigger" -> "android.event.user_present"
                "NotificationTrigger" -> "android.event.notification_posted"
                else -> null
            }
            SourceFeatureKind.CONDITION -> when (sourceType) {
                "ScreenOnConstraint" -> "android.condition.screen"
                "VolumeLevelConstraint" -> "android.condition.media_volume"
                "BrightnessConstraint" -> "android.condition.brightness"
                "DataOnOffConstraint" -> "android.condition.mobile_data_enabled"
                "IsRoamingConstraint" -> "android.condition.network_roaming"
                "RoamingOnOffConstraint" -> "android.condition.data_roaming_setting"
                "SignalOnOffConstraint" -> "android.condition.cellular_service_available"
                else -> null
            }
            else -> null
        }
    }

    fun nativeAction(
        obj: JsonObject,
        importerId: String,
        sourceType: String,
        raw: String,
    ): FeatureRef? = when (sourceType) {
        "PauseAction" -> pause(obj, importerId, sourceType, raw)
        "ToastAction" -> toast(obj, importerId, sourceType, raw)
        "LaunchActivityAction" -> launchApp(obj, importerId, sourceType, raw)
        "SetVariableAction" -> setVariable(obj, importerId, sourceType, raw)
        "SetClipboardAction" -> obj.string("m_text", "text")?.let { sourceFeature("android.clipboard.set", importerId, sourceType, raw, extra = mapOf("text" to ConfigValue.StringValue(it))) }
        "OpenWebPageAction" -> obj.string("m_urlToOpen", "urlToOpen")?.takeIf { it.isNotBlank() }?.let { sourceFeature("android.uri.open", importerId, sourceType, raw, extra = mapOf("uri" to ConfigValue.StringValue(it))) }
        "SetVolumeAction" -> setVolume(obj, importerId, sourceType, raw)
        "SetBrightnessAction" -> setBrightness(obj, importerId, sourceType, raw)
        "SendIntentAction" -> sendIntent(obj, importerId, sourceType, raw)
        "NotificationAction" -> notification(obj, importerId, sourceType, raw)
        "CarModeAction" -> modeAction(obj, "m_option", "android.car_mode.set", importerId, sourceType, raw)
        "DayDreamAction" -> sourceFeature("android.display.dream.start", importerId, sourceType, raw)
        "InvertColoursAction" -> modeAction(obj, "m_option", "android.display.color_inversion.set", importerId, sourceType, raw)
        "HeadsUpNotificationsAction" -> modeAction(obj, "option", "android.notification.heads_up.set", importerId, sourceType, raw)
        "AmbientDisplayAction" -> ambientDisplay(obj, importerId, sourceType, raw)
        "ExpandCollapseStatusBarAction" -> statusBar(obj, importerId, sourceType, raw)
        "ConnectivityCheckAction" -> connectivityCheck(obj, importerId, sourceType, raw)
        "OpenCallLogAction" -> sourceFeature("android.call_log.open", importerId, sourceType, raw)
        "SetRingtoneAction" -> defaultSound(obj, "ringtone", importerId, sourceType, raw)
        "SetNotificationSoundAction" -> defaultSound(obj, "notification", importerId, sourceType, raw)
        else -> null
    }

    fun nativeContext(obj: JsonObject, kind: SourceFeatureKind, importerId: String, sourceType: String, raw: String): FeatureRef? =
        when (kind) {
            SourceFeatureKind.EVENT -> when (sourceType) {
                "ScreenOnOffTrigger" -> obj.bool("m_screenOn", "screenOn")?.let {
                    sourceFeature(if (it) "android.event.screen_on" else "android.event.screen_off", importerId, sourceType, raw)
                }
                "DeviceBootTrigger" -> sourceFeature("android.event.boot", importerId, sourceType, raw)
                "ScreenUnlockedTrigger" -> sourceFeature("android.event.user_present", importerId, sourceType, raw)
                "NotificationTrigger" -> notificationTrigger(obj, importerId, sourceType, raw)
                else -> null
            }
            SourceFeatureKind.CONDITION -> when (sourceType) {
                "ScreenOnConstraint" -> obj.bool("m_screenOn", "screenOn")?.let {
                    sourceFeature("android.condition.screen", importerId, sourceType, raw,
                        extra = mapOf("value" to ConfigValue.BooleanValue(it)))
                }
                "VolumeLevelConstraint" -> volumeConstraint(obj, importerId, sourceType, raw)
                "BrightnessConstraint" -> brightnessConstraint(obj, importerId, sourceType, raw)
                "DataOnOffConstraint" -> booleanConstraint(obj, "m_dataOn", "android.condition.mobile_data_enabled", importerId, sourceType, raw)
                "IsRoamingConstraint" -> booleanConstraint(obj, "m_isRoaming", "android.condition.network_roaming", importerId, sourceType, raw)
                "RoamingOnOffConstraint" -> booleanConstraint(obj, "m_roamingOn", "android.condition.data_roaming_setting", importerId, sourceType, raw)
                "SignalOnOffConstraint" -> signalConstraint(obj, importerId, sourceType, raw)
                else -> null
            }
            else -> null
        }

    private fun modeAction(
        obj: JsonObject,
        optionKey: String,
        target: String,
        importerId: String,
        sourceType: String,
        raw: String,
    ): FeatureRef? {
        val mode = when (obj.number(optionKey)?.toInt()) {
            0 -> "enable"
            1 -> "disable"
            2 -> "toggle"
            else -> return null
        }
        return sourceFeature(
            target,
            importerId,
            sourceType,
            raw,
            extra = mapOf("mode" to ConfigValue.StringValue(mode)),
        )
    }

    private fun ambientDisplay(
        obj: JsonObject,
        importerId: String,
        sourceType: String,
        raw: String,
    ): FeatureRef? {
        val setting = when (obj.number("m_settingOption")?.toInt()) {
            0 -> "wake_for_notifications"
            1 -> "always_on"
            else -> return null
        }
        val mode = when (obj.number("m_option")?.toInt()) {
            0 -> "enable"
            1 -> "disable"
            2 -> "toggle"
            else -> return null
        }
        return sourceFeature(
            "android.display.ambient_display.set",
            importerId,
            sourceType,
            raw,
            extra = mapOf(
                "setting" to ConfigValue.StringValue(setting),
                "mode" to ConfigValue.StringValue(mode),
            ),
        )
    }

    private fun statusBar(
        obj: JsonObject,
        importerId: String,
        sourceType: String,
        raw: String,
    ): FeatureRef? {
        val mode = when (obj.number("m_option")?.toInt()) {
            0 -> "notifications"
            1 -> "collapse"
            else -> return null
        }
        return sourceFeature(
            "android.status_bar.control",
            importerId,
            sourceType,
            raw,
            extra = mapOf("mode" to ConfigValue.StringValue(mode)),
        )
    }

    private fun connectivityCheck(
        obj: JsonObject,
        importerId: String,
        sourceType: String,
        raw: String,
    ): FeatureRef? {
        val site = obj.string("site")?.takeIf { it.isNotBlank() } ?: return null
        val timeout = (obj.number("timeout") ?: 3_000.0).takeIf { it in 250.0..60_000.0 } ?: return null
        val variableName = obj.objectValue("variable", "m_variable")
            ?.string("m_name", "name")
            ?: obj.string("variableName", "m_variableName")
            ?: return null
        if (variableName.isBlank()) return null
        return sourceFeature(
            "android.network.connectivity.check",
            importerId,
            sourceType,
            raw,
            extra = mapOf(
                "site" to ConfigValue.StringValue(site),
                "timeoutMs" to ConfigValue.NumberValue(timeout),
                "resultVariable" to ConfigValue.StringValue(variableName),
            ),
        )
    }

    private fun defaultSound(
        obj: JsonObject,
        type: String,
        importerId: String,
        sourceType: String,
        raw: String,
    ): FeatureRef? {
        val uri = obj.string("m_ringtoneUri")?.takeIf { it.isNotBlank() } ?: return null
        return sourceFeature(
            "android.audio.default_sound.set",
            importerId,
            sourceType,
            raw,
            extra = mapOf(
                "type" to ConfigValue.StringValue(type),
                "uri" to ConfigValue.StringValue(uri),
                "silent" to ConfigValue.BooleanValue(false),
            ),
        )
    }

    private fun notificationTrigger(obj: JsonObject, importerId: String, sourceType: String, raw: String): FeatureRef? {
        if (obj.bool("m_excludeApps") == true || obj.bool("m_excludes") == true) return null
        if (obj.bool("enableRegex") == true || obj.bool("m_exactMatch") == true) return null
        if (obj.bool("m_supressMultiples") == true) return null
        if ((obj.number("m_soundOption") ?: 0.0) != 0.0) return null

        val packages = obj.array("m_packageNameList")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }.orEmpty()
        if (packages.size > 1) return null

        val separate = obj.bool("separateTitleAndMessage") == true
        val title = obj.string("titleContent").orEmpty()
        val message = obj.string("messageContent").orEmpty()
        val legacy = obj.string("m_textContent").orEmpty()
        if (!separate && (title.isNotBlank() || message.isNotBlank() || legacy.isNotBlank())) return null
        if ((title.isNotBlank() || message.isNotBlank()) && obj.bool("ignoreCase") == false) return null

        fun containsOrAny(content: String, optionKey: String): String? {
            if (content.isBlank()) return ""
            return when ((obj.number(optionKey) ?: 0.0).toInt()) {
                0 -> "" // Source ignores the content when match mode is Any.
                2 -> content
                else -> null
            }
        }
        val titleContains = if (separate) containsOrAny(title, "matchOptionTitle") ?: return null else ""
        val textContains = if (separate) containsOrAny(message, "matchOptionMessage") ?: return null else ""
        val eventId = when ((obj.number("m_option") ?: 0.0).toInt()) {
            0 -> "android.event.notification_posted"
            1 -> "android.event.notification_removed"
            else -> return null
        }
        return sourceFeature(
            eventId, importerId, sourceType, raw,
            extra = buildMap {
                packages.singleOrNull()?.let { put("package", ConfigValue.StringValue(it)) }
                if (titleContains.isNotBlank()) put("titleContains", ConfigValue.StringValue(titleContains))
                if (textContains.isNotBlank()) put("textContains", ConfigValue.StringValue(textContains))
                if (obj.bool("m_ignoreOngoing") == true) put("ongoing", ConfigValue.StringValue("exclude"))
            },
        )
    }

    private fun booleanConstraint(
        obj: JsonObject,
        key: String,
        target: String,
        importerId: String,
        sourceType: String,
        raw: String,
    ): FeatureRef? {
        val value = obj.bool(key) ?: return null
        return sourceFeature(
            target,
            importerId,
            sourceType,
            raw,
            extra = mapOf("value" to ConfigValue.BooleanValue(value)),
        )
    }

    private fun signalConstraint(
        obj: JsonObject,
        importerId: String,
        sourceType: String,
        raw: String,
    ): FeatureRef? {
        val available = when (obj.number("m_option")?.toInt()) {
            0 -> true
            1 -> false
            else -> return null
        }
        val subscriptionId = obj.number("subscriptionId")?.toInt() ?: -1
        if (subscriptionId < -1) return null
        return sourceFeature(
            "android.condition.cellular_service_available",
            importerId,
            sourceType,
            raw,
            extra = mapOf(
                "subscriptionId" to ConfigValue.NumberValue(subscriptionId.toDouble()),
                "value" to ConfigValue.BooleanValue(available),
            ),
        )
    }

    private fun volumeConstraint(obj: JsonObject, importerId: String, sourceType: String, raw: String): FeatureRef? {
        val selected = obj.array("m_streamIndexArray") ?: return null
        val enabledIndexes = selected.mapIndexedNotNull { index, value -> if (value.asBoolean() == true) index else null }
        if (enabledIndexes != listOf(1)) return null // Exact native reader currently covers Media/Music only.
        val value = obj.number("m_volume") ?: return null
        if (value !in 0.0..100.0) return null
        val operator = when ((obj.number("m_comparison") ?: 2.0).toInt()) {
            0 -> "<"
            1 -> ">"
            2 -> "=="
            else -> return null
        }
        return sourceFeature(
            "android.condition.media_volume", importerId, sourceType, raw,
            extra = mapOf("operator" to ConfigValue.StringValue(operator), "value" to ConfigValue.NumberValue(value)),
        )
    }

    private fun brightnessConstraint(obj: JsonObject, importerId: String, sourceType: String, raw: String): FeatureRef? {
        if (obj.bool("m_forcePieMode") == true) return null
        if (obj.bool("m_isAutoBrightness") == true) {
            return sourceFeature(
                "android.condition.brightness", importerId, sourceType, raw,
                extra = mapOf(
                    "mode" to ConfigValue.StringValue("auto"),
                    "compareLevel" to ConfigValue.BooleanValue(false),
                ),
            )
        }
        val value = obj.number("m_brightness") ?: return null
        if (value !in 0.0..100.0) return null
        val operator = when {
            obj.bool("m_equals") == true -> "=="
            obj.bool("m_greaterThan") == true -> ">"
            else -> "<"
        }
        return sourceFeature(
            "android.condition.brightness", importerId, sourceType, raw,
            extra = mapOf(
                "mode" to ConfigValue.StringValue("any"),
                "compareLevel" to ConfigValue.BooleanValue(true),
                "operator" to ConfigValue.StringValue(operator),
                "value" to ConfigValue.NumberValue(value),
            ),
        )
    }

    private fun pause(obj: JsonObject, importerId: String, sourceType: String, raw: String): FeatureRef {
        val seconds = obj.number("m_delayInSeconds", "delayInSeconds") ?: 0.0
        val millis = obj.number("m_delayInMilliSeconds", "delayInMilliSeconds") ?: 0.0
        val duration = (seconds * 1_000.0 + millis).coerceAtLeast(0.0)
        return sourceFeature(
            "core.delay", importerId, sourceType, raw,
            extra = mapOf("durationMs" to ConfigValue.NumberValue(duration)),
        )
    }

    private fun toast(obj: JsonObject, importerId: String, sourceType: String, raw: String): FeatureRef? {
        val text = obj.string("m_messageText", "messageText", "m_message", "message") ?: return null
        return sourceFeature(
            "android.toast.show", importerId, sourceType, raw,
            extra = mapOf("text" to ConfigValue.StringValue(text)),
        )
    }

    private fun launchApp(obj: JsonObject, importerId: String, sourceType: String, raw: String): FeatureRef? {
        val packageName = obj.string("m_packageToLaunch", "packageToLaunch", "m_packageName", "packageName")
            ?.takeIf { it.isNotBlank() } ?: return null
        return sourceFeature(
            "android.app.launch", importerId, sourceType, raw,
            extra = mapOf("package" to ConfigValue.StringValue(packageName)),
        )
    }

    private fun setVolume(obj: JsonObject, importerId: String, sourceType: String, raw: String): FeatureRef? {
        val selected = obj.array("m_streamIndexArray") ?: return null
        val volumes = obj.array("m_streamVolumeArray") ?: return null
        if (selected.size <= 1 || volumes.size <= 1) return null
        val enabledIndexes = selected.mapIndexedNotNull { index, value -> if (value.asBoolean() == true) index else null }
        if (enabledIndexes != listOf(1)) return null
        val variables = obj.array("m_variables")
        if (variables != null && variables.getOrNull(1)?.let { it !is JsonNull && it.toString() != "null" } == true) return null
        val percent = volumes.getOrNull(1).asNumber() ?: return null
        if (percent !in 0.0..100.0) return null
        return sourceFeature(
            "android.audio.media_volume.set", importerId, sourceType, raw,
            extra = mapOf(
                "percent" to ConfigValue.NumberValue(percent),
                "showUi" to ConfigValue.BooleanValue(obj.bool("setInForeground") == true),
            ),
        )
    }

    private fun setBrightness(obj: JsonObject, importerId: String, sourceType: String, raw: String): FeatureRef? {
        if (obj["m_variable"] != null && obj["m_variable"] !is JsonNull) return null
        if (obj.bool("forceValueEnabled") == true || obj.bool("m_forcePieMode") == true) return null
        val setAuto = obj.bool("setAutoBrightness") ?: true
        val autoOn = obj.bool("autoBrightnessOn") ?: false
        val setValue = obj.bool("setBrightnessValue") ?: true

        if (setAuto && autoOn && setValue) return null
        if (setAuto && autoOn && !setValue) {
            return sourceFeature(
                "android.display.brightness.set", importerId, sourceType, raw,
                extra = mapOf("mode" to ConfigValue.StringValue("auto")),
            )
        }
        if (!setValue) return null
        val percent = obj.number("m_brightnessPercent") ?: return null
        if (percent !in 0.0..100.0) return null
        return sourceFeature(
            "android.display.brightness.set", importerId, sourceType, raw,
            extra = mapOf(
                "mode" to ConfigValue.StringValue("manual"),
                "percent" to ConfigValue.NumberValue(percent),
            ),
        )
    }

    private fun sendIntent(obj: JsonObject, importerId: String, sourceType: String, raw: String): FeatureRef? {
        val target = when (obj.string("m_target")?.lowercase()) {
            "activity" -> "activity"
            "broadcast" -> "broadcast"
            "service" -> "service"
            else -> return null
        }
        for (i in 1..6) {
            val name = obj.string("m_extra${i}Name").orEmpty()
            if (name.isNotBlank()) {
                val type = obj.number("m_extra${i}Type")?.toInt() ?: 0
                if (type != 1 || i > 3) return null
            }
        }
        val category = if (obj.bool("useCustomCategory") == true) obj.string("customCategoryValue") else obj.string("m_category")
        return sourceFeature(
            "android.intent.send", importerId, sourceType, raw,
            extra = buildMap {
                put("target", ConfigValue.StringValue(target))
                obj.string("m_action")?.let { put("action", ConfigValue.StringValue(it)) }
                obj.string("m_packageName")?.let { put("package", ConfigValue.StringValue(it)) }
                obj.string("m_className")?.let { put("class", ConfigValue.StringValue(it)) }
                obj.string("m_data")?.let { put("data", ConfigValue.StringValue(it.trim())) }
                obj.string("m_mimeType")?.let { put("mimeType", ConfigValue.StringValue(it)) }
                category?.let { put("category", ConfigValue.StringValue(it)) }
                for (i in 1..3) {
                    obj.string("m_extra${i}Name")?.takeIf { it.isNotBlank() }?.let { name ->
                        put("extra${i}Key", ConfigValue.StringValue(name))
                        put("extra${i}Value", ConfigValue.StringValue(obj.string("m_extra${i}Value").orEmpty()))
                    }
                }
                obj.number("m_flags")?.let { put("flags", ConfigValue.NumberValue(it)) }
            },
        )
    }

    private fun notification(obj: JsonObject, importerId: String, sourceType: String, raw: String): FeatureRef? {
        if (obj.bool("m_runMacroWhenPressed") == true) return null
        if (!obj.string("configuredActionJson").isNullOrBlank()) return null
        if (obj.array("notificationActionButtons")?.isNotEmpty() == true) return null
        if ((obj.number("showAsOverlayOption") ?: 0.0) != 0.0) return null
        if (obj.bool("liveNotification") == true || obj.bool("displayOverStatusBar") == true) return null
        if (!obj.string("m_ringtoneName").isNullOrBlank()) return null
        if ((obj.number("m_priority") ?: 0.0) != 0.0) return null
        if ((obj.number("m_notificationChannelType") ?: 0.0) != 0.0) return null

        val title = obj.string("m_notificationSubject") ?: return null
        val text = obj.string("m_notificationText") ?: return null
        return sourceFeature(
            "android.notification.show", importerId, sourceType, raw,
            extra = buildMap {
                put("title", ConfigValue.StringValue(title))
                put("text", ConfigValue.StringValue(text))
                obj.number("notificatonId")?.let { put("id", ConfigValue.NumberValue(it)) }
                obj.string("notificationChannelName")?.takeIf { it.isNotBlank() }?.let {
                    put("channelName", ConfigValue.StringValue(it))
                }
                if (obj.bool("preventRemovalByBin") == true) put("ongoing", ConfigValue.BooleanValue(true))
            },
        )
    }

    private fun setVariable(obj: JsonObject, importerId: String, sourceType: String, raw: String): FeatureRef? {
        val variable = obj.objectValue("m_variable", "variable")
        val name = variable?.string("m_name", "name")
            ?: obj.string("m_variableName", "variableName")
            ?: return null
        if (name.isBlank()) return null

        val increment = obj.bool("m_increment", "increment") == true
        val decrement = obj.bool("m_decrement", "decrement") == true
        if (increment || decrement) {
            return sourceFeature(
                "variable.increment", importerId, sourceType, raw,
                extra = mapOf(
                    "name" to ConfigValue.StringValue(name),
                    "amount" to ConfigValue.NumberValue(if (decrement) -1.0 else 1.0),
                ),
            )
        }

        val value = obj.newVariableValue() ?: return null
        return sourceFeature(
            "variable.set", importerId, sourceType, raw,
            extra = mapOf(
                "name" to ConfigValue.StringValue(name),
                "value" to value,
            ),
        )
    }

    private fun JsonObject.newVariableValue(): ConfigValue? {
        string("m_newStringValue", "newStringValue")?.let { return ConfigValue.StringValue(it) }
        bool("m_newBooleanValue", "newBooleanValue")?.let { return ConfigValue.BooleanValue(it) }
        number("m_newIntValue", "newIntValue")?.let { return ConfigValue.NumberValue(it) }
        number("m_newDecimalValue", "newDecimalValue")?.let { return ConfigValue.NumberValue(it) }
        return null
    }

    private fun JsonObject.objectValue(vararg keys: String): JsonObject? =
        keys.firstNotNullOfOrNull { this[it] as? JsonObject }

    private fun JsonObject.array(vararg keys: String): JsonArray? =
        keys.firstNotNullOfOrNull { this[it] as? JsonArray }

    private fun JsonObject.string(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.contentOrNull }

    private fun JsonObject.number(vararg keys: String): Double? =
        keys.firstNotNullOfOrNull { key ->
            val p = this[key] as? JsonPrimitive
            p?.doubleOrNull ?: p?.contentOrNull?.toDoubleOrNull()
        }

    private fun JsonObject.bool(vararg keys: String): Boolean? =
        keys.firstNotNullOfOrNull { key ->
            val p = this[key] as? JsonPrimitive
            p?.booleanOrNull ?: p?.contentOrNull?.toBooleanStrictOrNull()
        }

    private fun kotlinx.serialization.json.JsonElement?.asBoolean(): Boolean? {
        val p = this as? JsonPrimitive ?: return null
        return p.booleanOrNull ?: p.contentOrNull?.let { value ->
            when (value.lowercase()) {
                "1", "true" -> true
                "0", "false" -> false
                else -> null
            }
        }
    }

    private fun kotlinx.serialization.json.JsonElement?.asNumber(): Double? {
        val p = this as? JsonPrimitive ?: return null
        return p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull()
    }
}
