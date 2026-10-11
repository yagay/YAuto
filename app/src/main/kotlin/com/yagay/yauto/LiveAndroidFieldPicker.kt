package com.yagay.yauto

import android.content.Context
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
        } else if (descriptorId.contains("notification", ignoreCase = true)) {
            val controller = NotificationControlBridge.current()
                ?: error("Notification listener is not connected")
            val selectedPackage = values["package"].orEmpty().trim()
            val pm = context.packageManager
            val active = controller.snapshots()
                .asSequence()
                .filter { selectedPackage.isEmpty() || it.packageName == selectedPackage }
                .sortedByDescending { it.postTimeEpochMs }
                .take(200)
                .toList()
            buildList {
                active.forEach { notification ->
                    val appName = runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(notification.packageName, 0)).toString()
                    }.getOrDefault(notification.packageName)
                    val origin = buildString {
                        append(appName)
                        if (notification.title.isNotBlank()) {
                            append(" · ")
                            append(notification.title.take(70))
                        }
                    }
                    val related = mapOf("package" to notification.packageName)
                    fun addValue(value: String, readable: String = value, indexDetail: String = origin) {
                        if (value.isNotBlank()) add(SystemPickerOption(
                            value, readable.take(120), indexDetail.take(250), related
                        ))
                    }
                    when (fieldKey) {
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
                                TextR.string.live_picker_notification_action, index + 1, title
                            ))
                        }
                    }
                }
            }.distinctBy { it.value }.take(120)
        } else emptyList()
    }
}
