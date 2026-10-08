package com.yagay.yauto.ui.editor

import androidx.annotation.StringRes
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeaturePickerCategory
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.normalizeMacroDroidPickerCategory
import com.yagay.yauto.ui.design.R as TextR
import java.util.Locale

internal data class CatalogCategory(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    val order: Int,
)

internal data class FeaturePickerCatalogItem(
    val descriptor: FeatureDescriptor,
    val title: String,
    val description: String,
    val category: CatalogCategory,
    val searchIndex: String,
)

internal class FeaturePickerCatalogModel private constructor(
    val allItems: List<FeaturePickerCatalogItem>,
    val categories: List<CatalogCategory>,
    private val byId: Map<String, FeaturePickerCatalogItem>,
    private val byCategory: Map<String, List<FeaturePickerCatalogItem>>,
    private val unifiedIndex: UnifiedFeatureIndex,
    private val categoryCounts: Map<String, Int>,
) {
    fun item(id: String): FeaturePickerCatalogItem? = byId[id]

    fun contains(id: String): Boolean = id in byId

    fun categoryCount(categoryId: String): Int = categoryCounts[categoryId] ?: 0

    fun favoriteCount(favorites: Set<String>): Int =
        collapseUnifiedFeatureItems(
            allItems.filter { it.descriptor.id.value in favorites }, unifiedIndex,
        ).size

    fun recentCount(recent: List<String>): Int =
        collapseUnifiedFeatureItems(recent.mapNotNull(byId::get), unifiedIndex).size

    fun unifiedGroup(id: String): UnifiedFeatureGroup? = unifiedIndex.byId[id]

    fun unifiedGroupForMember(memberId: String): UnifiedFeatureGroup? =
        unifiedIndex.byMemberId[memberId]?.let(unifiedIndex.byId::get)

    fun search(query: String): List<FeaturePickerCatalogItem> {
        val needle = normalizeQuery(query)
        if (needle.isEmpty()) return emptyList()
        return allItems.filter { needle in it.searchIndex }
    }

    fun searchEntries(query: String): List<FeaturePickerListEntry> =
        collapseUnifiedFeatureItems(search(query), unifiedIndex)

    fun entries(
        page: PickerPage.Features,
        favorites: Set<String>,
        recent: List<String>,
        query: String,
    ): List<FeaturePickerListEntry> {
        val items = items(page, favorites, recent, query)
        return collapseUnifiedFeatureItems(items, unifiedIndex)
    }

    fun items(
        page: PickerPage.Features,
        favorites: Set<String>,
        recent: List<String>,
        query: String,
    ): List<FeaturePickerCatalogItem> {
        val base = when (page.special) {
            "recent" -> recent.mapNotNull(byId::get)
            "favorites" -> allItems.filter { it.descriptor.id.value in favorites }
            else -> byCategory[page.category.id].orEmpty()
        }
        val needle = normalizeQuery(query)
        return if (needle.isEmpty()) base else base.filter { needle in it.searchIndex }
    }

    companion object {
        fun create(
            items: List<FeaturePickerCatalogItem>,
            titleComparator: Comparator<String>,
        ): FeaturePickerCatalogModel {
            val sorted = items.sortedWith { left, right ->
                titleComparator.compare(left.title, right.title)
            }
            // A family has one category in the picker, even when its concrete operations
            // were inferred into different legacy categories. Preserve the underlying IDs.
            val families = buildUnifiedFeatureIndex(sorted)
            val familyCategories = families.byId.mapValues { (_, group) ->
                group.members.groupingBy { it.category }.eachCount().entries
                    .sortedWith(
                        compareByDescending<Map.Entry<CatalogCategory, Int>> { it.value }
                            .thenBy { it.key.order }
                            .thenBy { it.key.id }
                    ).first().key
            }
            val normalized = sorted.map { item ->
                val familyId = families.byMemberId[item.descriptor.id.value]
                val canonical = familyId?.let(familyCategories::get)
                if (canonical != null && item.category != canonical) item.copy(category = canonical)
                else item
            }
            val unifiedIndex = buildUnifiedFeatureIndex(normalized)
            val categories = normalized.map { it.category }.distinctBy { it.id }.sortedBy { it.order }
            val byCategory = normalized.groupBy { it.category.id }
            return FeaturePickerCatalogModel(
                allItems = normalized,
                categories = categories,
                byId = normalized.associateBy { it.descriptor.id.value },
                byCategory = byCategory,
                unifiedIndex = unifiedIndex,
                categoryCounts = byCategory.mapValues { (_, categoryItems) ->
                    collapseUnifiedFeatureItems(categoryItems, unifiedIndex).size
                },
            )
        }
    }
}

internal fun buildFeaturePickerCatalog(
    descriptors: List<FeatureDescriptor>,
    textResolver: FeatureTextResolver,
    titleComparator: Comparator<String>,
): FeaturePickerCatalogModel = FeaturePickerCatalogModel.create(
    items = descriptors.map { descriptor ->
        FeaturePickerCatalogItem(
            descriptor = descriptor,
            title = textResolver.title(descriptor),
            description = textResolver.description(descriptor),
            category = catalogCategory(
                normalizeMacroDroidPickerCategory(descriptor.kind, descriptor.pickerCategory),
                descriptor.kind,
            ),
            searchIndex = textResolver.searchText(descriptor).lowercase(Locale.ROOT),
        )
    },
    titleComparator = titleComparator,
)

