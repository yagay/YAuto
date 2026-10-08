package com.yagay.yauto.ui.editor

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.ui.design.MacroItemRow
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
        descriptors.map { macroCategory(it) }.distinctBy { it.id }.sortedBy { it.order }
    }
    val textResolver = rememberFeatureTextResolver()
    val search = remember(descriptors, query, textResolver) {
        if (query.isBlank()) emptyList() else descriptors.filter { textResolver.matches(it, query) }
    }
    val recentCount = recent.count { id -> descriptors.any { it.id.value == id } }
    val favoriteCount = favorites.count { id -> descriptors.any { it.id.value == id } }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
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
            items(categories, key = { it.id }) { category ->
                val count = descriptors.count { macroCategory(it).id == category.id }
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
            items(search, key = { it.id.value }) { descriptor ->
                val title = localizedFeatureTitle(descriptor)
                MacroItemRow(
                    title = if (descriptor.id.value in favorites) {
                        stringResource(TextR.string.editor_favorite_prefix, title)
                    } else title,
                    subtitle = stringResource(
                        TextR.string.editor_feature_search_subtitle_format,
                        stringResource(macroCategory(descriptor).titleRes),
                        localizedFeatureDescriptionShared(descriptor),
                    ),
                    accent = kindAccent(kind),
                    onClick = { onFeature(descriptor) },
                    onMenu = { onFavorite(descriptor.id.value) },
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
    val features = remember(descriptors, categoryPage, query, favorites, recent, textResolver, titleComparator) {
        val base = when (categoryPage.special) {
            "recent" -> recent.mapNotNull { id -> descriptors.firstOrNull { it.id.value == id } }
            "favorites" -> descriptors.filter { it.id.value in favorites }
            else -> descriptors.filter { macroCategory(it).id == categoryPage.category.id }
        }
        base.filter { query.isBlank() || textResolver.matches(it, query) }
            .sortedWith { left, right -> titleComparator.compare(textResolver.title(left), textResolver.title(right)) }
    }
    val title = categoryTitle(categoryPage)
    val subtitle = categorySubtitle(categoryPage)

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
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
        items(features, key = { it.id.value }) { descriptor ->
            val localizedTitle = localizedFeatureTitle(descriptor)
            MacroItemRow(
                title = if (descriptor.id.value in favorites) {
                    stringResource(TextR.string.editor_favorite_prefix, localizedTitle)
                } else localizedTitle,
                subtitle = localizedFeatureDescriptionShared(descriptor),
                accent = kindAccent(kind),
                onClick = { onFeature(descriptor) },
                onMenu = { onFavorite(descriptor.id.value) },
            )
        }
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

/**
 * Presentation-only MacroDroid-style taxonomy. Feature IDs and persisted categories stay intact.
 * Resolve feature families before the broad registry category so phone/location/time entries
 * do not disappear into Device or System. Categories are computed for the active picker kind.
 */
internal fun macroCategory(descriptor: FeatureDescriptor): CatalogCategory {
    val id = descriptor.id.value.lowercase(Locale.ROOT)
    fun group(key: String, title: Int, subtitle: Int, order: Int) =
        CatalogCategory(key, title, subtitle, order)
    // Match complete ID segments; these are presentation rules, never persisted IDs.
    // Specific domains take precedence over general tokens (e.g. battery voltage,
    // SMS notification, Wi-Fi scan time, media screen controls).
    fun has(vararg tokens: String): Boolean = tokens.any { token ->
        ("_" + id + "_").contains("_" + token + "_")
    }
    return when {
        has("sms", "mms", "email", "messaging") || has("message") && !has("log") ->
            group("messaging", TextR.string.macro_category_messaging, TextR.string.macro_category_messaging_subtitle, 76)
        has("notification", "toast", "quick_tile", "qs_tile") ->
            group("notification", TextR.string.category_notification, TextR.string.category_notification_subtitle, 70)
        has("geofence", "location", "gps") ->
            group("location", TextR.string.macro_category_location, TextR.string.macro_category_location_subtitle, 55)
        has("phone", "telephony", "dial", "dialer") || has("call") && !has("callback") ->
            group("phone", TextR.string.macro_category_phone, TextR.string.macro_category_phone_subtitle, 75)
        has("headset", "audio", "volume", "ringer", "speakerphone", "microphone", "media", "playback", "midi") ->
            group("media", TextR.string.macro_category_media, TextR.string.macro_category_media_subtitle, 65)
        has("wifi", "bluetooth", "network", "vpn", "nfc", "usb", "airplane", "hotspot", "ethernet") ||
            has("mobile") && has("data") ->
            group("connectivity", TextR.string.macro_category_connectivity, TextR.string.macro_category_connectivity_subtitle, 50)
        has("battery", "charging", "charger") || has("device", "idle") && id.contains("device_idle") ||
            has("power") && !id.contains("power_user") ->
            group("battery", TextR.string.macro_category_battery, TextR.string.macro_category_battery_subtitle, 40)
        has("screen", "display", "brightness", "wallpaper", "rotation") ->
            group("screen", TextR.string.macro_category_screen, TextR.string.macro_category_screen_subtitle, 60)
        has("sensor", "shake", "proximity", "orientation", "accelerometer", "gyroscope") ->
            group("sensors", TextR.string.macro_category_sensors, TextR.string.macro_category_sensors_subtitle, 45)
        has("time", "date", "alarm", "calendar", "sunrise", "sunset", "interval", "clock", "schedule") ->
            group("date_time", TextR.string.macro_category_date_time, TextR.string.macro_category_date_time_subtitle, 35)
        else -> catalogCategory(descriptor.category)
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
