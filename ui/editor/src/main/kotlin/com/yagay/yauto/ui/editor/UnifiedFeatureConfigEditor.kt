package com.yagay.yauto.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.isRootExclusiveFeature
import com.yagay.yauto.ui.design.R as TextR

/**
 * MacroDroid-style unified editor for a logical unified feature concept.
 *
 * The user sees one feature entry and chooses the concrete operation as a setting. Runtime
 * compatibility stays intact because saving still emits the selected concrete Feature ID.
 */
@Composable
internal fun UnifiedFeatureConfigEditor(
    modifier: Modifier,
    group: UnifiedFeatureGroup,
    initial: FeatureRef?,
    initialMemberId: String?,
    accent: Color,
    onSave: (FeatureRef) -> Unit,
) {
    val automatic = group.spec.isAutomaticImplementationGroup()
    val initialSelection = remember(group, initial?.typeId, initialMemberId, automatic) {
        if (automatic) autoUnifiedMemberId(group, initial?.typeId)
        else resolveUnifiedMemberId(group, initial?.typeId, initialMemberId)
    }
    var selectedMemberId by remember(group.spec.id, initialSelection) {
        mutableStateOf(initialSelection)
    }
    val selectedItem = group.members.firstOrNull {
        it.descriptor.id.value == selectedMemberId
    } ?: group.members.first()

    key(selectedItem.descriptor.id.value) {
        GenericFeatureConfigEditor(
            modifier = modifier,
            descriptor = selectedItem.descriptor,
            initial = initial?.takeIf { it.typeId == selectedItem.descriptor.id.value },
            accent = accent,
            leadingContent = if (automatic) null else ({
                UnifiedFeatureSelector(
                    group = group,
                    selectedItem = selectedItem,
                    onSelect = { selectedMemberId = it.descriptor.id.value },
                )
            }),
            onSave = onSave,
        )
    }
}

@Composable
private fun UnifiedFeatureSelector(
    group: UnifiedFeatureGroup,
    selectedItem: FeaturePickerCatalogItem,
    onSelect: (FeaturePickerCatalogItem) -> Unit,
) {
    val textResolver = rememberFeatureTextResolver()
    fun methodLabel(item: FeaturePickerCatalogItem): String =
        textResolver.implementationLabel(item.descriptor.id.value) ?: item.title

    fun methodDescription(item: FeaturePickerCatalogItem): String? =
        textResolver.implementationDescription(item.descriptor.id.value)
            ?: item.description.takeIf { it.isNotBlank() && it != item.title }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(group.spec.subtitleRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(unifiedSelectorLabelRes(selectedItem.descriptor.kind)),
                style = MaterialTheme.typography.titleSmall,
            )
            // Two choices are compact chips; both are visible and switch immediately.
            // For 3+ methods, full-width radio rows prevent long localized labels
            // from being clipped or hidden behind an additional dropdown tap.
            if (group.members.size == 2) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    group.members.forEach { item ->
                        FilterChip(
                            selected = item.descriptor.id.value == selectedItem.descriptor.id.value,
                            onClick = { onSelect(item) },
                            label = { Text(methodLabel(item)) },
                        )
                    }
                }
                // Compact choices keep both methods visible; explain whichever
                // implementation is selected instead of repeating a generic hint.
                methodDescription(selectedItem)?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Column(Modifier.fillMaxWidth().selectableGroup()) {
                    group.members.forEach { item ->
                        val selected = item.descriptor.id.value == selectedItem.descriptor.id.value
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(item) },
                                )
                                .padding(vertical = 7.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Column(Modifier.weight(1f)) {
                                Text(methodLabel(item), style = MaterialTheme.typography.bodyMedium)
                                methodDescription(item)?.let { description ->
                                    Text(
                                        text = description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun resolveUnifiedMemberId(
    group: UnifiedFeatureGroup,
    initialTypeId: String?,
    requestedMemberId: String?,
): String {
    fun contains(id: String?): Boolean =
        id != null && group.members.any { it.descriptor.id.value == id }

    return when {
        contains(initialTypeId) -> initialTypeId!!
        contains(requestedMemberId) -> requestedMemberId!!
        else -> group.members.first().descriptor.id.value
    }
}


internal fun unifiedSelectorLabelRes(kind: FeatureKind): Int = when (kind) {
    FeatureKind.ACTION -> TextR.string.unified_selector_action
    FeatureKind.EVENT -> TextR.string.unified_selector_event
    FeatureKind.STATE, FeatureKind.CONDITION -> TextR.string.unified_selector_check
}

internal fun unifiedSelectorHintRes(kind: FeatureKind): Int = when (kind) {
    FeatureKind.ACTION -> TextR.string.unified_selector_action_hint
    FeatureKind.EVENT -> TextR.string.unified_selector_event_hint
    FeatureKind.STATE, FeatureKind.CONDITION -> TextR.string.unified_selector_check_hint
}

/**
 * Equivalent implementations are chosen automatically. Distinct operations
 * (start/stop, copy/move, Wi-Fi set/connect) still require an operation choice.
 */
internal fun UnifiedFeatureSpec.isAutomaticImplementationGroup(): Boolean = id in setOf(
    "clipboard_write", "clipboard_read", "screenshot_capture",
    "music_activity_condition", "music_activity_state",
    "device_orientation_condition", "device_orientation_state",
    "battery_status_condition", "battery_status_state",
    "battery_voltage_condition", "battery_voltage_state",
    "font_scale_condition", "font_scale_state",
    "audio_mode_condition", "audio_mode_state",
    "dnd_filter_condition", "dnd_filter_state",
    "camera_flash_condition", "camera_flash_state",
    "app_language", "torch_status_state", "torch_status_condition",
    "power_menu_access_method",
)

/** Prefer non-root when creating; preserve the concrete implementation of saved tasks. */
internal fun autoUnifiedMemberId(group: UnifiedFeatureGroup, initialId: String?): String {
    val members = group.members
    return members.firstOrNull { it.descriptor.id.value == initialId }?.descriptor?.id?.value
        ?: members.firstOrNull { !it.descriptor.isRootExclusiveFeature() }?.descriptor?.id?.value
        ?: members.first().descriptor.id.value
}