internal fun catalogCategory(
    category: FeaturePickerCategory,
    kind: FeatureKind? = null,
): CatalogCategory = when (category) {
    FeaturePickerCategory.AI ->
        CatalogCategory("ai", TextR.string.macro_category_ai, TextR.string.macro_category_ai_subtitle, 10)
    FeaturePickerCategory.APPLICATIONS ->
        CatalogCategory("applications", TextR.string.macro_category_applications, TextR.string.macro_category_applications_subtitle, 20)
    FeaturePickerCategory.BATTERY_POWER ->
        CatalogCategory("battery_power", TextR.string.macro_category_battery_power, TextR.string.macro_category_battery_power_subtitle, 30)
    FeaturePickerCategory.CALL_SMS ->
        CatalogCategory("call_sms", TextR.string.macro_category_call_sms, TextR.string.macro_category_call_sms_subtitle, 40)
    FeaturePickerCategory.CAMERA_PHOTO ->
        CatalogCategory("camera_photo", TextR.string.macro_category_camera_photo, TextR.string.macro_category_camera_photo_subtitle, 50)
    FeaturePickerCategory.CONNECTIVITY ->
        CatalogCategory("connectivity", TextR.string.macro_category_connectivity, TextR.string.macro_category_connectivity_subtitle, 60)
    FeaturePickerCategory.DATE_TIME ->
        CatalogCategory("date_time", TextR.string.macro_category_date_time, TextR.string.macro_category_date_time_subtitle, 70)
    FeaturePickerCategory.DEVICE_ACTIONS ->
        CatalogCategory("device_actions", TextR.string.macro_category_device_actions, TextR.string.macro_category_device_actions_subtitle, 80)
    FeaturePickerCategory.DEVICE_EVENTS ->
        CatalogCategory("device_events", TextR.string.macro_category_device_events, TextR.string.macro_category_device_events_subtitle, 90)
    FeaturePickerCategory.DEVICE_SETTINGS ->
        CatalogCategory("device_settings", TextR.string.macro_category_device_settings, TextR.string.macro_category_device_settings_subtitle, 100)
    FeaturePickerCategory.DEVICE_STATE ->
        CatalogCategory("device_state", TextR.string.macro_category_device_state, TextR.string.macro_category_device_state_subtitle, 110)
    FeaturePickerCategory.FILES ->
        CatalogCategory("files", TextR.string.macro_category_files, TextR.string.macro_category_files_subtitle, 120)
    FeaturePickerCategory.LOCATION ->
        CatalogCategory("location", TextR.string.macro_category_location, TextR.string.macro_category_location_subtitle, 130)
    FeaturePickerCategory.LOGGING ->
        CatalogCategory("logging", TextR.string.macro_category_logging, TextR.string.macro_category_logging_subtitle, 140)
    FeaturePickerCategory.MACROS ->
        CatalogCategory("macros", TextR.string.macro_category_macros, TextR.string.macro_category_macros_subtitle, 145)
    FeaturePickerCategory.CONDITIONS_LOOPS ->
        CatalogCategory("conditions_loops", TextR.string.macro_category_conditions_loops, TextR.string.macro_category_conditions_loops_subtitle, 150)
    FeaturePickerCategory.YAUTO_SPECIFIC ->
        CatalogCategory("yauto_specific", TextR.string.macro_category_yauto_specific, TextR.string.macro_category_yauto_specific_subtitle, 160)
    FeaturePickerCategory.MEDIA ->
        CatalogCategory("media", TextR.string.macro_category_media, TextR.string.macro_category_media_subtitle, 170)
    FeaturePickerCategory.MESSAGING ->
        CatalogCategory("messaging", TextR.string.macro_category_messaging, TextR.string.macro_category_messaging_subtitle, 180)
    FeaturePickerCategory.NOTIFICATIONS ->
        CatalogCategory("notifications", TextR.string.macro_category_notifications, TextR.string.macro_category_notifications_subtitle, 190)
    FeaturePickerCategory.PHONE ->
        CatalogCategory("phone", TextR.string.macro_category_phone, TextR.string.macro_category_phone_subtitle, 200)
    FeaturePickerCategory.SCREEN ->
        if (kind == FeatureKind.CONDITION || kind == FeatureKind.STATE) {
            CatalogCategory("screen", TextR.string.macro_category_screen_speaker, TextR.string.macro_category_screen_speaker_subtitle, 210)
        } else {
            CatalogCategory("screen", TextR.string.macro_category_screen, TextR.string.macro_category_screen_subtitle, 210)
        }
    FeaturePickerCategory.SENSORS ->
        CatalogCategory("sensors", TextR.string.macro_category_sensors, TextR.string.macro_category_sensors_subtitle, 220)
    FeaturePickerCategory.USER_INPUT ->
        CatalogCategory("user_input", TextR.string.macro_category_user_input, TextR.string.macro_category_user_input_subtitle, 230)
    FeaturePickerCategory.VARIABLES ->
        CatalogCategory("variables", TextR.string.macro_category_variables, TextR.string.macro_category_variables_subtitle, 235)
    FeaturePickerCategory.VOLUME ->
        CatalogCategory("volume", TextR.string.macro_category_volume, TextR.string.macro_category_volume_subtitle, 240)
    FeaturePickerCategory.WEB_INTERACTIONS ->
        CatalogCategory("web_interactions", TextR.string.macro_category_web_interactions, TextR.string.macro_category_web_interactions_subtitle, 250)
}

private fun normalizeQuery(value: String): String = value.trim().lowercase(Locale.ROOT)
