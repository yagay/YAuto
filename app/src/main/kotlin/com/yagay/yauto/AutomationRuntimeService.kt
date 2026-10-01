package com.yagay.yauto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yagay.yauto.core.logging.TraceEvent
import com.yagay.yauto.core.logging.TraceKind
import com.yagay.yauto.core.logging.TraceLevel
import com.yagay.yauto.core.model.ExecutionId
import com.yagay.yauto.platform.android.AndroidEventSource
import com.yagay.yauto.platform.android.NetworkEventSource
import com.yagay.yauto.platform.android.RuntimeEventEmitter
import com.yagay.yauto.platform.android.SystemBroadcastEventSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

class AutomationRuntimeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sources = mutableListOf<AndroidEventSource>()

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, createNotification())
        val graph = (application as YAutoApplication).graph
        val dispatcher = RuntimeEventDispatcher(graph, scope)
        sources += SystemBroadcastEventSource(this)
        sources += NetworkEventSource(this)
        val emitter = RuntimeEventEmitter(dispatcher::dispatch)
        sources.forEach { source ->
            runCatching { source.start(emitter) }
                .onFailure { error ->
                    scope.launch {
                        graph.tracer.record(
                            TraceEvent(
                                executionId = ExecutionId("source-${UUID.randomUUID()}"),
                                kind = TraceKind.ERROR,
                                level = TraceLevel.ERROR,
                                timestampEpochMs = System.currentTimeMillis(),
                                message = "Event source failed to start: ${source.id}: ${error.message ?: error::class.simpleName}",
                                success = false,
                                attributes = mapOf(
                                    "eventSource" to source.id,
                                    "exception" to error::class.qualifiedName.orEmpty(),
                                ),
                            )
                        )
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        sources.asReversed().forEach { source -> runCatching { source.stop() } }
        sources.clear()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "YAuto 自动化运行", NotificationManager.IMPORTANCE_LOW).apply {
                description = "保持自动化事件监听和规则运行"
                setShowBadge(false)
            }
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("YAuto 正在运行")
            .setContentText("自动化事件监听已启用")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "yauto_runtime"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AutomationRuntimeService::class.java))
        }
    }
}
