package com.yagay.yauto.ui.editor

import com.yagay.yauto.core.model.Stability
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeaturePickerCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeaturePickerCatalogModelTest {
    private val core = CatalogCategory("core", 1, 2, 10)
    private val app = CatalogCategory("app", 3, 4, 20)

    @Test fun `compatibility action is selectable except when deprecated`() {
        val descriptor = FeatureDescriptor(
            id = FeatureId("android.plugin.locale.action"),
            kind = FeatureKind.ACTION,
            title = "Locale plugin",
            description = "Installed action",
            category = FeatureCategory.COMPATIBILITY,
        )
        assertTrue(descriptor.isPickerSelectable())
        assertTrue(!descriptor.copy(stability = Stability.DEPRECATED).isPickerSelectable())
    }

    @Test fun `verified merges use upstream identical feature names and stable IDs`() {
        assertEquals(setOf("clipboard_write", "clipboard_read", "screenshot_capture"),
            UNIFIED_FEATURE_SPECS.map { it.id }.toSet())
        assertEquals(listOf("android.clipboard.set", "android.clipboard.write"),
            UNIFIED_FEATURE_SPECS.single { it.id == "clipboard_write" }.memberIds)
        assertEquals(listOf("android.clipboard.get", "android.clipboard.read"),
            UNIFIED_FEATURE_SPECS.single { it.id == "clipboard_read" }.memberIds)
        assertEquals(listOf("android.screen.screenshot", "android.screenshot.capture"),
            UNIFIED_FEATURE_SPECS.single { it.id == "screenshot_capture" }.memberIds)
        assertEquals(6, UNIFIED_FEATURE_SPECS.flatMap { it.memberIds }.distinct().size)
    }

    @Test fun `independent MacroDroid volume actions are separate picker options`() {
        val ids = listOf("android.audio.volume.set", "android.audio.volume.adjust",
            "android.audio.playback_volume.set", "android.audio.ringer_mode.set")
        val model = build(ids)
        assertEquals(4, model.entries(PickerPage.Features(app), emptySet(), emptyList(), "").size)
        ids.forEach { assertNull(model.unifiedGroupForMember(it)) }
        assertEquals(4, model.categoryCount("app"))
    }

    @Test fun `unrelated app actions remain selectable on their own`() {
        val ids = listOf("android.app.enabled.set", "android.app.background.kill",
            "android.app.suspended.set", "android.app.standby_bucket.set")
        val model = build(ids)
        assertEquals(4, model.entries(PickerPage.Features(app), emptySet(), emptyList(), "").size)
        assertTrue(model.entries(PickerPage.Features(app), emptySet(), emptyList(), "")
            .all { it is FeaturePickerListEntry.Feature })
    }

    @Test fun `battery constraints are not one giant merged feature`() {
        val ids = listOf("android.condition.charging", "android.condition.battery_level",
            "android.condition.battery_health", "android.condition.battery_temperature")
        val model = build(ids, FeatureKind.CONDITION)
        assertEquals(4, model.entries(PickerPage.Features(app), emptySet(), emptyList(), "").size)
        ids.forEach { assertNull(model.unifiedGroupForMember(it)) }
    }

    @Test fun `independent system event triggers are not hidden in family menus`() {
        val ids = listOf("android.event.hardware_key", "android.event.hardware_key_combo",
            "android.event.hardware_key_gesture")
        val model = build(ids, FeatureKind.EVENT)
        assertEquals(3, model.entries(PickerPage.Features(app), emptySet(), emptyList(), "").size)
    }

    @Test fun `same MacroDroid screenshot action supports two concrete implementations`() {
        val ids = listOf("android.screen.screenshot", "android.screenshot.capture")
        val model = build(ids)
        val group = (model.entries(PickerPage.Features(app), emptySet(), emptyList(), "")
            .single() as FeaturePickerListEntry.Unified).group
        assertEquals("screenshot_capture", group.spec.id)
        assertEquals(ids, group.members.map { it.descriptor.id.value })
        assertEquals("android.screenshot.capture", resolveUnifiedMemberId(
            group, "android.screenshot.capture", "android.screen.screenshot"))
    }

    @Test fun `clipboard writes and reads never merge together`() {
        val ids = listOf("android.clipboard.set", "android.clipboard.write",
            "android.clipboard.get", "android.clipboard.read")
        val model = build(ids)
        val entries = model.entries(PickerPage.Features(app), emptySet(), emptyList(), "")
        assertEquals(2, entries.size)
        val groups = entries.map { (it as FeaturePickerListEntry.Unified).group.spec.id }.toSet()
        assertEquals(setOf("clipboard_write", "clipboard_read"), groups)
        assertEquals(2, model.categoryCount("app"))
    }

    @Test fun `cross category screenshot implementations never mix semantic categories`() {
        val model = FeaturePickerCatalogModel.create(listOf(
            item("android.screen.screenshot", app),
            item("android.screenshot.capture", core),
        ), Comparator.naturalOrder())
        assertEquals(2, model.allItems.size)
        assertEquals(1, model.categoryCount("app"))
        assertEquals(1, model.categoryCount("core"))
        assertTrue(model.entries(PickerPage.Features(app), emptySet(), emptyList(), "")
            .single() is FeaturePickerListEntry.Feature)
        assertTrue(model.entries(PickerPage.Features(core), emptySet(), emptyList(), "")
            .single() is FeaturePickerListEntry.Feature)
    }

    @Test fun `favorites recent and search keep preferred concrete clipboard operation`() {
        val model = build(listOf("android.clipboard.set", "android.clipboard.write"))
        val favorite = model.entries(PickerPage.Features(app, special = "favorites"),
            setOf("android.clipboard.write"), emptyList(), "")
            .single() as FeaturePickerListEntry.Unified
        assertEquals("android.clipboard.write", favorite.preferredMemberId)
        val recent = model.entries(PickerPage.Features(app, special = "recent"),
            emptySet(), listOf("android.clipboard.set"), "")
            .single() as FeaturePickerListEntry.Unified
        assertEquals("android.clipboard.set", recent.preferredMemberId)
        val match = model.searchEntries("write").single() as FeaturePickerListEntry.Unified
        assertEquals("android.clipboard.write", match.preferredMemberId)
    }

    @Test fun `MacroDroid category order still depends on feature kind`() {
        val appAction = catalogCategory(FeaturePickerCategory.APPLICATIONS, FeatureKind.ACTION)
        val fileAction = catalogCategory(FeaturePickerCategory.FILES, FeatureKind.ACTION)
        val sensorEvent = catalogCategory(FeaturePickerCategory.SENSORS, FeatureKind.EVENT)
        val inputEvent = catalogCategory(FeaturePickerCategory.USER_INPUT, FeatureKind.EVENT)
        assertTrue(appAction.order < fileAction.order)
        assertTrue(sensorEvent.order < inputEvent.order)
    }

    @Test fun `unrelated features sort independently with stable category counts`() {
        val model = FeaturePickerCatalogModel.create(listOf(
            item("a", core, "Alpha"), item("z", app, "Zulu"), item("b", app, "Beta")
        ), Comparator.naturalOrder())
        assertEquals(listOf("Alpha", "Beta", "Zulu"), model.allItems.map { it.title })
        assertEquals(1, model.categoryCount("core"))
        assertEquals(2, model.categoryCount("app"))
    }

    private fun build(
        ids: List<String>, kind: FeatureKind = FeatureKind.ACTION,
    ): FeaturePickerCatalogModel = FeaturePickerCatalogModel.create(
        ids.map { item(it, app, kind = kind) }, Comparator.naturalOrder())

    private fun item(
        id: String, category: CatalogCategory, title: String = id,
        kind: FeatureKind = FeatureKind.ACTION,
    ): FeaturePickerCatalogItem {
        val descriptor = FeatureDescriptor(
            id = FeatureId(id), kind = kind, title = title, description = title,
            category = if (category.id == "core") FeatureCategory.CORE else FeatureCategory.APP,
        )
        return FeaturePickerCatalogItem(descriptor, title, title, category,
            (title + " " + id).lowercase())
    }
}
