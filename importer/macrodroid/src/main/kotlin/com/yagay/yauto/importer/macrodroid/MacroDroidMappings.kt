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
import kotlinx.serialization.json.intOrNull

/**
 * MacroDroid mappings are intentionally conservative. A source action is converted to a native
 * YAuto feature only when both the class type and the fields required by that native feature are
 * understood. Otherwise the importer keeps the complete source payload as a compatibility node.
 */
object MacroDroidMappings {
    val mapper = SourceFeatureMapper { sourceType, kind ->
        if (kind != SourceFeatureKind.ACTION) null
        else when (sourceType) {
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
        else -> null
    }

    fun nativeContext(obj: JsonObject, kind: SourceFeatureKind, importerId: String, sourceType: String, raw: String): FeatureRef? {
        val target = when (kind) {
            SourceFeatureKind.EVENT -> when (sourceType) {
                "ScreenOnOffTrigger" -> obj.bool("m_screenOn", "screenOn")?.let { if (it) "android.event.screen_on" else "android.event.screen_off" }
                "DeviceBootTrigger" -> "android.event.boot"
                "ScreenUnlockedTrigger" -> "android.event.user_present"
                else -> null
            }
            SourceFeatureKind.CONDITION -> if (sourceType == "ScreenOnConstraint" && obj.bool("m_screenOn", "screenOn") != null) "android.condition.screen" else null
            else -> null
        } ?: return null
        return sourceFeature(target, importerId, sourceType, raw, extra =
            if (kind == SourceFeatureKind.CONDITION) mapOf("value" to ConfigValue.BooleanValue(obj.bool("m_screenOn", "screenOn")!!)) else emptyMap())
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
        // MacroDroid schema order: Alarm, Music, Notification, Ringer, System, VoiceCall,
        // BluetoothVoice, Accessibility. YAuto currently has an exact native target for Music only.
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

        if (setAuto && autoOn && setValue) return null // Source performs two independent operations.
        if (setAuto && autoOn && !setValue) {
            return sourceFeature(
                "android.display.brightness.set", importerId, sourceType, raw,
                extra = mapOf("mode" to ConfigValue.StringValue("auto")),
            )
        }
        if (!setValue) return null // Merely switching auto off does not specify a brightness value.
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
        // Current YAuto intent editor stores simple string extras. Convert only explicitly-string
        // MacroDroid extras; auto-detected/numeric/array extras remain compatibility nodes.
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
        // Preserve interactive/overlay/special-channel variants until YAuto has equivalent UI.
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
