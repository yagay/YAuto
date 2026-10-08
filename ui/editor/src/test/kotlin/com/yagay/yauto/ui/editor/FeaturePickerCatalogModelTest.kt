package com.yagay.yauto.ui.editor

import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeaturePickerCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeaturePickerCatalogModelTest {
    private val core = CatalogCategory("core", 1, 2, 10)
    private val app = CatalogCategory("app", 3, 4, 20)

    @Test
    fun `the picker uses a different category order for each feature kind`() {
        val appAction = catalogCategory(FeaturePickerCategory.APPLICATIONS, FeatureKind.ACTION)
        val fileAction = catalogCategory(FeaturePickerCategory.FILES, FeatureKind.ACTION)
        val sensorEvent = catalogCategory(FeaturePickerCategory.SENSORS, FeatureKind.EVENT)
        val inputEvent = catalogCategory(FeaturePickerCategory.USER_INPUT, FeatureKind.EVENT)
        val batteryCondition = catalogCategory(FeaturePickerCategory.BATTERY_POWER, FeatureKind.CONDITION)
        val screenCondition = catalogCategory(FeaturePickerCategory.SCREEN, FeatureKind.CONDITION)
        assertTrue(appAction.order < fileAction.order)
        assertTrue(sensorEvent.order < inputEvent.order)
        assertTrue(batteryCondition.order < screenCondition.order)
        assertEquals("screen", screenCondition.id)
        assertEquals("applications", appAction.id)
    }

    @Test
    fun `network and speaker families stay separate across MacroDroid categories`() {
        val networkInfo = UNIFIED_FEATURE_SPECS.single { it.id == "network_information" }
        val networkTransport = UNIFIED_FEATURE_SPECS.single { it.id == "network_transport" }
        val speakers = UNIFIED_FEATURE_SPECS.single { it.id == "speaker_checks" }
        val audio = UNIFIED_FEATURE_SPECS.single { it.id == "audio_checks" }
        assertEquals(listOf("android.network.dns.resolve", "android.network.local_addresses"), networkInfo.memberIds)
        assertEquals(listOf("android.network.udp.send", "android.network.tcp.wait"), networkTransport.memberIds)
        assertEquals(listOf("android.state.audio.speakerphone", "android.condition.audio.speakerphone"), speakers.memberIds)
        assertTrue(audio.memberIds.none { it in speakers.memberIds })
    }

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
        assertTrue(entries.single() is FeaturePickerListEntry.Unified)
        val family = (entries.single() as FeaturePickerListEntry.Unified).group
        assertEquals("volume", family.spec.id)
        assertEquals(
            listOf("android.audio.volume.set", "android.audio.volume.adjust"),
            family.members.map { it.descriptor.id.value },
        )
        assertEquals(1, model.categoryCount("app"))
        assertEquals(
            "volume",
            model.unifiedGroupForMember("android.audio.volume.adjust")?.spec?.id,
        )

        val searchEntries = model.entries(page, favorites = emptySet(), recent = emptyList(), query = "adjust")
        assertEquals(1, searchEntries.size)
        assertTrue(searchEntries.single() is FeaturePickerListEntry.Unified)
        assertEquals(
            "volume",
            (searchEntries.single() as FeaturePickerListEntry.Unified).group.spec.id,
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
        val family = (entries.single() as FeaturePickerListEntry.Unified).group
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
    fun `third batch families include complete playback wifi and utility operations`() {
        val model = FeaturePickerCatalogModel.create(
            listOf(
                item("android.audio.play", "Play", app, "play"),
                item("android.audio.pause", "Pause", app, "pause"),
                item("android.audio.resume", "Resume", app, "resume"),
                item("android.audio.stop", "Stop", app, "stop"),
                item("android.audio.seek", "Seek", app, "seek"),
                item("android.wifi.connection.info", "Wi-Fi info", app, "wifi info"),
                item("android.wifi.scan.start", "Start Wi-Fi scan", app, "wifi scan"),
                item("android.wifi.network.connect", "Connect Wi-Fi", app, "wifi connect"),
                item("android.wifi.network.disconnect", "Disconnect Wi-Fi", app, "wifi disconnect"),
                item("android.bluetooth.discovery.start", "Start Bluetooth discovery", app, "bluetooth discover"),
                item("android.bluetooth.discovery.stop", "Stop Bluetooth discovery", app, "bluetooth stop"),
                item("android.bluetooth.paired.query", "Paired Bluetooth devices", app, "bluetooth paired"),
                item("android.audio.focus.request", "Request audio focus", app, "audio focus request"),
                item("android.audio.focus.abandon", "Abandon audio focus", app, "audio focus abandon"),
                item("android.ime.picker.show", "IME picker", app, "ime picker"),
                item("android.ime.default.set", "Set IME", app, "ime default"),
                item("android.ime.settings.open", "IME settings", app, "ime settings"),
            ),
            Comparator.naturalOrder(),
        )

        val entries = model.entries(
            PickerPage.Features(app),
            favorites = emptySet(),
            recent = emptyList(),
            query = "",
        )
        val families = entries.filterIsInstance<FeaturePickerListEntry.Unified>()
            .associateBy { it.group.spec.id }

        assertEquals(
            setOf("audio_playback", "wifi_tools", "bluetooth_devices", "audio_focus", "input_method"),
            families.keys,
        )
        assertEquals(
            setOf(
                "android.audio.play",
                "android.audio.pause",
                "android.audio.resume",
                "android.audio.stop",
                "android.audio.seek",
            ),
            families.getValue("audio_playback").group.members
                .map { it.descriptor.id.value }
                .toSet(),
        )
        assertTrue(
            families.getValue("wifi_tools").group.members
                .map { it.descriptor.id.value }
                .containsAll(listOf("android.wifi.network.connect", "android.wifi.network.disconnect"))
        )
    }

    @Test
    fun `cross category operations get one canonical category and remain editable`() {
        val model = FeaturePickerCatalogModel.create(
            listOf(
                item("android.qs_tile.click", "Tile click", app, "tile click"),
                item("android.qs_tile.info", "Tile info", core, "tile info"),
                item("android.qs_tile.configure", "Tile configure", app, "tile configure"),
            ),
            Comparator.naturalOrder(),
        )
        val entries = model.entries(
            PickerPage.Features(app), emptySet(), emptyList(), "",
        )
        assertEquals(1, entries.size)
        assertEquals("quick_settings_tile", (entries.single() as FeaturePickerListEntry.Unified).group.spec.id)
        assertEquals(3, model.unifiedGroupForMember("android.qs_tile.info")?.members?.size)
        assertEquals(1, model.categoryCount("app"))
        assertEquals(0, model.categoryCount("core"))
        assertTrue(model.categories.none { it.id == "core" })
    }

    @Test
    fun `search favorites and recent keep the same unified entry`() {
        val model = FeaturePickerCatalogModel.create(
            listOf(
                item("android.audio.volume.set", "Set volume", app, "volume set"),
                item("android.audio.volume.adjust", "Adjust volume", app, "volume adjust"),
            ),
            Comparator.naturalOrder(),
        )
        assertEquals(1, model.favoriteCount(setOf(
            "android.audio.volume.set", "android.audio.volume.adjust",
        )))
        assertEquals(1, model.recentCount(listOf(
            "android.audio.volume.set", "android.audio.volume.adjust",
        )))
        assertEquals(1, model.searchEntries("volume").size)
        assertTrue(model.searchEntries("volume").single() is FeaturePickerListEntry.Unified)
        assertEquals(1, model.entries(
            PickerPage.Features(app, special = "favorites"),
            setOf("android.audio.volume.set", "android.audio.volume.adjust"), emptyList(), "",
        ).size)
        assertEquals(1, model.entries(
            PickerPage.Features(app, special = "recent"),
            emptySet(), listOf("android.audio.volume.set", "android.audio.volume.adjust"), "",
        ).size)
    }

    @Test
    fun `unified operation restoration prefers existing concrete id then requested operation`() {
        val first = item("android.audio.volume.set", "Set volume", app, "set volume")
        val second = item("android.audio.volume.adjust", "Adjust volume", app, "adjust volume")
        val group = UnifiedFeatureGroup(
            spec = UnifiedFeatureSpec(
                id = "volume",
                titleRes = 1,
                subtitleRes = 2,
                memberIds = listOf(first.descriptor.id.value, second.descriptor.id.value),
            ),
            members = listOf(first, second),
        )

        assertEquals(
            "android.audio.volume.adjust",
            resolveUnifiedMemberId(
                group,
                initialTypeId = "android.audio.volume.adjust",
                requestedMemberId = "android.audio.volume.set",
            ),
        )
        assertEquals(
            "android.audio.volume.adjust",
            resolveUnifiedMemberId(
                group,
                initialTypeId = null,
                requestedMemberId = "android.audio.volume.adjust",
            ),
        )
        assertEquals(
            "android.audio.volume.set",
            resolveUnifiedMemberId(
                group,
                initialTypeId = null,
                requestedMemberId = "missing",
            ),
        )
    }

    @Test
    fun `event concepts collapse hardware key trigger variants into one entry`() {
        val model = FeaturePickerCatalogModel.create(
            listOf(
                item("android.event.hardware_key", "Hardware key", app, "hardware key", FeatureKind.EVENT),
                item("android.event.hardware_key_gesture", "Hardware key gesture", app, "hardware key gesture", FeatureKind.EVENT),
                item("android.event.hardware_key_combo", "Hardware key combo", app, "hardware key combo", FeatureKind.EVENT),
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
        val group = (entries.single() as FeaturePickerListEntry.Unified).group
        assertEquals("hardware_key_triggers", group.spec.id)
        assertTrue(group.members.all { it.descriptor.kind == FeatureKind.EVENT })
    }

    @Test
    fun `condition concepts collapse battery checks without mixing state kind`() {
        val model = FeaturePickerCatalogModel.create(
            listOf(
                item("android.condition.charging", "Charging", app, "charging", FeatureKind.CONDITION),
                item("android.condition.battery_level", "Battery level", app, "battery level", FeatureKind.CONDITION),
                item("android.condition.battery_health", "Battery health", app, "battery health", FeatureKind.CONDITION),
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
        val group = (entries.single() as FeaturePickerListEntry.Unified).group
        assertEquals("battery_checks", group.spec.id)
        assertTrue(group.members.all { it.descriptor.kind == FeatureKind.CONDITION })
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
        kind: FeatureKind = FeatureKind.ACTION,
    ): FeaturePickerCatalogItem {
        val descriptor = FeatureDescriptor(
            id = FeatureId(id),
            kind = kind,
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
