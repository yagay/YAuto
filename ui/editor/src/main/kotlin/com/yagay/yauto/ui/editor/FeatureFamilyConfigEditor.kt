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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.ui.design.R as TextR

/**
 * MacroDroid-style unified editor for a logical feature family.
 *
 * The user sees one feature entry and chooses the concrete operation as a setting. Runtime
 * compatibility stays intact because saving still emits the selected concrete Feature ID.
 */
@Composable
internal fun FeatureFamilyConfigEditor(
    modifier: Modifier,
    family: FeaturePickerFamily,
    initial: FeatureRef?,
    initialMemberId: String?,
    accent: Color,
    onSave: (FeatureRef) -> Unit,
) {
    val initialSelection = remember(family.spec.id, initial?.typeId, initialMemberId) {
        resolveFamilyMemberId(
            family = family,
            initialTypeId = initial?.typeId,
            requestedMemberId = initialMemberId,
        )
    }
    var selectedMemberId by remember(family.spec.id, initialSelection) {
        mutableStateOf(initialSelection)
    }
    val selectedItem = family.members.firstOrNull {
        it.descriptor.id.value == selectedMemberId
    } ?: family.members.first()

    key(selectedItem.descriptor.id.value) {
        GenericFeatureConfigEditor(
            modifier = modifier,
            descriptor = selectedItem.descriptor,
            initial = initial?.takeIf { it.typeId == selectedItem.descriptor.id.value },
            accent = accent,
            leadingContent = {
                FamilyOperationSelector(
                    family = family,
                    selectedItem = selectedItem,
                    onSelect = { selectedMemberId = it.descriptor.id.value },
                )
            },
            onSave = onSave,
        )
    }
}

@Composable
private fun FamilyOperationSelector(
    family: FeaturePickerFamily,
    selectedItem: FeaturePickerCatalogItem,
    onSelect: (FeaturePickerCatalogItem) -> Unit,
) {
    var expanded by remember(family.spec.id, selectedItem.descriptor.id.value) {
        mutableStateOf(false)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(family.spec.subtitleRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(TextR.string.feature_family_operation),
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
                    Text("▾")
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    family.members.forEach { item ->
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
                text = stringResource(TextR.string.feature_family_operation_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}


internal fun resolveFamilyMemberId(
    family: FeaturePickerFamily,
    initialTypeId: String?,
    requestedMemberId: String?,
): String {
    fun contains(id: String?): Boolean =
        id != null && family.members.any { it.descriptor.id.value == id }

    return when {
        contains(initialTypeId) -> initialTypeId!!
        contains(requestedMemberId) -> requestedMemberId!!
        else -> family.members.first().descriptor.id.value
    }
}
