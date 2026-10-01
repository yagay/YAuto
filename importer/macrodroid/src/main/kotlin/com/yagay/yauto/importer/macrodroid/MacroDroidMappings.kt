package com.yagay.yauto.importer.macrodroid

import com.yagay.yauto.core.importer.SourceFeatureKind
import com.yagay.yauto.core.importer.SourceFeatureMapper
import com.yagay.yauto.core.importer.sourceFeature
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

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
}
