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
        val families = entries.filterIsInstance<FeaturePickerListEntry.Family>()
            .associateBy { it.family.spec.id }

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
            families.getValue("audio_playback").family.members
                .map { it.descriptor.id.value }
                .toSet(),
        )
        assertTrue(
            families.getValue("wifi_tools").family.members
                .map { it.descriptor.id.value }
                .containsAll(listOf("android.wifi.network.connect", "android.wifi.network.disconnect"))
        )
    }

    @Test
    fun `connectivity and power state families collapse only concrete same-kind entries`() {
        val model = FeaturePickerCatalogModel.create(
            listOf(
                item("android.state.wifi_network", "Wi-Fi network", app, "wifi network"),
                item("android.state.wifi_scan_match", "Wi-Fi scan match", app, "wifi scan"),
                item("android.state.bluetooth_enabled", "Bluetooth enabled", app, "bluetooth enabled"),
                item("android.state.bluetooth_device_bonded", "Bluetooth bonded", app, "bluetooth bonded"),
                item("android.state.usb_device_connected", "USB connected", app, "usb connected"),
                item("android.state.nfc_enabled", "NFC enabled", app, "nfc enabled"),
                item("android.state.network", "Network type", app, "network type"),
                item("android.state.vpn_active", "VPN active", app, "vpn active"),
                item("android.state.battery_level", "Battery level", app, "battery level"),
                item("android.state.battery_status", "Battery status", app, "battery status"),
                item("android.state.charging", "Charging", app, "charging"),
                item("android.state.power_save", "Power save", app, "power save"),
            ),
            Comparator.naturalOrder(),
        )

        val entries = model.entries(
            PickerPage.Features(app),
            favorites = emptySet(),
            recent = emptyList(),
            query = "",
        )
        val families = entries.filterIsInstance<FeaturePickerListEntry.Family>()
            .associateBy { it.family.spec.id }

        assertEquals(
            setOf(
                "wifi_states",
                "bluetooth_states",
                "usb_nfc_states",
                "network_states",
                "battery_states",
                "power_states",
            ),
            families.keys,
        )
        assertEquals(
            setOf("android.state.wifi_network", "android.state.wifi_scan_match"),
            families.getValue("wifi_states").family.members
                .map { it.descriptor.id.value }
                .toSet(),
        )
        assertEquals(
            setOf("android.state.usb_device_connected", "android.state.nfc_enabled"),
            families.getValue("usb_nfc_states").family.members
                .map { it.descriptor.id.value }
                .toSet(),
        )
    }

    @Test
    fun `connectivity and battery event families collapse during event browsing`() {
        val model = FeaturePickerCatalogModel.create(
            listOf(
                item("android.event.wifi_changed", "Wi-Fi changed", app, "wifi changed"),
                item("android.event.wifi_scan_results", "Wi-Fi scan results", app, "wifi scan results"),
                item("android.event.bluetooth_state", "Bluetooth state", app, "bluetooth state"),
                item("android.event.bluetooth_device_found", "Bluetooth found", app, "bluetooth found"),
                item("android.event.usb_device_changed", "USB changed", app, "usb changed"),
                item("android.event.nfc_tag", "NFC tag", app, "nfc tag"),
                item("android.event.battery_changed", "Battery changed", app, "battery changed"),
                item("android.event.battery_status_filtered", "Battery status", app, "battery status"),
            ),
            Comparator.naturalOrder(),
        )

        val entries = model.entries(
            PickerPage.Features(app),
            favorites = emptySet(),
            recent = emptyList(),
            query = "",
        )
        val familyIds = entries.filterIsInstance<FeaturePickerListEntry.Family>()
            .map { it.family.spec.id }
            .toSet()

        assertEquals(
            setOf("wifi_events", "bluetooth_events", "usb_nfc_events", "battery_events"),
            familyIds,
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
