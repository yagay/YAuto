package com.yagay.yauto.feature.standard.json

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FieldBehavior
import com.yagay.yauto.core.registry.FieldSchema
import kotlinx.serialization.json.Json

internal val YAutoJson: Json = Json {
    prettyPrint = false
    isLenient = true
    ignoreUnknownKeys = true
}

internal fun jsonDescriptor(
    id: String,
    title: String,
    description: String,
    fields: List<FieldSchema>,
    keywords: Set<String>,
    behaviors: Map<String, FieldBehavior> = emptyMap(),
): FeatureDescriptor = FeatureDescriptor(
    id = FeatureId(id),
    kind = FeatureKind.ACTION,
    title = title,
    description = description,
    category = FeatureCategory.VARIABLE,
    fields = fields,
    keywords = keywords,
    fieldBehaviors = behaviors,
)

internal fun jsonFailure(error: Throwable): ActionExecutionResult =
    ActionExecutionResult(false, message = userText("feature.operation_failed", error.message ?: error.javaClass.simpleName))

internal fun invalidJsonPath(): ActionExecutionResult =
    ActionExecutionResult(false, message = userText("feature.json_path_invalid"))

internal fun parseConfiguredJsonValue(raw: String, type: String): ConfigValue? = when (type) {
    "string" -> ConfigValue.StringValue(raw)
    "number" -> raw.toDoubleOrNull()?.let(ConfigValue::NumberValue)
    "boolean" -> when (raw.trim().lowercase()) {
        "true" -> ConfigValue.BooleanValue(true)
        "false" -> ConfigValue.BooleanValue(false)
        else -> null
    }
    "null" -> ConfigValue.NullValue
    "json" -> runCatching { YAutoJson.parseToJsonElement(raw).toConfigValue() }.getOrNull()
    else -> null
}
