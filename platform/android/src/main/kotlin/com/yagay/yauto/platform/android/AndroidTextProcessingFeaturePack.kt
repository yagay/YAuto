package com.yagay.yauto.platform.android

import android.icu.text.Transliterator
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

class AndroidTextProcessingFeaturePack : FeaturePack {
    override val id: String = "android.text_processing"

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.text.process"),
                FeatureKind.ACTION,
                "Process text",
                "Transform text with trim, length, whitespace, case, reverse or ICU Han-to-Latin transliteration",
                FeatureCategory.VARIABLE,
                fields = listOf(
                    FieldSchema.Text("text", "Input text", true, multiline = true),
                    FieldSchema.Choice(
                        "operation", "Operation", true,
                        listOf(
                            "trim", "trim_start", "trim_end", "trim_length",
                            "remove_spaces", "normalize_whitespace",
                            "upper", "lower", "capitalize", "reverse",
                            "pinyin", "latin_ascii"
                        )
                    ),
                    FieldSchema.Number("maxLength", "Maximum length", min = 0.0, max = 1_000_000.0),
                    FieldSchema.Variable("resultVariable", "Store result", true),
                ),
                fieldBehaviors = mapOf(
                    "text" to FieldBehavior(supportsVariables = true),
                    "maxLength" to FieldBehavior(
                        visibleWhen = FieldRule.Equals("operation", ConfigValue.StringValue("trim_length"))
                    ),
                ),
                keywords = setOf("text processing", "trim", "pinyin", "transliterate", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val input = feature.config.string("text").resolveVariables(ctx.variables)
            val result = runCatching {
                when (feature.config.string("operation", "trim")) {
                    "trim" -> input.trim()
                    "trim_start" -> input.trimStart()
                    "trim_end" -> input.trimEnd()
                    "trim_length" -> {
                        val max = (feature.config["maxLength"].numberOrNull() ?: 0.0)
                            .toInt().coerceAtLeast(0)
                        if (input.length <= max) input else input.take(max)
                    }
                    "remove_spaces" -> input.filterNot(Char::isWhitespace)
                    "normalize_whitespace" -> input.trim().replace(Regex("\\s+"), " ")
                    "upper" -> input.uppercase()
                    "lower" -> input.lowercase()
                    "capitalize" -> input.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    "reverse" -> input.reversed()
                    "pinyin" -> Transliterator.getInstance("Han-Latin; NFD; [:Nonspacing Mark:] Remove; NFC")
                        .transliterate(input)
                    "latin_ascii" -> Transliterator.getInstance("Any-Latin; Latin-ASCII")
                        .transliterate(input)
                    else -> input
                }
            }.getOrElse {
                return@registerAction ActionExecutionResult(
                    false,
                    message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName),
                )
            }
            val output = ConfigValue.StringValue(result)
            val variable = feature.config.string("resultVariable").trim()
            if (variable.isBlank()) return@registerAction ActionExecutionResult(false)
            ctx.variables.set(variable, output)
            ActionExecutionResult(true, output)
        }
    }
}
