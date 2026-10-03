package com.yagay.yauto.feature.standard.json

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.FeatureDefinition
import com.yagay.yauto.core.registry.FieldBehavior
import com.yagay.yauto.core.registry.FieldSchema
import com.yagay.yauto.core.registry.actionFeature
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.encodeToString

internal object JsonConversionFeatures {
    val definitions: List<FeatureDefinition> = listOf(
        actionFeature(
            jsonDescriptor(
                "data.json.parse",
                "Parse JSON",
                "Parse JSON text into a structured YAuto variable",
                fields = listOf(
                    FieldSchema.Text("text", "JSON text", true, multiline = true),
                    FieldSchema.Variable("resultVariable", "Store parsed value in variable", true),
                ),
                keywords = setOf("json", "parse", "object", "array"),
                behaviors = mapOf("text" to FieldBehavior(supportsVariables = true)),
            )
        ) { feature, context ->
            val raw = feature.config.string("text").resolveVariables(context.variables)
            val output = runCatching { YAutoJson.parseToJsonElement(raw).toConfigValue() }
                .getOrElse { return@actionFeature jsonFailure(it) }
            context.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        },
        actionFeature(
            jsonDescriptor(
                "data.json.stringify",
                "Convert value to JSON",
                "Serialize a YAuto variable as compact or pretty JSON text",
                fields = listOf(
                    FieldSchema.Variable("name", "Source variable", true),
                    FieldSchema.Toggle("pretty", "Pretty print"),
                    FieldSchema.Variable("resultVariable", "Store JSON text in variable", true),
                ),
                keywords = setOf("json", "stringify", "serialize", "format"),
                behaviors = mapOf("pretty" to FieldBehavior(defaultValue = ConfigValue.BooleanValue(false))),
            )
        ) { feature, context ->
            val sourceName = feature.config.string("name")
            val source = context.variables.get(sourceName)
                ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.variable_missing", sourceName))
            val writer = if (feature.config.boolean("pretty")) Json { prettyPrint = true } else YAutoJson
            val output = ConfigValue.StringValue(
                writer.encodeToString(JsonElement.serializer(), source.toJsonElement()),
            )
            context.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        },
    )
}
