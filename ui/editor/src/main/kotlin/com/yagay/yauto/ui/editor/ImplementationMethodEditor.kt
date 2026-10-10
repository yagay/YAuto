package com.yagay.yauto.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FieldSchema
import com.yagay.yauto.core.registry.hasDualMethodRoutes
import com.yagay.yauto.core.registry.resolvedImplementationOptions
import com.yagay.yauto.ui.design.R as TextR

/** Shared implementation selectors for the generic feature editor. */
@Composable
internal fun MethodChoiceEditor(
    selected: String,
    enabled: Boolean,
    onValue: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(TextR.string.implementation_method), fontWeight = FontWeight.Medium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "auto" to TextR.string.implementation_auto_title,
                "macrodroid" to TextR.string.implementation_family_macro_title,
                "shortx" to TextR.string.implementation_family_shortx_title,
            ).forEach { (value, label) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onValue(value) },
                    enabled = enabled,
                    label = { Text(stringResource(label)) },
                )
            }
        }
    }
}

@Composable
internal fun BackendChoiceEditor(
    descriptor: FeatureDescriptor,
    field: FieldSchema.Choice,
    value: String,
    enabled: Boolean,
    onValue: (String) -> Unit,
) {
    val selected = value.ifBlank { "auto" }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(
                if (descriptor.hasDualMethodRoutes())
                    TextR.string.implementation_family_shortx_backend
                else TextR.string.implementation_method,
            ),
            fontWeight = FontWeight.Medium,
        )
        field.options.forEach { option ->
            val supported = option == "auto" || descriptor.resolvedImplementationOptions().any { it.backendId == option }
            if (supported) {
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = enabled) { onValue(option) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected == option, onClick = { onValue(option) }, enabled = enabled)
                    Text(implementationTitle(option))
                }
            }
        }
    }
}


@Composable
internal fun implementationTitle(backendId: String): String = stringResource(
    when (backendId) {
        "auto" -> TextR.string.implementation_auto_title
        "root" -> TextR.string.implementation_root_title
        "shizuku" -> TextR.string.implementation_shizuku_title
        "lsposed" -> TextR.string.implementation_lsposed_title
        "accessibility" -> TextR.string.implementation_accessibility_title
        "usage_stats" -> TextR.string.implementation_usage_stats_title
        else -> TextR.string.implementation_method
    }
)


