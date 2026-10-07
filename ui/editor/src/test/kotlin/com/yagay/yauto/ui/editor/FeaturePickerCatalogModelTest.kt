package com.yagay.yauto.ui.editor

import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeaturePickerCatalogModelTest {
    private val core = CatalogCategory("core", 1, 2, 10)
    private val app = CatalogCategory("app", 3, 4, 20)

    @Test
    fun `catalog sorts once and exposes stable category counts`() {
        val model = FeaturePickerCatalogModel.create(
            listOf(
                item("z", "Zulu", app, "zulu app"),
                item("a", "Alpha", core, "alpha core"),
                item("b", "Beta", app, "beta app"),
            ),
            Comparator.naturalOrder(),
        )

        assertEquals(listOf("Alpha", "Beta", "Zulu"), model.allItems.map { it.title })
        assertEquals(listOf("core", "app"), model.categories.map { it.id })
        assertEquals(1, model.categoryCount("core"))
        assertEquals(2, model.categoryCount("app"))
    }

    @Test
    fun `related actions collapse into one family during normal browsing`() {
        val model = FeaturePickerCatalogModel.create(
            listOf(
                item("android.audio.volume.set", "Set volume", app, "set volume audio"),
                item("android.audio.volume.adjust", "Adjust volume", app, "adjust volume audio"),
            ),
            Comparator.naturalOrder(),
        )
        val page = PickerPage.Features(app)

        val entries = model.entries(page, favorites = emptySet(), recent = emptyList(), query = "")

        assertEquals(1, entries.size)
        assertTrue(entries.single() is FeaturePickerListEntry.Family)
        val family = (entries.single() as FeaturePickerListEntry.Family).family
        assertEquals("volume", family.spec.id)
        assertEquals(
            listOf("android.audio.volume.set", "android.audio.volume.adjust"),
            family.members.map { it.descriptor.id.value },
        )
        assertEquals(1, model.categoryCount("app"))

        val searchEntries = model.entries(page, favorites = emptySet(), recent = emptyList(), query = "adjust")
        assertEquals(1, searchEntries.size)
        assertTrue(searchEntries.single() is FeaturePickerListEntry.Feature)
        assertEquals(
            "android.audio.volume.adjust",
            (searchEntries.single() as FeaturePickerListEntry.Feature).item.descriptor.id.value,
        )
    }

    @Test
    fun `second batch app controls collapse without changing concrete ids`() {
        val model = FeaturePickerCatalogModel.create(
            listOf(
                item("android.app.enabled.set", "Set app enabled", app, "app enabled"),
                item("android.app.background.kill", "Kill background app", app, "app background kill"),
                item("android.app.suspended.set", "Set app suspended", app, "app suspended"),
            ),
            Comparator.naturalOrder(),
        )

        val entries = model.entries(
            PickerPage.Features(app),
            favorites = emptySet(),
            recent = emptyList(),
            query = "",
        )

        assertEquals(1, entries.size)
        val family = (entries.single() as FeaturePickerListEntry.Family).family
        assertEquals("app_state_control", family.spec.id)
        assertEquals(
            setOf(
                "android.app.enabled.set",
                "android.app.background.kill",
                "android.app.suspended.set",
            ),
            family.members.map { it.descriptor.id.value }.toSet(),
        )
    }

    @Test
    fun `search favorites and recent use prebuilt indexes without reordering recent`() {
        val alpha = item("a", "Alpha", app, "alpha launch browser")
        val beta = item("b", "Beta", app, "beta network wifi")
        val gamma = item("c", "Gamma", app, "gamma clipboard")
        val model = FeaturePickerCatalogModel.create(
            listOf(gamma, beta, alpha),
            Comparator.naturalOrder(),
        )

        assertEquals(listOf("Beta"), model.search("WIFI").map { it.title })

        val favoritePage = PickerPage.Features(app, special = "favorites")
        assertEquals(
            listOf("Alpha", "Gamma"),
            model.items(favoritePage, favorites = setOf("c", "a"), recent = emptyList(), query = "")
                .map { it.title },
        )

        val recentPage = PickerPage.Features(app, special = "recent")
        assertEquals(
            listOf("Gamma", "Alpha"),
            model.items(recentPage, favorites = emptySet(), recent = listOf("c", "a"), query = "")
                .map { it.title },
        )
    }

    private fun item(
        id: String,
        title: String,
        category: CatalogCategory,
        searchIndex: String,
    ): FeaturePickerCatalogItem {
        val descriptor = FeatureDescriptor(
            id = FeatureId(id),
            kind = FeatureKind.ACTION,
            title = title,
            description = title,
            category = if (category.id == "core") FeatureCategory.CORE else FeatureCategory.APP,
        )
        return FeaturePickerCatalogItem(
            descriptor = descriptor,
            title = title,
            description = title,
            category = category,
            searchIndex = searchIndex.lowercase(),
        )
    }
}
