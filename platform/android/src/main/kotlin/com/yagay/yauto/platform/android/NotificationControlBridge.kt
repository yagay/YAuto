package com.yagay.yauto.platform.android

data class ActiveNotificationSnapshot(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val actionCount: Int,
    val ongoing: Boolean,
    val actionTitles: List<String> = emptyList(),
    val postTimeEpochMs: Long = 0L,
    val notificationId: Int = 0,
    val tag: String = "",
    val channelId: String = "",
    val category: String = "",
    val groupKey: String = "",
)

interface NotificationController {
    fun snapshots(): List<ActiveNotificationSnapshot>
    fun dismiss(key: String): Boolean
    fun open(key: String): Boolean
    fun invokeAction(key: String, index: Int): Boolean
}

object NotificationControlBridge {
    @Volatile private var controller: NotificationController? = null

    fun attach(value: NotificationController?) {
        controller = value
    }

    fun current(): NotificationController? = controller
}
