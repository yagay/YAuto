package com.yagay.yauto.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.registry.*
import com.yagay.yauto.ui.design.R as TextR
import com.yagay.yauto.ui.design.localizedList

/** Permission guidance only; the implementation strategy is automatic. */
@Composable
internal fun AutoImplementationSummary(descriptor: FeatureDescriptor, initial: FeatureRef?) {
    val options = descriptor.resolvedImplementationOptions().sortedWith(
        compareBy<FeatureImplementationOption> { it.requiresRootOrLsposed() }
            .thenBy { when (it.backendId) {
                "android" -> 0
                "accessibility" -> 1
                "shizuku" -> 2
                "root" -> 3
                "lsposed" -> 4
                else -> 5
            } },
    )
    val required = descriptor.mandatoryAccessRequirements()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(TextR.string.implementation_optimal_automatic),
                fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(if (descriptor.requiresRootToRun())
                    TextR.string.implementation_root_mandatory
                else TextR.string.implementation_nonroot_preferred),
                style = MaterialTheme.typography.bodySmall,
            )
            if (required.isNotEmpty()) {
                Text(stringResource(TextR.string.implementation_requirements_format,
                    localizedList(required.map { accessRequirementLabelNonComposable(it) })),
                    style = MaterialTheme.typography.bodySmall)
            }
            options.forEach { option ->
                val explanation = when (option.backendId) {
                    "shizuku" -> stringResource(TextR.string.implementation_shizuku_setup)
                    "root" -> stringResource(TextR.string.implementation_root_setup)
                    "lsposed" -> stringResource(TextR.string.implementation_lsposed_setup)
                    else -> if (option.requirements.isEmpty())
                        stringResource(TextR.string.implementation_no_special_access)
                    else localizedList(option.requirements.map { accessRequirementLabelNonComposable(it) })
                }
                Text(stringResource(TextR.string.implementation_path_permission_format,
                    implementationTitle(option.backendId.orEmpty()), explanation),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (initial?.config?.containsKey(FEATURE_METHOD_CONFIG_KEY) == true ||
                initial?.config?.containsKey(FEATURE_BACKEND_CONFIG_KEY) == true) {
                Text(stringResource(TextR.string.implementation_legacy_conversion),
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
