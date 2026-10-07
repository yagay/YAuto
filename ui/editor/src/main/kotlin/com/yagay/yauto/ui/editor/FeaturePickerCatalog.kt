package com.yagay.yauto.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import com.yagay.yauto.core.registry.FeatureDomain
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
    onFavorite: (String) -> Unit,
) {
    val availability = LocalFeatureAvailability.current
    val search = remember(catalog, query) {
        if (query.isBlank()) emptyList() else catalog.search(query)
    }
    val recentCount = recent.count(catalog::contains)
    val favoriteCount = favorites.count(catalog::contains)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
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
                        onCategory(PickerPage.Features(catalogCategory(FeatureDomain.YAUTO, kind), special = "recent"))
                    }
                }
            }
            if (favoriteCount > 0) {
                item(key = "favorites", contentType = "category") {
                    CategoryRow(
                        stringResource(TextR.string.feature_picker_favorites),
                        stringResource(TextR.string.feature_picker_favorites_subtitle_format, favoriteCount),
                    ) {
                        onCategory(PickerPage.Features(catalogCategory(FeatureDomain.YAUTO, kind), special = "favorites"))
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
                key = { it.descriptor.id.value },
                contentType = { "feature_search_result" },
            ) { item ->
                FeaturePickerRow(
                    title = favoriteTitle(item, favorites),
                    subtitle = stringResource(item.category.titleRes),
                    accent = kindAccent(kind),
                    onClick = { onFeature(item.descriptor) },
                    onFavorite = { onFavorite(item.descriptor.id.value) },
                    height = 72.dp,
                    availability = availability[item.descriptor.id.value],
                    accessTags = featureAccessTags(item.descriptor),
                )
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
    onFavorite: (String) -> Unit,
) {
    val availability = LocalFeatureAvailability.current
    val features = remember(catalog, categoryPage, query, favorites, recent) {
        catalog.items(categoryPage, favorites, recent, query)
    }
    val title = categoryTitle(categoryPage)
    val subtitle = categorySubtitle(categoryPage)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
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
            items = features,
            key = { it.descriptor.id.value },
            contentType = { "feature" },
        ) { item ->
            FeaturePickerRow(
                title = favoriteTitle(item, favorites),
                subtitle = null,
                accent = kindAccent(kind),
                onClick = { onFeature(item.descriptor) },
                onFavorite = { onFavorite(item.descriptor.id.value) },
                height = 68.dp,
                availability = availability[item.descriptor.id.value],
                accessTags = featureAccessTags(item.descriptor),
            )
        }
    }
}

@Composable
private fun favoriteTitle(item: FeaturePickerCatalogItem, favorites: Set<String>): String =
    if (item.descriptor.id.value in favorites) {
        stringResource(TextR.string.editor_favorite_prefix, item.title)
    } else {
        item.title
    }

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
) {
    val divider = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                val accentWidth = 4.dp.toPx()
                drawRect(accent, size = androidx.compose.ui.geometry.Size(accentWidth, size.height))
                drawLine(
                    color = divider,
                    start = androidx.compose.ui.geometry.Offset(accentWidth, size.height),
                    end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .clickable(onClick = onClick)
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val supporting = localizedList(
                listOfNotNull(
                    subtitle?.takeIf { it.isNotBlank() },
                    accessTags.takeIf { it.isNotBlank() },
                    availability?.summary?.takeIf { it.isNotBlank() },
                )
            )
            if (supporting.isNotBlank()) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        availability?.let {
            Text(
                text = it.statusLabel,
                style = MaterialTheme.typography.labelSmall,
                color = when (it.tone) {
                    FeatureAvailabilityTone.READY -> MacroPalette.Constraint
                    FeatureAvailabilityTone.BLOCKED -> MacroPalette.Trigger
                    FeatureAvailabilityTone.BROKEN -> MaterialTheme.colorScheme.error
                    FeatureAvailabilityTone.UNSUPPORTED -> MacroPalette.Utility
                },
                modifier = Modifier.padding(horizontal = 6.dp),
                maxLines = 1,
            )
        }
        Icon(
            painter = androidx.compose.ui.res.painterResource(TextR.drawable.ic_more),
            contentDescription = stringResource(TextR.string.icon_more_options),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(44.dp)
                .clickable(onClick = onFavorite)
                .padding(11.dp),
        )
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
    return localizedList(
        buildList {
            if (AccessRequirement.ROOT in requirements) add(stringResource(TextR.string.access_root))
            if (AccessRequirement.SHIZUKU in requirements) add(stringResource(TextR.string.access_shizuku))
            if (AccessRequirement.LSPOSED in requirements) add(stringResource(TextR.string.access_lsposed))
            if (AccessRequirement.ZYGISK in requirements) add(stringResource(TextR.string.access_zygisk))
            if (AccessRequirement.ACCESSIBILITY in requirements) add(stringResource(TextR.string.access_accessibility))
        }
    )
}

@Composable
private fun CategoryRow(title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Icon(
                painter = androidx.compose.ui.res.painterResource(TextR.drawable.ic_chevron_right),
                contentDescription = stringResource(TextR.string.icon_open_details),
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
    HorizontalDivider()
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
