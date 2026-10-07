package com.yagay.yauto.ui.editor

import androidx.annotation.StringRes
import com.yagay.yauto.ui.design.R as TextR

internal data class FeatureFamilySpec(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    val memberIds: List<String>,
)

internal data class FeaturePickerFamily(
    val spec: FeatureFamilySpec,
    val members: List<FeaturePickerCatalogItem>,
)

internal sealed interface FeaturePickerListEntry {
    data class Feature(val item: FeaturePickerCatalogItem) : FeaturePickerListEntry
    data class Family(val family: FeaturePickerFamily) : FeaturePickerListEntry
}

private val FEATURE_FAMILY_SPECS = listOf(
    FeatureFamilySpec(
        "stopwatch",
        TextR.string.feature_family_stopwatch,
        TextR.string.feature_family_stopwatch_subtitle,
        listOf(
            "android.stopwatch.start",
            "android.stopwatch.pause",
            "android.stopwatch.resume",
            "android.stopwatch.reset",
            "android.stopwatch.stop",
        ),
    ),
    FeatureFamilySpec(
        "volume",
        TextR.string.feature_family_volume,
        TextR.string.feature_family_volume_subtitle,
        listOf("android.audio.volume.set", "android.audio.volume.adjust"),
    ),
    FeatureFamilySpec(
        "websocket",
        TextR.string.feature_family_websocket,
        TextR.string.feature_family_websocket_subtitle,
        listOf(
            "android.websocket.connect",
            "android.websocket.send",
            "android.websocket.messages.get",
            "android.websocket.close",
        ),
    ),
    FeatureFamilySpec(
        "wake_lock",
        TextR.string.feature_family_wake_lock,
        TextR.string.feature_family_wake_lock_subtitle,
        listOf("android.power.wake_lock.acquire", "android.power.wake_lock.release"),
    ),
    FeatureFamilySpec(
        "quick_settings_tile",
        TextR.string.feature_family_quick_settings_tile,
        TextR.string.feature_family_quick_settings_tile_subtitle,
        listOf("android.qs_tile.click", "android.qs_tile.info", "android.qs_tile.configure"),
    ),
    FeatureFamilySpec(
        "notification_history",
        TextR.string.feature_family_notification_history,
        TextR.string.feature_family_notification_history_subtitle,
        listOf(
            "android.notification.history.query",
            "android.notification.restore",
            "android.notification.history.clear",
        ),
    ),
    FeatureFamilySpec(
        "text_file",
        TextR.string.feature_family_text_file,
        TextR.string.feature_family_text_file_subtitle,
        listOf("file.read_text", "file.write_text"),
    ),
    FeatureFamilySpec(
        "file_operations",
        TextR.string.feature_family_file_operations,
        TextR.string.feature_family_file_operations_subtitle,
        listOf("file.delete", "file.mkdir", "file.list", "file.properties"),
    ),
    FeatureFamilySpec(
        "clock",
        TextR.string.feature_family_clock,
        TextR.string.feature_family_clock_subtitle,
        listOf("android.alarm.set", "android.timer.set"),
    ),
    FeatureFamilySpec(
        "matter",
        TextR.string.feature_family_matter,
        TextR.string.feature_family_matter_subtitle,
        listOf("android.matter.commission", "android.matter.light", "android.matter.command"),
    ),
    FeatureFamilySpec(
        "notification_channels",
        TextR.string.feature_family_notification_channels,
        TextR.string.feature_family_notification_channels_subtitle,
        listOf(
            "android.notification.channels.query",
            "android.notification.channel.create",
            "android.notification.channel.delete",
        ),
    ),
    FeatureFamilySpec(
        "notification_channel_groups",
        TextR.string.feature_family_notification_channel_groups,
        TextR.string.feature_family_notification_channel_groups_subtitle,
        listOf(
            "android.notification.channel_groups.query",
            "android.notification.channel_group.create",
            "android.notification.channel_group.delete",
        ),
    ),
    FeatureFamilySpec(
        "clipboard",
        TextR.string.feature_family_clipboard,
        TextR.string.feature_family_clipboard_subtitle,
        listOf(
            "android.clipboard.read",
            "android.clipboard.write",
            "android.clipboard.clear",
        ),
    ),
    FeatureFamilySpec(
        "vibration",
        TextR.string.feature_family_vibration,
        TextR.string.feature_family_vibration_subtitle,
        listOf(
            "android.vibration.vibrate",
            "android.vibration.pattern",
            "android.vibration.cancel",
        ),
    ),
    FeatureFamilySpec(
        "widget",
        TextR.string.feature_family_widget,
        TextR.string.feature_family_widget_subtitle,
        listOf("android.widget.configure", "android.widget.refresh", "android.widget.pin"),
    ),
    FeatureFamilySpec(
        "app_roles",
        TextR.string.feature_family_app_roles,
        TextR.string.feature_family_app_roles_subtitle,
        listOf("android.role.request", "android.role.holder.manage"),
    ),
    FeatureFamilySpec(
        "wifi_tools",
        TextR.string.feature_family_wifi_tools,
        TextR.string.feature_family_wifi_tools_subtitle,
        listOf(
            "android.wifi.connection.info",
            "android.wifi.scan.start",
            "android.wifi.scan.results",
            "android.wifi.network.connect",
            "android.wifi.network.disconnect",
        ),
    ),
    FeatureFamilySpec(
        "bluetooth_events",
        TextR.string.feature_family_bluetooth_events,
        TextR.string.feature_family_bluetooth_events_subtitle,
        listOf(
            "android.event.bluetooth_device_connection",
            "android.event.bluetooth_device_found",
            "android.event.bluetooth_bond_changed",
        ),
    ),
    FeatureFamilySpec(
        "bluetooth_devices",
        TextR.string.feature_family_bluetooth_devices,
        TextR.string.feature_family_bluetooth_devices_subtitle,
        listOf(
            "android.bluetooth.discovery.start",
            "android.bluetooth.discovery.stop",
            "android.bluetooth.paired.query",
        ),
    ),
    FeatureFamilySpec(
        "audio_playback",
        TextR.string.feature_family_audio_playback,
        TextR.string.feature_family_audio_playback_subtitle,
        listOf(
            "android.audio.play",
            "android.audio.pause",
            "android.audio.resume",
            "android.audio.stop",
            "android.audio.seek",
            "android.audio.playback_volume.set",
            "android.audio.playback_info",
        ),
    ),
    FeatureFamilySpec(
        "audio_modes",
        TextR.string.feature_family_audio_modes,
        TextR.string.feature_family_audio_modes_subtitle,
        listOf(
            "android.audio.ringer_mode.set",
            "android.audio.microphone_mute.set",
            "android.audio.speakerphone.set",
        ),
    ),
    FeatureFamilySpec(
        "audio_focus",
        TextR.string.feature_family_audio_focus,
        TextR.string.feature_family_audio_focus_subtitle,
        listOf(
            "android.audio.focus.request",
            "android.audio.focus.abandon",
        ),
    ),
    FeatureFamilySpec(
        "notification_actions",
        TextR.string.feature_family_notification_actions,
        TextR.string.feature_family_notification_actions_subtitle,
        listOf(
            "android.notification.reply",
            "android.notification.dismiss_all",
            "android.notification.query",
            "android.notification.count",
        ),
    ),
    FeatureFamilySpec(
        "app_state_control",
        TextR.string.feature_family_app_state_control,
        TextR.string.feature_family_app_state_control_subtitle,
        listOf(
            "android.app.enabled.set",
            "android.app.background.kill",
            "android.app.suspended.set",
            "android.app.standby_bucket.set",
            "android.app.inactive.set",
        ),
    ),
    FeatureFamilySpec(
        "app_installation",
        TextR.string.feature_family_app_installation,
        TextR.string.feature_family_app_installation_subtitle,
        listOf(
            "android.app.uninstall_user",
            "android.app.install_existing",
        ),
    ),
    FeatureFamilySpec(
        "app_information",
        TextR.string.feature_family_app_information,
        TextR.string.feature_family_app_information_subtitle,
        listOf(
            "android.app.details.open",
            "android.app.package_info",
            "android.app.installed.list",
        ),
    ),
    FeatureFamilySpec(
        "app_maintenance",
        TextR.string.feature_family_app_maintenance,
        TextR.string.feature_family_app_maintenance_subtitle,
        listOf(
            "android.app.data.clear",
            "android.app.permission.set",
        ),
    ),
    FeatureFamilySpec(
        "input_method",
        TextR.string.feature_family_input_method,
        TextR.string.feature_family_input_method_subtitle,
        listOf(
            "android.ime.picker.show",
            "android.ime.default.set",
            "android.ime.settings.open",
        ),
    ),
    FeatureFamilySpec(
        "screen_saver",
        TextR.string.feature_family_screen_saver,
        TextR.string.feature_family_screen_saver_subtitle,
        listOf(
            "android.display.dream.start",
            "android.display.dream.stop",
        ),
    ),
    FeatureFamilySpec(
        "display_system_controls",
        TextR.string.feature_family_display_system_controls,
        TextR.string.feature_family_display_system_controls_subtitle,
        listOf(
            "android.display.immersive.set",
            "android.display.color_inversion.set",
            "android.display.ambient_display.set",
        ),
    ),
    FeatureFamilySpec(
        "overlay_visibility",
        TextR.string.feature_family_overlay_visibility,
        TextR.string.feature_family_overlay_visibility_subtitle,
        listOf(
            "surface.overlay.show",
            "surface.overlay.hide",
            "surface.overlay.hide_all",
        ),
    ),
    FeatureFamilySpec(
        "surface_widgets",
        TextR.string.feature_family_surface_widgets,
        TextR.string.feature_family_surface_widgets_subtitle,
        listOf(
            "surface.input.show",
            "surface.list.show",
            "surface.buttons.show",
            "surface.sidebar.show",
            "surface.bubble.show",
            "surface.chip.show",
            "surface.pie.show",
            "surface.slider.show",
            "surface.toggle.show",
            "surface.image.show",
            "surface.web.show",
            "surface.edge_lighting.show",
            "surface.progress.show",
            "surface.screen_flash.show",
            "surface.danmu.show",
        ),
    ),
    FeatureFamilySpec(
        "surface_interaction",
        TextR.string.feature_family_surface_interaction,
        TextR.string.feature_family_surface_interaction_subtitle,
        listOf(
            "surface.touch_blocker.show",
            "surface.edge_gesture.show",
            "surface.region_selector.show",
            "surface.gesture_recorder.show",
            "surface.gesture_recording.get",
            "surface.gesture_recording.clear",
        ),
    ),
    FeatureFamilySpec(
        "location_triggers",
        TextR.string.feature_family_location_triggers,
        TextR.string.feature_family_location_triggers_subtitle,
        listOf(
            "android.event.location_update",
            "android.event.geofence_transition",
        ),
    ),
    FeatureFamilySpec(
        "sensor_triggers",
        TextR.string.feature_family_sensor_triggers,
        TextR.string.feature_family_sensor_triggers_subtitle,
        listOf(
            "android.event.sensor_value",
            "android.event.significant_motion",
            "android.event.shake",
        ),
    ),
    FeatureFamilySpec(
        "camera_video",
        TextR.string.feature_family_camera_video,
        TextR.string.feature_family_camera_video_subtitle,
        listOf(
            "android.camera.video.start",
            "android.camera.video.stop",
            "android.camera.video.record",
        ),
    ),
    FeatureFamilySpec(
        "qr_tools",
        TextR.string.feature_family_qr_tools,
        TextR.string.feature_family_qr_tools_subtitle,
        listOf(
            "android.qr.generate",
            "android.qr.decode",
        ),
    ),
    FeatureFamilySpec(
        "image_tools",
        TextR.string.feature_family_image_tools,
        TextR.string.feature_family_image_tools_subtitle,
        listOf(
            "android.ocr.text",
            "android.image.crop",
            "android.image.pixel.get",
            "android.image.color.find",
            "android.image.match",
        ),
    ),
    FeatureFamilySpec(
        "network_tools",
        TextR.string.feature_family_network_tools,
        TextR.string.feature_family_network_tools_subtitle,
        listOf(
            "android.network.dns.resolve",
            "android.network.local_addresses",
            "android.network.udp.send",
            "android.network.tcp.wait",
        ),
    ),
    FeatureFamilySpec(
        "system_info",
        TextR.string.feature_family_system_info,
        TextR.string.feature_family_system_info_subtitle,
        listOf(
            "android.memory.info",
            "android.device.uptime",
            "android.device.thermal_status",
            "android.locale.info",
        ),
    ),
    FeatureFamilySpec(
        "communication_actions",
        TextR.string.feature_family_communication_actions,
        TextR.string.feature_family_communication_actions_subtitle,
        listOf(
            "android.phone.dial",
            "android.sms.compose",
            "android.email.compose",
        ),
    ),
)

