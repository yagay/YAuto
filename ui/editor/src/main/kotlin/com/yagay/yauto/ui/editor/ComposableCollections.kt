package com.yagay.yauto.ui.editor

import androidx.compose.runtime.Composable
import com.yagay.yauto.core.registry.AccessRequirement

/** More-specific Compose overload used when localized requirement labels are joined for display. */
@Composable
internal fun Set<AccessRequirement>.joinToString(
    separator: CharSequence,
    transform: @Composable (AccessRequirement) -> CharSequence,
): String {
    val output = StringBuilder()
    var index = 0
    for (item in this) {
        if (index > 0) output.append(separator)
        output.append(transform(item))
        index++
    }
    return output.toString()
}
