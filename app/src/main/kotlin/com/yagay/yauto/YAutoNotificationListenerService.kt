package com.yagay.yauto

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.yagay.yauto.platform.android.NotificationRuntimeEventMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class YAutoNotificationListenerService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dispatcher by lazy {
        RuntimeEventDispatcher((application as YAutoApplication).graph, scope)
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

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
