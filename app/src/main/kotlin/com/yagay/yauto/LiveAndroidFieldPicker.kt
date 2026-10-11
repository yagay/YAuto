package com.yagay.yauto

import android.content.Context
import android.app.NotificationManager
import android.service.quicksettings.Tile
import com.yagay.yauto.platform.android.NotificationControlBridge
import com.yagay.yauto.platform.android.QuickSettingsTileController
import com.yagay.yauto.ui.design.R as TextR
import com.yagay.yauto.ui.editor.LiveSystemFieldPicker
import com.yagay.yauto.ui.editor.SystemPickerOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * UI only: read the live NotificationListener and YAuto tile preferences.
 * Never synthesize other apps' notifications or claim to know panel tile order.
 */
internal fun liveAndroidFieldPicker(
    context: Context,
    tiles: QuickSettingsTileController,
    deviceTiles: DeviceQuickSettingsInventory,
): LiveSystemFieldPicker = LiveSystemFieldPicker { descriptorId, fieldKey, values ->
    withContext(Dispatchers.Default) {
        if (fieldKey == "slot" && descriptorId.contains("qs_tile", ignoreCase = true)) {
            (1..3).map { slot ->
                val state = when (tiles.state(slot)) {
                    Tile.STATE_ACTIVE -> context.getString(TextR.string.live_picker_tile_active)
                    Tile.STATE_UNAVAILABLE -> context.getString(TextR.string.live_picker_tile_unavailable)
                    else -> context.getString(TextR.string.live_picker_tile_inactive)
                }
                SystemPickerOption(
                    value = slot.toString(),
                    label = context.getString(TextR.string.live_picker_tile_slot, slot, tiles.label(slot)),
                    detail = state,
                )
            }
        } else if (fieldKey in setOf("component", "tileSpec") &&
            descriptorId.contains("qs_tile", ignoreCase = true)
        ) {
            deviceTiles.load().entries.mapNotNull { entry ->
                val value = if (fieldKey == "component") entry.component else entry.spec
                if (value.isNullOrBlank()) null else {
                    val origin = when (entry.isInPanel) {
                        true -> context.getString(TextR.string.live_picker_tile_in_panel)
                        false -> context.getString(TextR.string.live_picker_tile_not_in_panel)
                        null -> context.getString(TextR.string.live_picker_tile_unknown_panel)
                    }
                    SystemPickerOption(
                        value = value,
                        label = entry.label,
                        detail = "$origin · ${entry.spec}",
                    )
                }
            }
        } else if (descriptorId.contains("notification", ignoreCase = true)) {
            val controller = NotificationControlBridge.current()
            val selectedPackage = values["package"].orEmpty().trim()
            val pm = context.packageManager
            val isOwnedChannel = descriptorId.contains("notification.channel", ignoreCase = true)
            val supportsOwnChannel = fieldKey in setOf("channelId", "groupId")
            // Creating/managing YAuto's own channels never requires a connected
            // notification listener or inventing other apps' channel catalogs.
            val owned = if (isOwnedChannel && supportsOwnChannel) {
                val manager = context.getSystemService(NotificationManager::class.java)
                when (fieldKey) {
                    "channelId" -> manager.notificationChannels.map {
                        SystemPickerOption(it.id, it.name?.toString().orEmpty().ifBlank { it.id }, it.id)
                    }
                    else -> manager.notificationChannelGroups.map {
                        SystemPickerOption(it.id, it.name?.toString().orEmpty().ifBlank { it.id }, it.id)
                    }
                }
            } else emptyList()
            if (controller == null && !isOwnedChannel) {
                error("Notification listener is not connected")
            }
            val active = controller?.snapshots().orEmpty()
                .asSequence()
                .filter { selectedPackage.isEmpty() || it.packageName == selectedPackage }
                .sortedByDescending { it.postTimeEpochMs }
                .take(200).toList()
            val previous = controller?.history().orEmpty()
                .asSequence()
                .filter { selectedPackage.isEmpty() || it.packageName == selectedPackage }
                .sortedByDescending { it.removedAtEpochMs }
                .take(200).toList()
            (owned + buildList {
                active.forEach { notification ->
                    val appName = runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(notification.packageName, 0)).toString()
                    }.getOrDefault(notification.packageName)
                    val origin = if (notification.title.isNotBlank()) {
                        context.getString(
                            TextR.string.live_picker_notification_origin,
                            appName, notification.title.take(70),
                        )
                    } else appName
                    val related = mapOf("package" to notification.packageName)
                    fun addValue(value: String, readable: String = value, indexDetail: String = origin) {
                        if (value.isNotBlank()) add(SystemPickerOption(
                            value, readable.take(120), indexDetail.take(250), related,
                        ))
                    }
                    when (fieldKey) {
                        "package" -> addValue(notification.packageName, appName)
                        "channelId" -> addValue(notification.channelId)
                        "groupKey" -> addValue(notification.groupKey)
                        "titleContains" -> addValue(notification.title)
                        "textContains" -> addValue(notification.text)
                        "notificationId", "id" -> addValue(notification.notificationId.toString())
                        "tag" -> addValue(notification.tag)
                        "actionTitle" -> notification.actionTitles.forEach { title ->
                            addValue(title)
                        }
                        "actionIndex" -> notification.actionTitles.forEachIndexed { index, title ->
                            addValue(index.toString(), context.getString(
                                TextR.string.live_picker_notification_action, index + 1, title,
                            ))
                        }
                    }
                }
                // NotificationListener snapshots only cover currently active items.
                // History is a fallback solely for text/filter metadata; never
                // offer nonexistent action buttons from dismissed notifications.
                previous.forEach { item ->
                    val origin = context.getString(
                        TextR.string.live_picker_notification_history_origin,
                        item.packageName, item.title.take(60),
                    )
                    val related = mapOf("package" to item.packageName)
                    fun historical(value: String, label: String = value) {
                        if (value.isNotBlank()) add(SystemPickerOption(
                            value, label.take(120), origin.take(250), related,
                        ))
                    }
                    when (fieldKey) {
                        "package" -> historical(item.packageName)
                        "channelId" -> historical(item.channelId)
                        "groupKey" -> historical(item.groupKey)
                        "titleContains" -> historical(item.title)
                        "textContains" -> historical(item.text)
                    }
                }
            }).distinctBy { it.value }.take(200)
        } else emptyList()
    }
}
