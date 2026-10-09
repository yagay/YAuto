package com.yagay.yauto.ui.editor

internal data class UnifiedFeatureGroup(
    val spec: UnifiedFeatureSpec,
    val members: List<FeaturePickerCatalogItem>,
)

internal sealed interface FeaturePickerListEntry {
    data class Feature(val item: FeaturePickerCatalogItem) : FeaturePickerListEntry
    data class Unified(
        val group: UnifiedFeatureGroup,
        /** The operation that matched search, recent history or the favorites filter. */
        val preferredMemberId: String,
    ) : FeaturePickerListEntry
}

internal data class UnifiedFeatureIndex(
    val byId: Map<String, UnifiedFeatureGroup>,
    val byMemberId: Map<String, String>,
)

internal fun buildUnifiedFeatureIndex(items: List<FeaturePickerCatalogItem>): UnifiedFeatureIndex {
    val duplicateMemberIds = UNIFIED_FEATURE_SPECS
        .flatMap { spec -> spec.memberIds.map { memberId -> memberId to spec.id } }
        .groupBy({ it.first }, { it.second })
        .filterValues { conceptIds -> conceptIds.distinct().size > 1 }
    check(duplicateMemberIds.isEmpty()) {
        "Unified feature member belongs to multiple concepts: $duplicateMemberIds"
    }

    val byFeatureId = items.associateBy { it.descriptor.id.value }
    val groups = buildMap {
        UNIFIED_FEATURE_SPECS.forEach { spec ->
            val members = spec.memberIds.mapNotNull(byFeatureId::get)
            // A source-reviewed logical function is one picker entry even if its
            // implementations were registered under different browsing categories.
            // Keep EVENT/ACTION/STATE/CONDITION separate; never rewrite descriptor
            // categories, IDs, permission requirements, or saved configurations.
            // The first available member in the verified spec determines the sole
            // MacroDroid browsing category where this logical function appears.
            val partitions = members.groupBy { it.descriptor.kind }
            partitions.forEach { (kind, sameOperation) ->
                if (sameOperation.size < 2) return@forEach
                val groupId = if (partitions.size == 1) spec.id else
                    "${spec.id}:${kind.name.lowercase()}"
                val subSpec = if (groupId == spec.id) spec else spec.copy(
                    id = groupId,
                    memberIds = sameOperation.map { it.descriptor.id.value },
                )
                put(groupId, UnifiedFeatureGroup(subSpec, sameOperation))
            }
        }
    }

    return UnifiedFeatureIndex(
        byId = groups,
        byMemberId = buildMap {
            groups.forEach { (groupId, group) ->
                group.members.forEach { member -> put(member.descriptor.id.value, groupId) }
            }
        },
    )
}

internal fun collapseUnifiedFeatureItems(
    items: List<FeaturePickerCatalogItem>,
    unifiedIndex: UnifiedFeatureIndex,
): List<FeaturePickerListEntry> {
    val emittedGroups = HashSet<String>()
    return buildList {
        items.forEach { item ->
            val groupId = unifiedIndex.byMemberId[item.descriptor.id.value]
            val group = groupId?.let(unifiedIndex.byId::get)
            if (group == null) {
                add(FeaturePickerListEntry.Feature(item))
            } else if (emittedGroups.add(groupId)) {
                add(FeaturePickerListEntry.Unified(group, item.descriptor.id.value))
            }
        }
    }
}
