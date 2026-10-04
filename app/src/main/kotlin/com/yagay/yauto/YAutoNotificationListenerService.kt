package com.yagay.yauto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.platform.android.ActiveNotificationSnapshot
import com.yagay.yauto.platform.android.HistoricalNotificationSnapshot
import com.yagay.yauto.platform.android.NotificationControlBridge
import com.yagay.yauto.platform.android.NotificationController
import com.yagay.yauto.platform.android.NotificationRuntimeEventMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.atomic.AtomicInteger

class YAutoNotificationListenerService : NotificationListenerService(), NotificationController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dispatcher by lazy { RuntimeEventDispatcher((application as YAutoApplication).graph, scope) }
    private val mediaSessionManager by lazy { getSystemService(MediaSessionManager::class.java) }
    private val mediaCallbacks = LinkedHashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private val mediaMetadataSignatures = HashMap<MediaSession.Token, String>()
    private val mediaPlaybackSignatures = HashMap<MediaSession.Token, String>()
    private val notificationHistory = LinkedHashMap<String, RemovedNotificationRecord>()
    private val restoredNotificationIds = AtomicInteger(40_000)

    private val activeSessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        syncMediaSessions(controllers.orEmpty())
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationControlBridge.attach(this)
        runCatching {
            val component = ComponentName(this, YAutoNotificationListenerService::class.java)
            mediaSessionManager.addOnActiveSessionsChangedListener(activeSessionsListener, component)
            syncMediaSessions(mediaSessionManager.getActiveSessions(component))
        }
    }

    override fun onListenerDisconnected() {
        if (NotificationControlBridge.current() === this) NotificationControlBridge.attach(null)
        clearMediaSessions()
        runCatching { mediaSessionManager.removeOnActiveSessionsChangedListener(activeSessionsListener) }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        dispatcher.dispatch(NotificationRuntimeEventMapper.posted(sbn))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        rememberRemoved(sbn, null)
        dispatcher.dispatch(NotificationRuntimeEventMapper.removed(sbn))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        rememberRemoved(sbn, reason)
        if (reason == REASON_CLICK) dispatcher.dispatch(NotificationRuntimeEventMapper.clicked(sbn))
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

    override fun history(): List<HistoricalNotificationSnapshot> = synchronized(notificationHistory) {
        notificationHistory.values.map { it.snapshot }.reversed()
    }

    override fun clearHistory(): Int = synchronized(notificationHistory) {
        val count = notificationHistory.size
        notificationHistory.clear()
        count
    }

    override fun restore(
        packageName: String,
        titleContains: String,
        textContains: String,
        maxCount: Int,
        excludePackage: Boolean,
    ): Int {
        val records = synchronized(notificationHistory) {
            notificationHistory.values.toList().asReversed()
        }.filter { record ->
            val packageMatches = packageName.isBlank() || record.snapshot.packageName == packageName
            val packageAllowed = if (excludePackage && packageName.isNotBlank()) !packageMatches else packageMatches
            packageAllowed &&
                (titleContains.isBlank() || record.snapshot.title.contains(titleContains, ignoreCase = true)) &&
                (textContains.isBlank() || record.snapshot.text.contains(textContains, ignoreCase = true))
        }.take(maxCount.coerceIn(1, 100))
        if (records.isEmpty()) return 0

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                RESTORED_CHANNEL_ID,
                "Restored notifications",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Copies of previously removed notifications restored by YAuto"
            }
        )
        var restored = 0
        records.asReversed().forEach { record ->
            val snapshot = record.snapshot
            val appLabel = runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(snapshot.packageName, 0)).toString()
            }.getOrDefault(snapshot.packageName)
            val original = record.notification
            val builder = Notification.Builder(this, RESTORED_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(snapshot.title.ifBlank { appLabel })
                .setContentText(snapshot.text)
                .setSubText("Restored from $appLabel")
                .setWhen(snapshot.originalPostTimeEpochMs.takeIf { it > 0 } ?: snapshot.removedAtEpochMs)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
            original.contentIntent?.let(builder::setContentIntent)
            original.deleteIntent?.let(builder::setDeleteIntent)
            original.actions.orEmpty().take(4).forEach(builder::addAction)
            runCatching {
                manager.notify(restoredNotificationIds.incrementAndGet(), builder.build())
                restored++
            }
        }
        return restored
    }

    private fun rememberRemoved(sbn: StatusBarNotification, reason: Int?) {
        val notification = sbn.notification
        val extras = notification.extras
        val snapshot = HistoricalNotificationSnapshot(
            packageName = sbn.packageName,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            removedAtEpochMs = System.currentTimeMillis(),
            originalPostTimeEpochMs = sbn.postTime,
            channelId = notification.channelId.orEmpty(),
            category = notification.category.orEmpty(),
            groupKey = sbn.groupKey.orEmpty(),
            reason = reason,
        )
        val key = sbn.key + ":" + sbn.postTime
        synchronized(notificationHistory) {
            notificationHistory[key] = RemovedNotificationRecord(snapshot, notification)
            while (notificationHistory.size > MAX_NOTIFICATION_HISTORY) {
                val first = notificationHistory.entries.firstOrNull()?.key ?: break
                notificationHistory.remove(first)
            }
        }
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

    private fun syncMediaSessions(controllers: List<MediaController>) {
        clearMediaSessions()
        controllers.forEach { controller ->
            val token = controller.sessionToken
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    metadata ?: return
                    val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                        ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                        ?: ""
                    val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                        ?: ""
                    val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
                    val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
                    val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
                    val signature = listOf(title, artist, album, mediaId, duration.toString()).joinToString("\u0000")
                    if (mediaMetadataSignatures[token] == signature) return
                    mediaMetadataSignatures[token] = signature
                    dispatcher.dispatch(
                        RuntimeEvent(
                            "android.event.media_track_changed",
                            mapOf(
                                "package" to ConfigValue.StringValue(controller.packageName),
                                "title" to ConfigValue.StringValue(title),
                                "artist" to ConfigValue.StringValue(artist),
                                "album" to ConfigValue.StringValue(album),
                                "mediaId" to ConfigValue.StringValue(mediaId),
                                "durationMs" to ConfigValue.NumberValue(duration.toDouble()),
                            ),
                            source = "android.media_session",
                        )
                    )
                }

                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    state ?: return
                    val stateName = playbackStateName(state.state)
                    val signature = "${stateName}:${state.position}:${state.playbackSpeed}:${state.actions}"
                    if (mediaPlaybackSignatures[token] == signature) return
                    mediaPlaybackSignatures[token] = signature
                    dispatcher.dispatch(
                        RuntimeEvent(
                            "android.event.media_playback_state_changed",
                            mapOf(
                                "package" to ConfigValue.StringValue(controller.packageName),
                                "state" to ConfigValue.StringValue(stateName),
                                "positionMs" to ConfigValue.NumberValue(state.position.toDouble()),
                                "speed" to ConfigValue.NumberValue(state.playbackSpeed.toDouble()),
                                "actions" to ConfigValue.NumberValue(state.actions.toDouble()),
                            ),
                            source = "android.media_session",
                        )
                    )
                }
            }
            controller.registerCallback(callback)
            mediaCallbacks[token] = controller to callback
        }
    }

    private fun clearMediaSessions() {
        mediaCallbacks.values.forEach { (controller, callback) -> runCatching { controller.unregisterCallback(callback) } }
        mediaCallbacks.clear()
        mediaMetadataSignatures.clear()
        mediaPlaybackSignatures.clear()
    }

    private fun playbackStateName(state: Int): String = when (state) {
        PlaybackState.STATE_NONE -> "none"
        PlaybackState.STATE_STOPPED -> "stopped"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_FAST_FORWARDING -> "fast_forwarding"
        PlaybackState.STATE_REWINDING -> "rewinding"
        PlaybackState.STATE_BUFFERING -> "buffering"
        PlaybackState.STATE_ERROR -> "error"
        PlaybackState.STATE_CONNECTING -> "connecting"
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "skipping_previous"
        PlaybackState.STATE_SKIPPING_TO_NEXT -> "skipping_next"
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "skipping_queue_item"
        else -> "unknown"
    }

    private fun send(intent: PendingIntent?): Boolean = runCatching {
        intent ?: return false
        intent.send()
        true
    }.getOrDefault(false)

    override fun onDestroy() {
        if (NotificationControlBridge.current() === this) NotificationControlBridge.attach(null)
        clearMediaSessions()
        runCatching { mediaSessionManager.removeOnActiveSessionsChangedListener(activeSessionsListener) }
        scope.cancel()
        super.onDestroy()
    }
    private companion object {
        const val RESTORED_CHANNEL_ID = "yauto_restored_notifications"
        const val MAX_NOTIFICATION_HISTORY = 200
    }

}
