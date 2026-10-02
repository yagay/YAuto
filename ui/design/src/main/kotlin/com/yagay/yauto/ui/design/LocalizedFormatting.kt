package com.yagay.yauto.ui.design

import android.icu.text.ListFormatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Locale-aware list formatting for visible UI copy. */
@Composable
fun localizedList(values: Iterable<String>): String {
    val items = values.toList()
    val locale = currentAppLocale()
    return when (items.size) {
        0 -> ""
        1 -> items.single()
        else -> ListFormatter.getInstance(locale).format(*items.toTypedArray())
    }
}

/** Locale-aware date/time formatting for visible diagnostic copy. */
@Composable
fun localizedDateTime(epochMillis: Long): String {
    val locale = currentAppLocale()
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, locale)
        .format(Date(epochMillis))
}

@Composable
private fun currentAppLocale(): Locale {
    val context = LocalContext.current
    return context.resources.configuration.locales[0] ?: Locale.getDefault()
}
