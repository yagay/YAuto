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
    val initialSelection = remember(group.spec.id, initial?.typeId, initialMemberId) {
        resolveUnifiedMemberId(
            group = group,
            initialTypeId = initial?.typeId,
            requestedMemberId = initialMemberId,
        )
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
            leadingContent = {
                UnifiedFeatureSelector(
                    group = group,
                    selectedItem = selectedItem,
                    onSelect = { selectedMemberId = it.descriptor.id.value },
                )
            },
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
                                if (item.description.isNotBlank() && item.description != item.title) {
                                    Text(
                                        text = item.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Text(
                text = stringResource(unifiedSelectorHintRes(selectedItem.descriptor.kind)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
