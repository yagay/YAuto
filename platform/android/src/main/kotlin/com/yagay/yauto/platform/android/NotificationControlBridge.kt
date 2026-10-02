package com.yagay.yauto.platform.android

data class ActiveNotificationSnapshot(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val actionCount: Int,
    val ongoing: Boolean,
)

interface NotificationController {
    fun snapshots(): List<ActiveNotificationSnapshot>
    fun dismiss(key: String): Boolean
    fun open(key: String): Boolean
    fun invokeAction(key: String, index: Int): Boolean
}

/** Process-local bridge. The app's NotificationListenerService owns the Android objects. */
object NotificationControlBridge {
    @Volatile private var controller: NotificationController? = null

    fun attach(value: NotificationController?) { controller = value }
    fun current(): NotificationController? = controller
}
