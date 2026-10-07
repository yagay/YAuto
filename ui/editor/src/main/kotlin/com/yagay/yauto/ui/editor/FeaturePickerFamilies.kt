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
        "android_settings",
        TextR.string.feature_family_android_settings,
        TextR.string.feature_family_android_settings_subtitle,
        listOf("android.settings.open", "android.settings.page.open"),
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
        listOf("android.clipboard.read", "android.clipboard.write"),
    ),
    FeatureFamilySpec(
        "vibration",
        TextR.string.feature_family_vibration,
        TextR.string.feature_family_vibration_subtitle,
        listOf(
            "android.vibration.vibrate",
            "android.vibration.pattern",
            "android.vibration.cancel",
            "android.vibrate.cancel",
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
)

internal data class FeatureFamilyIndex(
    val byId: Map<String, FeaturePickerFamily>,
    val byMemberId: Map<String, String>,
)

internal fun buildFeatureFamilyIndex(items: List<FeaturePickerCatalogItem>): FeatureFamilyIndex {
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
