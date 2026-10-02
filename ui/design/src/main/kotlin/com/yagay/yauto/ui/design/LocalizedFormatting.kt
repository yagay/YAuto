package com.yagay.yauto.ui.design

/** Locale-aware list formatting for visible UI copy. */
fun localizedList(values: Iterable<String>): String {
    val items = values.toList()
    return when (items.size) {
        0 -> ""
        1 -> items.single()
        else -> android.icu.text.ListFormatter.getInstance().format(*items.toTypedArray())
    }
}
