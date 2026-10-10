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
            if (search.isEmpty()) {
                item(key = "no_search_results", contentType = "empty_state") {
                    PickerEmptyState(stringResource(TextR.string.feature_picker_no_matches))
                }
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
        if (query.isNotBlank()) {
            item(key = "category_result_count", contentType = "result_count") {
                Text(
                    stringResource(TextR.string.feature_picker_search_results_format, entries.size),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        if (entries.isEmpty()) {
            item(key = "empty_category", contentType = "empty_state") {
                PickerEmptyState(
                    stringResource(
                        if (query.isNotBlank()) TextR.string.feature_picker_no_matches
                        else TextR.string.feature_picker_empty_category,
                    )
                )
            }
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
