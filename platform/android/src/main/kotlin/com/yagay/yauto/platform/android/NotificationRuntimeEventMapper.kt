package com.yagay.yauto.platform.android

import android.app.Notification
import android.service.notification.StatusBarNotification
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent

object NotificationRuntimeEventMapper {
    fun posted(sbn: StatusBarNotification): RuntimeEvent =
        RuntimeEvent(
            typeId = "android.event.notification_posted",
            payload = payload(sbn),
            source = "android.notification_listener",
        )

    fun clicked(sbn: StatusBarNotification): RuntimeEvent =
        RuntimeEvent(
            typeId = "android.event.notification_clicked",
            payload = payload(sbn),
            source = "android.notification_listener",
        )

    fun removed(sbn: StatusBarNotification, reason: Int? = null): RuntimeEvent =
        RuntimeEvent(
            typeId = "android.event.notification_removed",
            payload = buildMap {
                putAll(payload(sbn))
                reason?.let { put("reason", ConfigValue.NumberValue(it.toDouble())) }
            },
            source = "android.notification_listener",
        )

    private fun payload(sbn: StatusBarNotification): Map<String, ConfigValue> {
        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()

        return buildMap {
            put("package", ConfigValue.StringValue(sbn.packageName.orEmpty()))
            put("key", ConfigValue.StringValue(sbn.key.orEmpty()))
            put("id", ConfigValue.NumberValue(sbn.id.toDouble()))
            sbn.tag?.let { put("tag", ConfigValue.StringValue(it)) }
            put("postTime", ConfigValue.NumberValue(sbn.postTime.toDouble()))
            put("ongoing", ConfigValue.BooleanValue(sbn.isOngoing))
            put("actionCount", ConfigValue.NumberValue(notification.actions.orEmpty().size.toDouble()))
            put("clearable", ConfigValue.BooleanValue(sbn.isClearable))
            put("title", ConfigValue.StringValue(title))
            put("text", ConfigValue.StringValue(text))
            put("subText", ConfigValue.StringValue(subText))
            put("channel", ConfigValue.StringValue(notification.channelId.orEmpty()))
            put("category", ConfigValue.StringValue(notification.category.orEmpty()))
            put("group", ConfigValue.StringValue(notification.group.orEmpty()))
            put("groupKey", ConfigValue.StringValue(sbn.groupKey.orEmpty()))
            put("flags", ConfigValue.NumberValue(notification.flags.toDouble()))
        }
    }
}
