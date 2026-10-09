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
        assertEquals(34, UNIFIED_FEATURE_SPECS.size)
        assertTrue(UNIFIED_FEATURE_SPECS.map { it.id }.containsAll(
            listOf("clipboard_write", "clipboard_read", "screenshot_capture",
                "stopwatch", "file_operations", "device_power", "audio_recording",
                "screen_power_events", "package_install_events", "notification_received_cleared")))
        assertEquals(listOf("android.clipboard.set", "android.clipboard.write"),
            UNIFIED_FEATURE_SPECS.single { it.id == "clipboard_write" }.memberIds)
        assertEquals(listOf("android.clipboard.get", "android.clipboard.read"),
            UNIFIED_FEATURE_SPECS.single { it.id == "clipboard_read" }.memberIds)
        assertEquals(listOf("android.screen.screenshot", "android.screenshot.capture"),
            UNIFIED_FEATURE_SPECS.single { it.id == "screenshot_capture" }.memberIds)
        assertEquals(73, UNIFIED_FEATURE_SPECS.flatMap { it.memberIds }.distinct().size)
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

    @Test fun `audio mode has one title and two parameterized detection methods`() {
        val model = build(listOf("android.condition.audio.mode",
            "android.condition.reference.audio_mode"), FeatureKind.CONDITION)
        val entries = model.entries(PickerPage.Features(app), emptySet(), emptyList(), "")
        assertEquals(1, entries.size)
        val group = (entries.single() as FeaturePickerListEntry.Unified).group
        assertEquals("audio_mode_condition", group.spec.id)
        assertEquals("android.condition.reference.audio_mode",
            resolveUnifiedMemberId(group, "android.condition.reference.audio_mode", null))
    }

    @Test fun `same DND system filter has one picker entry with two methods`() {
        val model = build(listOf("android.condition.dnd_filter",
            "android.condition.reference_signal.dnd_filter"), FeatureKind.CONDITION)
        val entries = model.entries(PickerPage.Features(app), emptySet(), emptyList(), "")
        assertEquals(1, entries.size)
        assertEquals("dnd_filter_condition",
            (entries.single() as FeaturePickerListEntry.Unified).group.spec.id)
    }

    @Test fun `per app language is one name with distinct implementation settings`() {
        val model = build(listOf("android.app.locale.set", "android.locale.app.set"))
        val entries = model.entries(PickerPage.Features(app), emptySet(), emptyList(), "")
        assertEquals(1, entries.size)
        val group = (entries.single() as FeaturePickerListEntry.Unified).group
        assertEquals("app_language", group.spec.id)
        assertEquals(2, group.members.size)
    }

    @Test fun `flashlight state is one entry with per camera or aggregate method`() {
        val model = build(listOf("android.state.torch_on",
            "android.state.reference.signal.torch_enabled"), FeatureKind.STATE)
        val entries = model.entries(PickerPage.Features(app), emptySet(), emptyList(), "")
        assertEquals(1, entries.size)
        val group = (entries.single() as FeaturePickerListEntry.Unified).group
        assertEquals("torch_status_state", group.spec.id)
    }

    @Test fun `power menu is one picker option with accessibility or privileged method`() {
        val ids = listOf("android.global_actions.show", "android.device.power_menu.show")
        val model = build(ids)
        val rows = model.entries(PickerPage.Features(app), emptySet(), emptyList(), "")
        assertEquals(1, rows.size)
        val group = (rows.single() as FeaturePickerListEntry.Unified).group
        assertEquals("power_menu_access_method", group.spec.id)
        assertEquals(ids.toSet(), group.members.map { it.descriptor.id.value }.toSet())
    }

    @Test fun `one MacroDroid file operation has multiple selected modes`() {
        val ids = listOf("file.copy", "file.move", "file.delete", "file.mkdir", "file.list")
        val model = build(ids)
        val family = (model.entries(PickerPage.Features(app), emptySet(), emptyList(), "")
            .single() as FeaturePickerListEntry.Unified).group
        assertEquals("file_operations", family.spec.id)
        assertEquals(ids.toSet(), family.members.map { it.descriptor.id.value }.toSet())
    }

    @Test fun `MacroDroid combined notification trigger is distinct from notification updates`() {
        val ids = listOf("android.event.notification_posted",
            "android.event.notification_removed", "android.event.notification_updated")
        val model = build(ids, FeatureKind.EVENT)
        val entries = model.entries(PickerPage.Features(app), emptySet(), emptyList(), "")
        assertEquals(2, entries.size)
        assertTrue(entries.any { it is FeaturePickerListEntry.Unified })
        assertTrue(entries.any { it is FeaturePickerListEntry.Feature &&
            it.item.descriptor.id.value == "android.event.notification_updated" })
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

    @Test fun `cross category implementations appear once under verified primary category`() {
        val model = FeaturePickerCatalogModel.create(listOf(
            item("android.screen.screenshot", app, "Take screenshot"),
            item("android.screenshot.capture", core, "Alternate capture"),
        ), Comparator.naturalOrder())
        // Underlying descriptors retain their own original semantic category.
        assertEquals(2, model.allItems.size)
        assertEquals("core", model.item("android.screenshot.capture")?.category?.id)
        assertEquals(1, model.categoryCount("app"))
        assertEquals(0, model.categoryCount("core"))
        assertEquals(listOf("app"), model.categories.map { it.id })
        val entry = model.entries(PickerPage.Features(app), emptySet(), emptyList(), "")
            .single() as FeaturePickerListEntry.Unified
        assertEquals("screenshot_capture", entry.group.spec.id)
        assertEquals(2, entry.group.members.size)
        assertEquals(emptyList<FeaturePickerListEntry>(),
            model.entries(PickerPage.Features(core), emptySet(), emptyList(), ""))
        // Searching either method still yields the SAME picker row.
        val byAlternateName = model.entries(
            PickerPage.Features(app), emptySet(), emptyList(), "alternate"
        ).single() as FeaturePickerListEntry.Unified
        assertEquals("android.screenshot.capture", byAlternateName.preferredMemberId)
        assertEquals(1, model.searchEntries("alternate").size)
        assertEquals(1, model.favoriteCount(setOf("android.screenshot.capture")))
        assertEquals(1, model.recentCount(listOf("android.screenshot.capture")))
        // Editing an existing rule must select its saved concrete method.
        assertEquals("android.screenshot.capture", resolveUnifiedMemberId(
            entry.group, "android.screenshot.capture", "android.screen.screenshot"))
    }

    @Test fun `same name without verified source evidence stays two independent operations`() {
        val model = FeaturePickerCatalogModel.create(listOf(
            item("android.audio.volume.set", app, "Volume"),
            item("android.audio.volume.adjust", core, "Volume"),
        ), Comparator.naturalOrder())
        assertEquals(1, model.categoryCount("app"))
        assertEquals(1, model.categoryCount("core"))
        assertNull(model.unifiedGroupForMember("android.audio.volume.set"))
        assertNull(model.unifiedGroupForMember("android.audio.volume.adjust"))
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
