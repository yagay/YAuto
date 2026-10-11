package com.yagay.yauto.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.capability.CapabilityBackendPriority
import com.yagay.yauto.core.registry.*
import com.yagay.yauto.ui.design.R as TextR

/**
 * Permissions alone are displayed here, never implementation names or method
 * selectors. Alternative backends are OR, not cumulative requirements.
 */
internal fun permissionsForFeature(
    descriptor: FeatureDescriptor,
    existing: FeatureRef?,
    preferRootWhenAvailable: Boolean = true,
    availability: (AccessRequirement) -> PermissionAvailability,
): List<AccessRequirement> {
    val required = descriptor.mandatoryAccessRequirements()
    val options = descriptor.resolvedImplementationOptions()
        .filter { option -> supportsConfiguredOperation(descriptor, existing, option.backendId) }
    if (options.isEmpty()) return required.sortedBy { it.ordinal }
    val fixedBackend = existing?.preferredBackendId()
    val method = existing?.preferredMethod()
    val eligible = options.filter { option ->
        fixedBackend == null || option.backendId == fixedBackend
    }.let { candidates ->
        // Never recommend another backend for a manually pinned legacy task.
        if (fixedBackend != null) candidates
        else if (method == FeatureMethod.ROOT_REQUIRED) {
            candidates.filter { it.requiresRootOrLsposed() }
        } else if (method == FeatureMethod.NO_ROOT) {
            candidates.filterNot { it.requiresRootOrLsposed() }
        } else candidates
    }
    val best = eligible.minWithOrNull(
        compareBy<FeatureImplementationOption> { option ->
            option.requirements.count { availability(it) != PermissionAvailability.GRANTED }
        }.thenBy { option ->
            // Only prioritize LSPosed/Root when their authorization is confirmed.
            // Otherwise suggest available Shizuku/Accessibility rather than asking
            // for unnecessary privileged permissions.
            val ready = option.requirements.all { availability(it) == PermissionAvailability.GRANTED }
            CapabilityBackendPriority.rank(
                option.backendId,
                privilegedFirst = ready && preferRootWhenAvailable,
            )
        },
    )
    return (required + best.orEmptyRequirements()).sortedBy { it.ordinal }
}

/**
 * Availability is operation-dependent, not just a global permission checkbox.
 * Avoid prompting for an implementation that cannot perform the configured
 * action (for example LSPosed cannot inject arbitrary BACK/HOME keys).
 */
private fun supportsConfiguredOperation(
    descriptor: FeatureDescriptor,
    existing: FeatureRef?,
    backendId: String?,
): Boolean {
    return when (descriptor.id.value) {
        "accessibility.global_action" -> when (existing?.config?.string("action").orEmpty()) {
            "power_dialog" -> backendId == "accessibility"
            "back", "home", "recents" -> backendId != "lsposed"
            else -> true
        }
        "android.lsposed.system.operation" -> when (existing?.config?.string("operation").orEmpty()) {
            "sleep", "expand_notifications", "expand_quick_settings" -> true
            "wake", "collapse_panels", "reboot", "reboot_recovery",
            "reboot_bootloader", "shutdown" -> backendId != "accessibility"
            else -> true
        }
        else -> true
    }
}

private fun FeatureImplementationOption?.orEmptyRequirements(): Set<AccessRequirement> =
    this?.requirements.orEmpty()

@Composable
internal fun AutoImplementationSummary(descriptor: FeatureDescriptor, initial: FeatureRef?) {
    val gateway = LocalFeaturePermissionGateway.current
    val requirements = permissionsForFeature(descriptor, initial,
        preferRootWhenAvailable = gateway?.preferRootWhenAvailable ?: true) {
        gateway?.availability(it) ?: PermissionAvailability.UNKNOWN
    }
    if (requirements.isEmpty()) return

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(TextR.string.feature_permission_title),
                fontWeight = FontWeight.SemiBold)
            requirements.forEach { permission ->
                val status = gateway?.availability(permission) ?: PermissionAvailability.UNKNOWN
                Row(Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(accessRequirementLabelNonComposable(permission),
                        modifier = Modifier.weight(1f))
                    OutlinedButton(
                        enabled = gateway != null,
                        onClick = { gateway?.request(permission) },
                    ) {
                        Text(stringResource(when (status) {
                            PermissionAvailability.GRANTED -> TextR.string.feature_permission_granted
                            PermissionAvailability.NOT_GRANTED -> TextR.string.feature_permission_grant
                            PermissionAvailability.UNKNOWN -> TextR.string.feature_permission_check
                        }))
                    }
                }
            }
            gateway?.feedback?.takeIf { it.isNotBlank() }?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
