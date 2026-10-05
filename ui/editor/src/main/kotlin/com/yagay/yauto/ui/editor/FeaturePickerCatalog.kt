package com.yagay.yauto.ui.editor

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.ui.design.MacroPalette
import com.yagay.yauto.ui.design.R as TextR
import java.util.Locale

internal data class CatalogCategory(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    val order: Int,
)

internal sealed interface PickerPage {
    data object Categories : PickerPage
    data class Features(val category: CatalogCategory, val special: String? = null) : PickerPage
    data class Configure(val descriptor: FeatureDescriptor, val fromCategory: Features?) : PickerPage
}

@Composable
internal fun FeatureCategoryPage(
    modifier: Modifier,
    kind: FeatureKind,
    descriptors: List<FeatureDescriptor>,
    query: String,
    favorites: Set<String>,
    recent: List<String>,
    onQuery: (String) -> Unit,
    onCategory: (PickerPage.Features) -> Unit,
    onFeature: (FeatureDescriptor) -> Unit,
    onFavorite: (String) -> Unit,
) {
    val categories = remember(descriptors) {
        descriptors.map { catalogCategory(it.category) }.distinctBy { it.id }.sortedBy { it.order }
    }
    val textResolver = rememberFeatureTextResolver()
    val displayItems = remember(descriptors, textResolver) {
        descriptors.map(textResolver::displayText)
    }
    val descriptorIds = remember(displayItems) { displayItems.asSequence().map { it.descriptor.id.value }.toHashSet() }
    val search = remember(displayItems, query, textResolver) {
        if (query.isBlank()) emptyList() else displayItems.filter { textResolver.matches(it.descriptor, query) }
    }
    val recentCount = recent.count { it in descriptorIds }
    val favoriteCount = favorites.count { it in descriptorIds }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(TextR.string.feature_picker_search_format, kindLabel(kind))) },
                singleLine = true,
            )
        }
        if (query.isBlank()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = kindAccent(kind).copy(alpha = .10f))) {
                    Column(Modifier.padding(12.dp)) {
                        Text(kindHelp(kind), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(TextR.string.feature_picker_usage_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (recentCount > 0) {
                item {
                    CategoryRow(
                        stringResource(TextR.string.feature_picker_recent),
                        stringResource(TextR.string.feature_picker_recent_subtitle_format, recentCount),
                    ) {
                        onCategory(PickerPage.Features(catalogCategory(FeatureCategory.CORE), special = "recent"))
                    }
                }
            }
            if (favoriteCount > 0) {
                item {
                    CategoryRow(
                        stringResource(TextR.string.feature_picker_favorites),
                        stringResource(TextR.string.feature_picker_favorites_subtitle_format, favoriteCount),
                    ) {
                        onCategory(PickerPage.Features(catalogCategory(FeatureCategory.CORE), special = "favorites"))
                    }
                }
            }
            items(
                items = categories,
                key = { it.id },
                contentType = { "category" },
            ) { category ->
                val count = descriptors.count { it.category.name.lowercase(Locale.ROOT) == category.id }
                CategoryRow(
                    stringResource(category.titleRes),
                    stringResource(
                        TextR.string.editor_category_count_format,
                        stringResource(category.subtitleRes),
                        count,
                    ),
                ) { onCategory(PickerPage.Features(category)) }
            }
        } else {
            item {
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
                val descriptor = item.descriptor
                FeaturePickerRow(
                    title = if (descriptor.id.value in favorites) {
                        stringResource(TextR.string.editor_favorite_prefix, item.title)
                    } else item.title,
                    subtitle = stringResource(catalogCategory(descriptor.category).titleRes),
                    accent = kindAccent(kind),
                    onClick = { onFeature(descriptor) },
                    onFavorite = { onFavorite(descriptor.id.value) },
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
    descriptors: List<FeatureDescriptor>,
    query: String,
    favorites: Set<String>,
    recent: List<String>,
    onQuery: (String) -> Unit,
    onFeature: (FeatureDescriptor) -> Unit,
    onFavorite: (String) -> Unit,
) {
    val textResolver = rememberFeatureTextResolver()
    val locale = currentEditorLocale()
    val titleComparator = remember(locale) { localizedStringComparator(locale) }
    val displayItems = remember(descriptors, textResolver) {
        descriptors.map(textResolver::displayText)
    }
    val displayById = remember(displayItems) {
        displayItems.associateBy { it.descriptor.id.value }
    }
    val features = remember(displayItems, displayById, categoryPage, query, favorites, recent, textResolver, titleComparator) {
        val base = when (categoryPage.special) {
            "recent" -> recent.mapNotNull(displayById::get)
            "favorites" -> displayItems.filter { it.descriptor.id.value in favorites }
            else -> displayItems.filter {
                it.descriptor.category.name.lowercase(Locale.ROOT) == categoryPage.category.id
            }
        }
        base.filter { query.isBlank() || textResolver.matches(it.descriptor, query) }
            .sortedWith { left, right -> titleComparator.compare(left.title, right.title) }
    }
    val title = categoryTitle(categoryPage)
    val subtitle = categorySubtitle(categoryPage)

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(TextR.string.feature_picker_search_in_format, title)) },
                singleLine = true,
            )
        }
        item {
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
            val descriptor = item.descriptor
            FeaturePickerRow(
                title = if (descriptor.id.value in favorites) {
                    stringResource(TextR.string.editor_favorite_prefix, item.title)
                } else item.title,
                subtitle = null,
                accent = kindAccent(kind),
                onClick = { onFeature(descriptor) },
                onFavorite = { onFavorite(descriptor.id.value) },
            )
        }
    }
}

