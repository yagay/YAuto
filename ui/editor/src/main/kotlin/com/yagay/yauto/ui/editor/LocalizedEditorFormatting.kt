package com.yagay.yauto.ui.editor

import android.icu.text.ListFormatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.ui.design.R as TextR
import java.text.Collator
import java.text.NumberFormat
import java.text.ParsePosition
import java.util.Locale

@Composable
internal fun currentEditorLocale(): Locale {
    val context = LocalContext.current
    return context.resources.configuration.locales[0] ?: Locale.getDefault()
}

internal fun localizedStringComparator(locale: Locale): Comparator<String> {
    val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
    return Comparator { left, right -> collator.compare(left, right) }
}

@Composable
internal fun localizedConfigValue(value: ConfigValue): String {
    val locale = currentEditorLocale()
    val trueLabel = stringResource(TextR.string.value_true)
    val falseLabel = stringResource(TextR.string.value_false)
    return localizedConfigValue(value, locale, trueLabel, falseLabel)
}

internal fun editorConfigValueText(value: ConfigValue?, locale: Locale): String = when (value) {
    null, ConfigValue.NullValue -> ""
    is ConfigValue.StringValue -> value.value
    is ConfigValue.NumberValue -> localizedNumber(value.value, locale)
    is ConfigValue.BooleanValue -> value.value.toString()
    is ConfigValue.ListValue -> value.value.joinToString(",") { editorConfigValueText(it, locale) }
    is ConfigValue.ObjectValue -> value.value.entries.joinToString(",") { (key, item) ->
        "$key=${editorConfigValueText(item, locale)}"
    }
}

internal fun parseLocalizedDouble(raw: String, locale: Locale): Double? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val position = ParsePosition(0)
    val parsed = NumberFormat.getNumberInstance(locale).parse(text, position)
    if (parsed != null && position.index == text.length) return parsed.toDouble()
    // Imported/canonical configurations use a dot decimal separator regardless of UI locale.
    return text.toDoubleOrNull()
}

private fun localizedConfigValue(
    value: ConfigValue,
    locale: Locale,
    trueLabel: String,
    falseLabel: String,
): String = when (value) {
    ConfigValue.NullValue -> ""
    is ConfigValue.StringValue -> value.value
    is ConfigValue.NumberValue -> localizedNumber(value.value, locale)
    is ConfigValue.BooleanValue -> if (value.value) trueLabel else falseLabel
    is ConfigValue.ListValue -> {
        val items = value.value.map { localizedConfigValue(it, locale, trueLabel, falseLabel) }
        when (items.size) {
            0 -> ""
            1 -> items.single()
            else -> ListFormatter.getInstance(locale).format(*items.toTypedArray())
        }
    }
    is ConfigValue.ObjectValue -> {
        val items = value.value.entries.map { (key, item) ->
            "$key=${localizedConfigValue(item, locale, trueLabel, falseLabel)}"
        }
        when (items.size) {
            0 -> ""
            1 -> items.single()
            else -> ListFormatter.getInstance(locale).format(*items.toTypedArray())
        }
    }
}

private fun localizedNumber(value: Double, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).apply {
        isGroupingUsed = false
        maximumFractionDigits = 15
    }.format(value)
