package com.yagay.yauto.feature.standard.data

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.FeatureDefinition
import com.yagay.yauto.core.registry.FieldBehavior
import com.yagay.yauto.core.registry.FieldSchema
import com.yagay.yauto.core.registry.actionFeature

internal object DataListFeatures {
    val definitions: List<FeatureDefinition> = listOf(
        listFeature(
            id = "data.list.sort",
            title = "Sort list",
            description = "Sort a list as text or numeric values",
            extras = listOf(
                FieldSchema.Choice("type", "Sort as", true, listOf("text", "number")),
                FieldSchema.Choice("order", "Sort order", true, listOf("ascending", "descending")),
            ),
            behaviors = mapOf(
                "type" to FieldBehavior(defaultValue = ConfigValue.StringValue("text")),
                "order" to FieldBehavior(defaultValue = ConfigValue.StringValue("ascending")),
            ),
        ) { feature, values ->
            val numeric = feature.config.string("type", "text") == "number"
            val descending = feature.config.string("order", "ascending") == "descending"
            val sorted = if (numeric) {
                values.sortedBy { it.asDataText().toDoubleOrNull() ?: Double.POSITIVE_INFINITY }
            } else {
                values.sortedBy { it.asDataText().lowercase() }
            }
            if (descending) sorted.reversed() else sorted
        },
        listFeature(
            id = "data.list.reverse",
            title = "Reverse list",
            description = "Reverse the order of items in a list",
        ) { _, values -> values.reversed() },
        listFeature(
            id = "data.list.distinct",
            title = "Remove duplicate list items",
            description = "Keep only the first occurrence of each list value",
        ) { _, values ->
            val seen = linkedSetOf<String>()
            values.filter { seen.add(it.asDataText()) }
        },
    )

    private fun listFeature(
        id: String,
        title: String,
        description: String,
        extras: List<FieldSchema> = emptyList(),
        behaviors: Map<String, FieldBehavior> = emptyMap(),
        transform: (com.yagay.yauto.core.model.FeatureRef, List<ConfigValue>) -> List<ConfigValue>,
    ): FeatureDefinition = actionFeature(
        dataDescriptor(
            id,
            title,
            description,
            fields = listOf(FieldSchema.Variable("name", "List variable", true)) +
                extras +
                FieldSchema.Variable("resultVariable", "Store list in variable", true),
            keywords = setOf("list", "sort", "reverse", "distinct", "data"),
            behaviors = behaviors,
        )
    ) { feature, context ->
        val values = (context.variables.get(feature.config.string("name")) as? ConfigValue.ListValue)?.value
            ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.variable_not_list"))
        context.storeValue(feature.destination(), ConfigValue.ListValue(transform(feature, values)))
    }
}
