package com.yagay.yauto.ui.editor

import androidx.annotation.StringRes
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
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
    private val categoryCounts: Map<String, Int>,
) {
    fun item(id: String): FeaturePickerCatalogItem? = byId[id]

    fun contains(id: String): Boolean = id in byId

    fun categoryCount(categoryId: String): Int = categoryCounts[categoryId] ?: 0

    fun search(query: String): List<FeaturePickerCatalogItem> {
        val needle = normalizeQuery(query)
        if (needle.isEmpty()) return emptyList()
        return allItems.filter { needle in it.searchIndex }
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
            return FeaturePickerCatalogModel(
                allItems = sorted,
                categories = categories,
                byId = sorted.associateBy { it.descriptor.id.value },
                byCategory = sorted.groupBy { it.category.id },
                categoryCounts = sorted.groupingBy { it.category.id }.eachCount(),
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
            category = catalogCategory(descriptor.category),
            searchIndex = textResolver.searchText(descriptor).lowercase(Locale.ROOT),
        )
    },
    titleComparator = titleComparator,
)

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

private fun normalizeQuery(value: String): String = value.trim().lowercase(Locale.ROOT)
