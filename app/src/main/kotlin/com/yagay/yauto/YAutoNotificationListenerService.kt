package com.yagay.yauto

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.yagay.yauto.platform.android.ActiveNotificationSnapshot
import com.yagay.yauto.platform.android.NotificationControlBridge
import com.yagay.yauto.platform.android.NotificationController
import com.yagay.yauto.platform.android.NotificationRuntimeEventMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class YAutoNotificationListenerService : NotificationListenerService(), NotificationController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dispatcher by lazy { RuntimeEventDispatcher((application as YAutoApplication).graph, scope) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationControlBridge.attach(this)
    }

    override fun onListenerDisconnected() {
        if (NotificationControlBridge.current() === this) NotificationControlBridge.attach(null)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        dispatcher.dispatch(NotificationRuntimeEventMapper.posted(sbn))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        dispatcher.dispatch(NotificationRuntimeEventMapper.removed(sbn))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        dispatcher.dispatch(NotificationRuntimeEventMapper.removed(sbn, reason))
    }

    override fun snapshots(): List<ActiveNotificationSnapshot> = activeNotifications.orEmpty().map { sbn ->
        val notification = sbn.notification
        val extras = notification.extras
        val actions = notification.actions.orEmpty()
        ActiveNotificationSnapshot(
            key = sbn.key,
            packageName = sbn.packageName,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            actionCount = actions.size,
            ongoing = sbn.isOngoing,
            actionTitles = actions.map { it.title?.toString().orEmpty() },
            replyActionIndexes = actions.mapIndexedNotNull { index, action -> index.takeIf { action.remoteInputs.orEmpty().isNotEmpty() } },
            postTimeEpochMs = sbn.postTime,
            notificationId = sbn.id,
            tag = sbn.tag.orEmpty(),
            channelId = notification.channelId.orEmpty(),
            category = notification.category.orEmpty(),
            groupKey = sbn.groupKey.orEmpty(),
        )
    }

    override fun dismiss(key: String): Boolean = runCatching {
        cancelNotification(key)
        true
    }.getOrDefault(false)

    override fun open(key: String): Boolean {
        val sbn = activeNotifications.orEmpty().firstOrNull { it.key == key } ?: return false
        return send(sbn.notification.contentIntent)
    }

    override fun invokeAction(key: String, index: Int): Boolean {
        val sbn = activeNotifications.orEmpty().firstOrNull { it.key == key } ?: return false
        val action = sbn.notification.actions?.getOrNull(index) ?: return false
        return send(action.actionIntent)
    }

    override fun reply(key: String, actionIndex: Int?, text: String): Boolean = runCatching {
        val sbn = activeNotifications.orEmpty().firstOrNull { it.key == key } ?: return false
        val actions = sbn.notification.actions.orEmpty()
        val action = if (actionIndex != null) {
            actions.getOrNull(actionIndex)
        } else {
            actions.firstOrNull { it.remoteInputs.orEmpty().isNotEmpty() }
        } ?: return false
        val remoteInputs = action.remoteInputs.orEmpty()
        if (remoteInputs.isEmpty()) return false

        val results = Bundle().apply {
            remoteInputs.forEach { input -> putCharSequence(input.resultKey, text) }
        }
        val fillIn = Intent()
        RemoteInput.addResultsToIntent(remoteInputs, fillIn, results)
        action.actionIntent.send(this, 0, fillIn)
        true
    }.getOrDefault(false)

    private fun send(intent: PendingIntent?): Boolean = runCatching {
        intent ?: return false
        intent.send()
        true
    }.getOrDefault(false)

    override fun onDestroy() {
        if (NotificationControlBridge.current() === this) NotificationControlBridge.attach(null)
        scope.cancel()
        super.onDestroy()
    }
}
