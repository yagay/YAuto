package com.yagay.yauto.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
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
    var expanded by remember(group.spec.id, selectedItem.descriptor.id.value) {
        mutableStateOf(false)
    }

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
            Box(Modifier.fillMaxWidth()) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { expanded = true },
                ) {
                    Text(
                        text = selectedItem.title,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Icon(
                        painter = painterResource(TextR.drawable.ic_chevron_right),
                        contentDescription = null,
                        modifier = Modifier.rotate(90f),
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    group.members.forEach { item ->
                        DropdownMenuItem(
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(item.title)
                                    if (item.description.isNotBlank() && item.description != item.title) {
                                        Text(
                                            text = item.description,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            },
                            onClick = {
                                expanded = false
                                onSelect(item)
                            },
                        )
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
