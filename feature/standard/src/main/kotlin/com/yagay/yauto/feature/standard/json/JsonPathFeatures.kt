package com.yagay.yauto.feature.standard.json

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.FeatureDefinition
import com.yagay.yauto.core.registry.FieldBehavior
import com.yagay.yauto.core.registry.FieldSchema
import com.yagay.yauto.core.registry.actionFeature

internal object JsonPathFeatures {
    val definitions: List<FeatureDefinition> = listOf(
        actionFeature(
            jsonDescriptor(
                "data.json.path.get",
                "Read JSON path",
                "Read a value from an object/list variable using a path such as user.items[0].name",
                fields = listOf(
                    FieldSchema.Variable("name", "Source variable", true),
                    FieldSchema.Text("path", "JSON path", true),
                    FieldSchema.Variable("resultVariable", "Store result in variable", true),
                ),
                keywords = setOf("json", "path", "get", "object", "array"),
                behaviors = mapOf("path" to FieldBehavior(supportsVariables = true)),
            )
        ) { feature, context ->
            val sourceName = feature.config.string("name")
            val source = context.variables.get(sourceName)
                ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.variable_missing", sourceName))
            val path = parseJsonPath(feature.config.string("path").resolveVariables(context.variables))
                ?: return@actionFeature invalidJsonPath()
            val output = getAtPath(source, path)
                ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.json_path_not_found"))
            context.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        },
        actionFeature(
            jsonDescriptor(
                "data.json.path.set",
                "Write JSON path",
                "Set or replace a value inside an object/list variable using a JSON path",
                fields = listOf(
                    FieldSchema.Variable("name", "Target variable", true),
                    FieldSchema.Text("path", "JSON path", true),
                    FieldSchema.Text("value", "Value", multiline = true),
                    FieldSchema.Choice(
                        "valueType",
                        "Value type",
                        true,
                        listOf("string", "number", "boolean", "null", "json"),
                    ),
                ),
                keywords = setOf("json", "path", "set", "object", "array"),
                behaviors = mapOf(
                    "path" to FieldBehavior(supportsVariables = true),
                    "value" to FieldBehavior(supportsVariables = true),
                    "valueType" to FieldBehavior(defaultValue = ConfigValue.StringValue("string")),
                ),
            )
        ) { feature, context ->
            val name = feature.config.string("name")
            val source = context.variables.get(name)
                ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.variable_missing", name))
            val path = parseJsonPath(feature.config.string("path").resolveVariables(context.variables))
                ?: return@actionFeature invalidJsonPath()
            if (path.isEmpty()) return@actionFeature invalidJsonPath()
            val value = parseConfiguredJsonValue(
                feature.config.string("value").resolveVariables(context.variables),
                feature.config.string("valueType", "string"),
            ) ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.json_value_invalid"))
            val updated = setAtPath(source, path, value)
                ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.json_path_not_found"))
            context.variables.set(name, updated)
            ActionExecutionResult(true, updated)
        },
        actionFeature(
            jsonDescriptor(
                "data.json.path.remove",
                "Remove JSON path",
                "Remove an object key or list item from a structured variable",
                fields = listOf(
                    FieldSchema.Variable("name", "Target variable", true),
                    FieldSchema.Text("path", "JSON path", true),
                ),
                keywords = setOf("json", "path", "remove", "delete"),
                behaviors = mapOf("path" to FieldBehavior(supportsVariables = true)),
            )
        ) { feature, context ->
            val name = feature.config.string("name")
            val source = context.variables.get(name)
                ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.variable_missing", name))
            val path = parseJsonPath(feature.config.string("path").resolveVariables(context.variables))
                ?: return@actionFeature invalidJsonPath()
            if (path.isEmpty()) return@actionFeature invalidJsonPath()
            val updated = removeAtPath(source, path)
                ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.json_path_not_found"))
            context.variables.set(name, updated)
            ActionExecutionResult(true, updated)
        },
    )
}