@Composable
private fun FeaturePickerRow(
    title: String,
    subtitle: String?,
    accent: Color,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
) {
    val divider = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
            .padding(start = 14.dp, top = 9.dp, bottom = 9.dp),
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
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            painter = androidx.compose.ui.res.painterResource(TextR.drawable.ic_more),
            contentDescription = stringResource(TextR.string.icon_more_options),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(40.dp)
                .clickable(onClick = onFavorite)
                .padding(10.dp),
        )
    }
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

internal fun catalogCategory(category: FeatureCategory): CatalogCategory = when (category) {
    FeatureCategory.CORE -> CatalogCategory("core", TextR.string.category_core, TextR.string.category_core_subtitle, 10)
    FeatureCategory.APP -> CatalogCategory("app", TextR.string.category_app, TextR.string.category_app_subtitle, 20)
    FeatureCategory.DEVICE -> CatalogCategory("device", TextR.string.category_device, TextR.string.category_device_subtitle, 30)
    FeatureCategory.NETWORK -> CatalogCategory("network", TextR.string.category_network, TextR.string.category_network_subtitle, 40)
    FeatureCategory.DISPLAY -> CatalogCategory("display", TextR.string.category_display, TextR.string.category_display_subtitle, 50)
    FeatureCategory.AUDIO -> CatalogCategory("audio", TextR.string.category_audio, TextR.string.category_audio_subtitle, 60)
    FeatureCategory.NOTIFICATION -> CatalogCategory("notification", TextR.string.category_notification, TextR.string.category_notification_subtitle, 70)
    FeatureCategory.FILE -> CatalogCategory("file", TextR.string.category_file, TextR.string.category_file_subtitle, 80)
    FeatureCategory.VARIABLE -> CatalogCategory("variable", TextR.string.category_variable, TextR.string.category_variable_subtitle, 90)
    FeatureCategory.FLOW -> CatalogCategory("flow", TextR.string.category_flow, TextR.string.category_flow_subtitle, 100)
    FeatureCategory.UI_AUTOMATION -> CatalogCategory("ui_automation", TextR.string.category_ui_automation, TextR.string.category_ui_automation_subtitle, 110)
    FeatureCategory.SYSTEM -> CatalogCategory("system", TextR.string.category_system, TextR.string.category_system_subtitle, 120)
    FeatureCategory.SCRIPT -> CatalogCategory("script", TextR.string.category_script, TextR.string.category_script_subtitle, 130)
    FeatureCategory.ADVANCED -> CatalogCategory("advanced", TextR.string.category_advanced, TextR.string.category_advanced_subtitle, 140)
    FeatureCategory.COMPATIBILITY -> CatalogCategory("advanced", TextR.string.category_advanced, TextR.string.category_advanced_subtitle, 150)
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