internal data class FeatureFamilyIndex(
    val byId: Map<String, FeaturePickerFamily>,
    val byMemberId: Map<String, String>,
)

internal fun buildFeatureFamilyIndex(items: List<FeaturePickerCatalogItem>): FeatureFamilyIndex {
    val duplicateMemberIds = FEATURE_FAMILY_SPECS
        .flatMap { spec -> spec.memberIds.map { memberId -> memberId to spec.id } }
        .groupBy({ it.first }, { it.second })
        .filterValues { familyIds -> familyIds.distinct().size > 1 }
    check(duplicateMemberIds.isEmpty()) {
        "Feature picker family member belongs to multiple families: $duplicateMemberIds"
    }

    val byFeatureId = items.associateBy { it.descriptor.id.value }
    val families = FEATURE_FAMILY_SPECS.mapNotNull { spec ->
        val members = spec.memberIds.mapNotNull(byFeatureId::get)
        if (members.size < 2) return@mapNotNull null
        if (members.map { it.category.id }.distinct().size != 1) return@mapNotNull null
        spec.id to FeaturePickerFamily(spec, members)
    }.toMap()

    return FeatureFamilyIndex(
        byId = families,
        byMemberId = buildMap {
            families.forEach { (familyId, family) ->
                family.members.forEach { member -> put(member.descriptor.id.value, familyId) }
            }
        },
    )
}

internal fun collapseFeaturePickerItems(
    items: List<FeaturePickerCatalogItem>,
    familyIndex: FeatureFamilyIndex,
): List<FeaturePickerListEntry> {
    val emittedFamilies = HashSet<String>()
    return buildList {
        items.forEach { item ->
            val familyId = familyIndex.byMemberId[item.descriptor.id.value]
            val family = familyId?.let(familyIndex.byId::get)
            if (family == null) {
                add(FeaturePickerListEntry.Feature(item))
            } else if (emittedFamilies.add(familyId)) {
                add(FeaturePickerListEntry.Family(family))
            }
        }
    }
}
