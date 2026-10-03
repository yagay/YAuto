package com.yagay.yauto.feature.standard.json

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.FeatureDefinition
import com.yagay.yauto.core.registry.FieldBehavior
import com.yagay.yauto.core.registry.FieldSchema
import com.yagay.yauto.core.registry.actionFeature
import com.yagay.yauto.core.registry.resolveVariables

internal object JsonInspectFeatures {
    val definitions: List<FeatureDefinition> = listOf(
        actionFeature(
            jsonDescriptor(
                "data.json.keys",
                "Get object keys",
                "Store the keys of an object variable or JSON-path object as a list",
                fields = listOf(
                    FieldSchema.Variable("name", "Source variable", true),
                    FieldSchema.Text("path", "Optional JSON path"),
                    FieldSchema.Variable("resultVariable", "Store key list in variable", true),
                ),
                keywords = setOf("json", "keys", "object", "map"),
                behaviors = mapOf("path" to FieldBehavior(supportsVariables = true)),
            )
        ) { feature, context ->
            val sourceName = feature.config.string("name")
            val source = context.variables.get(sourceName)
                ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.variable_missing", sourceName))
            val pathText = feature.config.string("path").resolveVariables(context.variables).trim()
            val target = if (pathText.isBlank()) {
                source
            } else {
                val path = parseJsonPath(pathText) ?: return@actionFeature invalidJsonPath()
                getAtPath(source, path)
                    ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.json_path_not_found"))
            }
            val objectValue = target as? ConfigValue.ObjectValue
                ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.json_object_required"))
            val output = ConfigValue.ListValue(objectValue.value.keys.sorted().map(ConfigValue::StringValue))
            context.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        },
        actionFeature(
            jsonDescriptor(
                "data.json.array_length",
                "Get array length",
                "Read the size of a list variable or list at a JSON path",
                fields = listOf(
                    FieldSchema.Variable("name", "Source variable", true),
                    FieldSchema.Text("path", "Optional JSON path"),
                    FieldSchema.Variable("resultVariable", "Store length in variable", true),
                ),
                keywords = setOf("json", "array", "length", "size"),
                behaviors = mapOf("path" to FieldBehavior(supportsVariables = true)),
            )
        ) { feature, context ->
            val sourceName = feature.config.string("name")
            val source = context.variables.get(sourceName)
                ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.variable_missing", sourceName))
            val pathText = feature.config.string("path").resolveVariables(context.variables).trim()
            val target = if (pathText.isBlank()) {
                source
            } else {
                val path = parseJsonPath(pathText) ?: return@actionFeature invalidJsonPath()
                getAtPath(source, path)
                    ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.json_path_not_found"))
            }
            val listValue = target as? ConfigValue.ListValue
                ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.variable_not_list"))
            val output = ConfigValue.NumberValue(listValue.value.size.toDouble())
            context.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        },
    )
}
