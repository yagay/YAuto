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
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.ExecutionId
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.platform.accessibility.AccessibilityRuntimeBridge
import com.yagay.yauto.platform.android.AndroidEventSource
import com.yagay.yauto.platform.android.ClipboardEventSource
import com.yagay.yauto.platform.android.ConfiguredBroadcastEventSource
import com.yagay.yauto.platform.android.ConfiguredSensorEventSource
import com.yagay.yauto.platform.android.NetworkEventSource
import com.yagay.yauto.platform.android.RuntimeEventEmitter
import com.yagay.yauto.platform.android.SurfaceRuntimeBridge
import com.yagay.yauto.platform.android.SystemBroadcastEventSource
import com.yagay.yauto.ui.design.R as TextR
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
        sources += ClipboardEventSource(this)
        sources += ConfiguredBroadcastEventSource(this, graph.workspace)
        sources += ConfiguredSensorEventSource(this, graph.workspace)
        val emitter = RuntimeEventEmitter { dispatcher.dispatch(it) }
        SurfaceRuntimeBridge.attach(emitter)

        AccessibilityRuntimeBridge.setListener { previous, current ->
            val currentPayload = mapOf(
                "package" to ConfigValue.StringValue(current.packageName),
                "class" to ConfigValue.StringValue(current.className.orEmpty()),
            )
            dispatcher.dispatch(
                RuntimeEvent(
                    "android.event.window_changed",
                    currentPayload,
                    source = "accessibility.window",
                )
            )
            if (previous?.packageName != current.packageName) {
                previous?.let {
                    dispatcher.dispatch(
                        RuntimeEvent(
                            "android.event.app_background",
                            mapOf(
                                "package" to ConfigValue.StringValue(it.packageName),
                                "class" to ConfigValue.StringValue(it.className.orEmpty()),
                                "nextPackage" to ConfigValue.StringValue(current.packageName),
                            ),
                            source = "accessibility.window",
                        )
                    )
                }
                dispatcher.dispatch(
                    RuntimeEvent(
                        "android.event.app_foreground",
                        currentPayload,
                        source = "accessibility.window",
                    )
                )
            }
        }

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
                                message = userText("runtime.event_source_failed", "Event source failed to start: %s: %s", source.id, error.message ?: error::class.simpleName.orEmpty()),
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
        dispatcher.dispatch(
            RuntimeEvent("android.event.runtime_started", source = "android.runtime"),
            statesOnly = true,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("boot", false) == true) {
            RuntimeEventDispatcher((application as YAutoApplication).graph, scope).dispatch(
                RuntimeEvent("android.event.boot", source = "android.boot")
            )
        }
        return START_STICKY
    }

    override fun onDestroy() {
        AccessibilityRuntimeBridge.setListener(null)
        SurfaceRuntimeBridge.attach(null)
        sources.asReversed().forEach { source -> runCatching { source.stop() } }
        sources.clear()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(TextR.string.runtime_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(TextR.string.runtime_notification_channel_description)
                setShowBadge(false)
            }
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(getString(TextR.string.runtime_notification_title))
            .setContentText(getString(TextR.string.runtime_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "yauto_runtime"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context, boot: Boolean = false) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AutomationRuntimeService::class.java).putExtra("boot", boot),
            )
        }
    }
}
