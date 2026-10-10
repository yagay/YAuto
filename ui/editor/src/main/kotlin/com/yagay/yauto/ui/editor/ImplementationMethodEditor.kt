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
import com.yagay.yauto.core.registry.FeatureMethod
import com.yagay.yauto.core.registry.FeatureImplementationOption
import com.yagay.yauto.core.registry.methodBackends
import com.yagay.yauto.ui.design.localizedList
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
                "no_root" to TextR.string.implementation_group_no_root,
                "root_required" to TextR.string.implementation_group_root_required,
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
    method: FeatureMethod,
    enabled: Boolean,
    onValue: (String) -> Unit,
) {
    val options = if (descriptor.hasDualMethodRoutes()) descriptor.methodBackends(method)
        else descriptor.resolvedImplementationOptions()
    val selected = value.takeIf { candidate -> options.any { it.backendId == candidate } } ?: "auto"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(
                if (descriptor.hasDualMethodRoutes())
                    TextR.string.implementation_backend_in_group
                else TextR.string.implementation_method,
            ),
            fontWeight = FontWeight.Medium,
        )
        (listOf("auto") + options.mapNotNull { it.backendId }).forEach { option ->
            Row(
                Modifier.fillMaxWidth().clickable(enabled = enabled) { onValue(option) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == option, onClick = { onValue(option) }, enabled = enabled)
                Column {
                    Text(implementationTitle(option))
                    options.firstOrNull { it.backendId == option }?.let {
                        ImplementationPermissionLine(it)
                    }
                }
            }
        }
    }
}


@Composable
internal fun ImplementationPermissionLine(option: FeatureImplementationOption) {
    val requirements = option.requirements.map { accessRequirementLabelNonComposable(it) }
    val label = when {
        option.backendId == "shizuku" -> stringResource(TextR.string.implementation_shizuku_setup)
        requirements.isEmpty() -> stringResource(TextR.string.implementation_no_special_access)
        else -> stringResource(TextR.string.implementation_requirements_format, localizedList(requirements))
    }
    Text(label, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
    if (option.restartRequired) {
        Text(stringResource(TextR.string.implementation_restart_note),
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun implementationTitle(backendId: String): String = stringResource(
    when (backendId) {
        "auto" -> TextR.string.implementation_auto_title
        "android" -> TextR.string.implementation_android_title
        "root" -> TextR.string.implementation_root_title
        "shizuku" -> TextR.string.implementation_shizuku_title
        "lsposed" -> TextR.string.implementation_lsposed_title
        "accessibility" -> TextR.string.implementation_accessibility_title
        "usage_stats" -> TextR.string.implementation_usage_stats_title
        else -> TextR.string.implementation_method
    }
)


