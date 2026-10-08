package com.yagay.yauto.platform.android

data class HistoricalNotificationSnapshot(
    val packageName: String,
    val title: String,
    val text: String,
    val removedAtEpochMs: Long,
    val originalPostTimeEpochMs: Long = 0L,
    val channelId: String = "",
    val category: String = "",
    val groupKey: String = "",
    val reason: Int? = null,
)

data class ActiveNotificationSnapshot(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val actionCount: Int,
    val ongoing: Boolean,
    val actionTitles: List<String> = emptyList(),
    val replyActionIndexes: List<Int> = emptyList(),
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
    fun reply(key: String, actionIndex: Int?, text: String): Boolean
    fun history(): List<HistoricalNotificationSnapshot> = emptyList()
    fun restore(
        packageName: String = "",
        titleContains: String = "",
        textContains: String = "",
        maxCount: Int = 50,
        excludePackage: Boolean = false,
    ): Int = 0
    fun clearHistory(): Int = 0
}

object NotificationControlBridge {
    @Volatile private var controller: NotificationController? = null

    fun attach(value: NotificationController?) {
        controller = value
    }

    fun current(): NotificationController? = controller
}
