package com.yagay.yauto.ui.editor

import androidx.annotation.StringRes
import com.yagay.yauto.core.registry.FeatureDomain
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureKind
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

    fun unifiedGroup(id: String): UnifiedFeatureGroup? = unifiedIndex.byId[id]

    fun unifiedGroupForMember(memberId: String): UnifiedFeatureGroup? =
        unifiedIndex.byMemberId[memberId]?.let(unifiedIndex.byId::get)

    fun search(query: String): List<FeaturePickerCatalogItem> {
        val needle = normalizeQuery(query)
        if (needle.isEmpty()) return emptyList()
        return allItems.filter { needle in it.searchIndex }
    }

    fun entries(
        page: PickerPage.Features,
        favorites: Set<String>,
        recent: List<String>,
        query: String,
    ): List<FeaturePickerListEntry> {
        val items = items(page, favorites, recent, query)
        return if (page.special != null || normalizeQuery(query).isNotEmpty()) {
            items.map { FeaturePickerListEntry.Feature(it) }
        } else {
            collapseUnifiedFeatureItems(items, unifiedIndex)
        }
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
            val categories = sorted
                .map { it.category }
                .distinctBy { it.id }
                .sortedBy { it.order }
            val byCategory = sorted.groupBy { it.category.id }
            val unifiedIndex = buildUnifiedFeatureIndex(sorted)
            return FeaturePickerCatalogModel(
                allItems = sorted,
                categories = categories,
                byId = sorted.associateBy { it.descriptor.id.value },
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
            category = catalogCategory(descriptor.domain, descriptor.kind),
            searchIndex = textResolver.searchText(descriptor).lowercase(Locale.ROOT),
        )
    },
    titleComparator = titleComparator,
)

internal fun catalogCategory(domain: FeatureDomain, kind: FeatureKind): CatalogCategory = when (domain) {
    FeatureDomain.APPLICATIONS -> CatalogCategory("applications", TextR.string.category_applications, TextR.string.category_applications_subtitle, 10)
    FeatureDomain.POWER -> CatalogCategory("power", TextR.string.category_power, TextR.string.category_power_subtitle, 20)
    FeatureDomain.COMMUNICATION -> CatalogCategory("communication", TextR.string.category_communication, TextR.string.category_communication_subtitle, 30)
    FeatureDomain.CONNECTIVITY -> CatalogCategory("connectivity", TextR.string.category_connectivity, TextR.string.category_connectivity_subtitle, 40)
    FeatureDomain.DATE_TIME -> CatalogCategory("date_time", TextR.string.category_date_time, TextR.string.category_date_time_subtitle, 50)
    FeatureDomain.DEVICE -> when (kind) {
        FeatureKind.ACTION -> CatalogCategory("device", TextR.string.category_device_actions, TextR.string.category_device_actions_subtitle, 60)
        FeatureKind.EVENT -> CatalogCategory("device", TextR.string.category_device_events, TextR.string.category_device_events_subtitle, 60)
        FeatureKind.STATE, FeatureKind.CONDITION -> CatalogCategory("device", TextR.string.category_device_state, TextR.string.category_device_state_subtitle, 60)
    }
    FeatureDomain.DISPLAY -> CatalogCategory("display", TextR.string.category_display, TextR.string.category_display_subtitle, 70)
    FeatureDomain.AUDIO_MEDIA -> CatalogCategory("audio_media", TextR.string.category_audio_media, TextR.string.category_audio_media_subtitle, 80)
    FeatureDomain.NOTIFICATIONS -> CatalogCategory("notifications", TextR.string.category_notifications, TextR.string.category_notifications_subtitle, 90)
    FeatureDomain.LOCATION -> CatalogCategory("location", TextR.string.category_location, TextR.string.category_location_subtitle, 100)
    FeatureDomain.SENSORS -> CatalogCategory("sensors", TextR.string.category_sensors, TextR.string.category_sensors_subtitle, 110)
    FeatureDomain.USER_INPUT -> CatalogCategory("user_input", TextR.string.category_user_input, TextR.string.category_user_input_subtitle, 120)
    FeatureDomain.CAPTURE -> CatalogCategory("capture", TextR.string.category_capture, TextR.string.category_capture_subtitle, 130)
    FeatureDomain.FILES_STORAGE -> CatalogCategory("files_storage", TextR.string.category_files_storage, TextR.string.category_files_storage_subtitle, 140)
    FeatureDomain.DATA -> CatalogCategory("data", TextR.string.category_data, TextR.string.category_data_subtitle, 150)
    FeatureDomain.FLOW_LOGIC -> CatalogCategory("flow_logic", TextR.string.category_flow_logic, TextR.string.category_flow_logic_subtitle, 160)
    FeatureDomain.WEB_NETWORK -> CatalogCategory("web_network", TextR.string.category_web_network, TextR.string.category_web_network_subtitle, 170)
    FeatureDomain.AI -> CatalogCategory("ai", TextR.string.category_ai, TextR.string.category_ai_subtitle, 180)
    FeatureDomain.SCRIPT_COMMANDS -> CatalogCategory("script_commands", TextR.string.category_script_commands, TextR.string.category_script_commands_subtitle, 190)
    FeatureDomain.YAUTO -> CatalogCategory("yauto", TextR.string.category_yauto, TextR.string.category_yauto_subtitle, 200)
}

private fun normalizeQuery(value: String): String = value.trim().lowercase(Locale.ROOT)
