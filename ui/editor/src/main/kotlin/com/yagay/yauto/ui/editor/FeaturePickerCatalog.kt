package com.yagay.yauto.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.registry.AccessRequirement
import com.yagay.yauto.core.registry.FeaturePickerCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.ui.design.MacroPalette
import com.yagay.yauto.ui.design.localizedList
import com.yagay.yauto.ui.design.R as TextR

@Composable
internal fun FeatureCategoryPage(
    modifier: Modifier,
    kind: FeatureKind,
    catalog: FeaturePickerCatalogModel,
    query: String,
    listState: LazyListState,
    favorites: Set<String>,
    recent: List<String>,
    onQuery: (String) -> Unit,
    onCategory: (PickerPage.Features) -> Unit,
    onFeature: (FeatureDescriptor) -> Unit,
    onUnified: (UnifiedFeatureGroup, String) -> Unit,
    onFavorite: (String) -> Unit,
    onUnifiedFavorite: (UnifiedFeatureGroup) -> Unit,
) {
    val availability = LocalFeatureAvailability.current
    val search = remember(catalog, query) {
        if (query.isBlank()) emptyList() else catalog.searchEntries(query)
    }
    val recentCount = remember(catalog, recent) { catalog.recentCount(recent) }
    val favoriteCount = remember(catalog, favorites) { catalog.favoriteCount(favorites) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        item(contentType = "search") {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(TextR.string.feature_picker_search_format, kindLabel(kind))) },
                singleLine = true,
            )
        }
        if (query.isBlank()) {
            item(contentType = "hint") {
                Card(colors = CardDefaults.cardColors(containerColor = kindAccent(kind).copy(alpha = .10f))) {
                    Column(Modifier.padding(12.dp)) {
                        Text(kindHelp(kind), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(TextR.string.feature_picker_usage_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (recentCount > 0) {
                item(key = "recent", contentType = "category") {
                    CategoryRow(
                        stringResource(TextR.string.feature_picker_recent),
                        stringResource(TextR.string.feature_picker_recent_subtitle_format, recentCount),
                    ) {
                        onCategory(PickerPage.Features(catalogCategory(FeaturePickerCategory.YAUTO_SPECIFIC), special = "recent"))
                    }
                }
            }
            if (favoriteCount > 0) {
                item(key = "favorites", contentType = "category") {
                    CategoryRow(
                        stringResource(TextR.string.feature_picker_favorites),
                        stringResource(TextR.string.feature_picker_favorites_subtitle_format, favoriteCount),
                    ) {
                        onCategory(PickerPage.Features(catalogCategory(FeaturePickerCategory.YAUTO_SPECIFIC), special = "favorites"))
                    }
                }
            }
            items(
                items = catalog.categories,
                key = { it.id },
                contentType = { "category" },
            ) { category ->
                CategoryRow(
                    stringResource(category.titleRes),
                    stringResource(
                        TextR.string.editor_category_count_format,
                        stringResource(category.subtitleRes),
                        catalog.categoryCount(category.id),
                    ),
                ) { onCategory(PickerPage.Features(category)) }
            }
        } else {
            item(contentType = "result_count") {
                Text(
                    stringResource(TextR.string.feature_picker_search_results_format, search.size),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            items(
                items = search,
                key = {
                    when (it) {
                        is FeaturePickerListEntry.Feature -> "feature:" + it.item.descriptor.id.value
                        is FeaturePickerListEntry.Unified -> "unified:" + it.group.spec.id
                    }
                },
                contentType = {
                    when (it) {
                        is FeaturePickerListEntry.Feature -> "feature_search_result"
                        is FeaturePickerListEntry.Unified -> "unified_search_result"
                    }
                },
            ) { entry ->
                when (entry) {
                    is FeaturePickerListEntry.Feature -> {
                        val item = entry.item
                        FeaturePickerRow(
                            title = favoriteTitle(item, favorites),
                            subtitle = localizedList(listOf(stringResource(item.category.titleRes), item.description)),
                            accent = kindAccent(kind),
                            onClick = { onFeature(item.descriptor) },
                            onFavorite = { onFavorite(item.descriptor.id.value) },
                            height = 72.dp,
                            availability = availability[item.descriptor.id.value],
                            accessTags = featureAccessTags(item.descriptor),
                        )
                    }
                    is FeaturePickerListEntry.Unified -> UnifiedFeatureRow(
                        group = entry.group,
                        accent = kindAccent(kind),
                        preferredTitle = entry.group.members.firstOrNull {
                            it.descriptor.id.value == entry.preferredMemberId
                        }?.title,
                        favorite = entry.group.members.any { it.descriptor.id.value in favorites },
                        onClick = { onUnified(entry.group, entry.preferredMemberId) },
                        onFavorite = { onUnifiedFavorite(entry.group) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun FeatureListPage(
    modifier: Modifier,
    kind: FeatureKind,
    categoryPage: PickerPage.Features,
    catalog: FeaturePickerCatalogModel,
    query: String,
    listState: LazyListState,
    favorites: Set<String>,
    recent: List<String>,
    onQuery: (String) -> Unit,
    onFeature: (FeatureDescriptor) -> Unit,
    onUnified: (UnifiedFeatureGroup, String) -> Unit,
    onFavorite: (String) -> Unit,
    onUnifiedFavorite: (UnifiedFeatureGroup) -> Unit,
) {
    val availability = LocalFeatureAvailability.current
    val entries = remember(catalog, categoryPage, query, favorites, recent) {
        catalog.entries(categoryPage, favorites, recent, query)
    }
    val title = categoryTitle(categoryPage)
    val subtitle = categorySubtitle(categoryPage)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        item(contentType = "search") {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(TextR.string.feature_picker_search_in_format, title)) },
                singleLine = true,
            )
        }
        item(contentType = "hint") {
            Text(
                stringResource(
                    TextR.string.feature_picker_category_hint_format,
                    subtitle,
                    stringResource(TextR.string.feature_picker_favorite_hint),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(
            items = entries,
            key = {
                when (it) {
                    is FeaturePickerListEntry.Feature -> "feature:" + it.item.descriptor.id.value
                    is FeaturePickerListEntry.Unified -> "unified:" + it.group.spec.id
                }
            },
            contentType = {
                when (it) {
                    is FeaturePickerListEntry.Feature -> "feature"
                    is FeaturePickerListEntry.Unified -> "unified"
                }
            },
        ) { entry ->
            when (entry) {
                is FeaturePickerListEntry.Feature -> {
                    val item = entry.item
                    FeaturePickerRow(
                        title = favoriteTitle(item, favorites),
                        subtitle = item.description,
                        accent = kindAccent(kind),
                        onClick = { onFeature(item.descriptor) },
                        onFavorite = { onFavorite(item.descriptor.id.value) },
                        height = 68.dp,
                        availability = availability[item.descriptor.id.value],
                        accessTags = featureAccessTags(item.descriptor),
                    )
                }
                is FeaturePickerListEntry.Unified -> UnifiedFeatureRow(
                    group = entry.group,
                    accent = kindAccent(kind),
                    preferredTitle = if (query.isNotBlank() || categoryPage.special != null) {
                        entry.group.members.firstOrNull {
                            it.descriptor.id.value == entry.preferredMemberId
                        }?.title
                    } else null,
                    favorite = entry.group.members.any { it.descriptor.id.value in favorites },
                    onClick = { onUnified(entry.group, entry.preferredMemberId) },
                    onFavorite = { onUnifiedFavorite(entry.group) },
                )
            }
        }
    }
}

@Composable
private fun UnifiedFeatureRow(
    group: UnifiedFeatureGroup,
    preferredTitle: String?,
    favorite: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
) {
    val title = stringResource(group.spec.titleRes)
    val detail = stringResource(
        TextR.string.feature_picker_family_count_format,
        stringResource(group.spec.subtitleRes),
        group.members.size,
    )
    FeaturePickerRow(
        title = if (favorite) stringResource(TextR.string.editor_favorite_prefix, title) else title,
        subtitle = if (preferredTitle.isNullOrBlank()) detail else "$preferredTitle · $detail",
        accent = accent,
        onClick = onClick,
        onFavorite = onFavorite,
        height = 68.dp,
        showChevron = true,
    )
}

@Composable
private fun favoriteTitle(item: FeaturePickerCatalogItem, favorites: Set<String>): String =
    if (item.descriptor.id.value in favorites) {
        stringResource(TextR.string.editor_favorite_prefix, item.title)
    } else {
        item.title
    }

/** Main-style rounded cards, with 1003's statuses, permission tags and stable list keys. */
@Composable
private fun FeaturePickerRow(
    title: String,
    subtitle: String?,
    accent: Color,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    height: androidx.compose.ui.unit.Dp,
    availability: FeatureAvailabilityUi? = null,
    accessTags: String = "",
    showChevron: Boolean = false,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(7.dp),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = height)
                .drawBehind {
                    drawRect(accent, size = androidx.compose.ui.geometry.Size(5.dp.toPx(), size.height))
                }
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val meta = localizedList(
                    listOfNotNull(
                        accessTags.takeIf { it.isNotBlank() },
                        availability?.summary?.takeIf { it.isNotBlank() },
                    )
                )
                if (meta.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                availability?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = it.statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = when (it.tone) {
                            FeatureAvailabilityTone.READY -> MacroPalette.Constraint
                            FeatureAvailabilityTone.BLOCKED -> MacroPalette.Trigger
                            FeatureAvailabilityTone.BROKEN -> MaterialTheme.colorScheme.error
                            FeatureAvailabilityTone.UNSUPPORTED -> MacroPalette.Utility
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Icon(
                painter = androidx.compose.ui.res.painterResource(TextR.drawable.ic_more),
                contentDescription = stringResource(TextR.string.icon_more_options),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(44.dp).clickable(onClick = onFavorite).padding(11.dp),
            )
            if (showChevron) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(TextR.drawable.ic_chevron_right),
                    contentDescription = stringResource(TextR.string.icon_open_details),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun featureAccessTags(descriptor: FeatureDescriptor): String {
    val requirements = remember(descriptor) {
        buildSet {
            addAll(descriptor.accessRequirements)
            descriptor.implementationOptions.forEach { addAll(it.requirements) }
        }
    }
    val root = if (AccessRequirement.ROOT in requirements) stringResource(TextR.string.access_root) else null
    val shizuku = if (AccessRequirement.SHIZUKU in requirements) stringResource(TextR.string.access_shizuku) else null
    val lsposed = if (AccessRequirement.LSPOSED in requirements) stringResource(TextR.string.access_lsposed) else null
    val zygisk = if (AccessRequirement.ZYGISK in requirements) stringResource(TextR.string.access_zygisk) else null
    val accessibility = if (AccessRequirement.ACCESSIBILITY in requirements) {
        stringResource(TextR.string.access_accessibility)
    } else {
        null
    }
    return localizedList(listOfNotNull(root, shizuku, lsposed, zygisk, accessibility))
}

/** Matches the main-branch card rhythm without changing category IDs or navigation. */
@Composable
private fun CategoryRow(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(7.dp),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                painter = androidx.compose.ui.res.painterResource(TextR.drawable.ic_chevron_right),
                contentDescription = stringResource(TextR.string.icon_open_details),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
internal fun categoryTitle(page: PickerPage.Features): String = when (page.special) {
    "recent" -> stringResource(TextR.string.feature_picker_recent)
    "favorites" -> stringResource(TextR.string.feature_picker_favorites)
    else -> stringResource(page.category.titleRes)
}

@Composable
internal fun categorySubtitle(page: PickerPage.Features): String = when (page.special) {
    "recent" -> stringResource(TextR.string.feature_picker_recent)
    "favorites" -> stringResource(TextR.string.feature_picker_favorites)
    else -> stringResource(page.category.subtitleRes)
}

@Composable
internal fun kindLabel(kind: FeatureKind): String = stringResource(
    when (kind) {
        FeatureKind.EVENT -> TextR.string.kind_event
        FeatureKind.STATE -> TextR.string.kind_state
        FeatureKind.ACTION -> TextR.string.kind_action
        FeatureKind.CONDITION -> TextR.string.kind_condition
    }
)

@Composable
internal fun kindHelp(kind: FeatureKind): String = stringResource(
    when (kind) {
        FeatureKind.EVENT -> TextR.string.kind_event_help
        FeatureKind.STATE -> TextR.string.kind_state_help
        FeatureKind.ACTION -> TextR.string.kind_action_help
        FeatureKind.CONDITION -> TextR.string.kind_condition_help
    }
)

internal fun kindAccent(kind: FeatureKind) = when (kind) {
    FeatureKind.EVENT -> MacroPalette.Trigger
    FeatureKind.STATE -> MacroPalette.State
    FeatureKind.ACTION -> MacroPalette.Action
    FeatureKind.CONDITION -> MacroPalette.Constraint
}
