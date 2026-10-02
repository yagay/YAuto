package com.yagay.yauto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
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
import com.yagay.yauto.platform.android.AudioDeviceEventSource
import com.yagay.yauto.platform.android.BluetoothDeviceEventSource
import com.yagay.yauto.platform.android.ClipboardEventSource
import com.yagay.yauto.platform.android.CommunicationEventSource
import com.yagay.yauto.platform.android.ConfiguredBroadcastEventSource
import com.yagay.yauto.platform.android.ConfiguredLocationEventSource
import com.yagay.yauto.platform.android.ConfiguredSensorEventSource
import com.yagay.yauto.platform.android.DeviceSettingEventSource
import com.yagay.yauto.platform.android.MidiDeviceEventSource
import com.yagay.yauto.platform.android.NetworkEventSource
import com.yagay.yauto.platform.android.NetworkProfileEventSource
import com.yagay.yauto.platform.android.RuntimeEventEmitter
import com.yagay.yauto.platform.android.SurfaceRuntimeBridge
import com.yagay.yauto.platform.android.SystemBroadcastEventSource
import com.yagay.yauto.platform.android.WifiScanEventSource
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
        if (!promoteToForeground()) {
            stopSelf()
            return
        }
        val graph = runCatching { (application as YAutoApplication).graph }
            .onFailure { StartupFailureRecorder.record(this, "runtime:graph", it) }
            .getOrNull()
        if (graph == null) {
            stopSelf()
            return
        }
        val dispatcher = RuntimeEventDispatcher(graph, scope)

        addSource("system-broadcast") { SystemBroadcastEventSource(this) }
        addSource("network") { NetworkEventSource(this) }
        addSource("network-profile") { NetworkProfileEventSource(this) }
        addSource("wifi-scan") { WifiScanEventSource(this) }
        addSource("clipboard") { ClipboardEventSource(this) }
        addSource("device-setting") { DeviceSettingEventSource(this) }
        addSource("audio-device") { AudioDeviceEventSource(this) }
        addSource("bluetooth-device") { BluetoothDeviceEventSource(this) }
        addSource("communication") { CommunicationEventSource(this) }
        addSource("midi-device") { MidiDeviceEventSource(this) }
        addSource("configured-broadcast") { ConfiguredBroadcastEventSource(this, graph.workspace) }
        addSource("configured-sensor") { ConfiguredSensorEventSource(this, graph.workspace) }
        addSource("configured-location") { ConfiguredLocationEventSource(this, graph.workspace) }

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
        AccessibilityRuntimeBridge.setKeyListener { key ->
            val action = when (key.action) {
                KeyEvent.ACTION_DOWN -> "down"
                KeyEvent.ACTION_UP -> "up"
                else -> "other"
            }
            dispatcher.dispatch(
                RuntimeEvent(
                    typeId = "android.event.hardware_key",
                    payload = mapOf(
                        "keyCode" to ConfigValue.NumberValue(key.keyCode.toDouble()),
                        "action" to ConfigValue.StringValue(action),
                        "repeatCount" to ConfigValue.NumberValue(key.repeatCount.toDouble()),
                        "metaState" to ConfigValue.NumberValue(key.metaState.toDouble()),
                        "deviceId" to ConfigValue.NumberValue(key.deviceId.toDouble()),
                    ),
                    source = "accessibility.key",
                )
            )
        }

        sources.forEach { source ->
            runCatching { source.start(emitter) }
                .onFailure { error ->
                    StartupFailureRecorder.record(this, "event-source:${source.id}:start", error)
                    scope.launch {
                        graph.tracer.record(
                            TraceEvent(
                                executionId = ExecutionId("source-${UUID.randomUUID()}"),
                                kind = TraceKind.ERROR,
                                level = TraceLevel.ERROR,
                                timestampEpochMs = System.currentTimeMillis(),
                                message = userText("runtime.event_source_failed", source.id, error.message ?: error::class.simpleName.orEmpty()),
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
        if (intent?.action == ACTION_REFRESH_LOCALIZED_SURFACES) {
            if (!promoteToForeground()) {
                stopSelf()
                return START_NOT_STICKY
            }
            return START_STICKY
        }
        if (intent?.getBooleanExtra("boot", false) == true) {
            runCatching { (application as YAutoApplication).graph }
                .onFailure { StartupFailureRecorder.record(this, "runtime:boot-graph", it) }
                .getOrNull()
                ?.let { graph ->
                    RuntimeEventDispatcher(graph, scope).dispatch(
                        RuntimeEvent("android.event.boot", source = "android.boot")
                    )
                }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        AccessibilityRuntimeBridge.setListener(null)
        AccessibilityRuntimeBridge.setKeyListener(null)
        SurfaceRuntimeBridge.attach(null)
        sources.asReversed().forEach { source -> runCatching { source.stop() } }
        sources.clear()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun addSource(component: String, factory: () -> AndroidEventSource) {
        try {
            sources += factory()
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            StartupFailureRecorder.record(this, "event-source:$component:construct", error)
        }
    }

    private fun promoteToForeground(): Boolean = runCatching {
        startForeground(NOTIFICATION_ID, createNotification())
        true
    }.getOrElse { error ->
        Log.e(TAG, "Unable to promote automation runtime to foreground", error)
        StartupFailureRecorder.record(this, "runtime:foreground", error)
        false
    }

    private fun createNotification(): Notification {
        val localizedContext = AppLanguageManager.localizedContext(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                localizedContext.getString(TextR.string.runtime_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = localizedContext.getString(TextR.string.runtime_notification_channel_description)
                setShowBadge(false)
            }
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(localizedContext.getString(TextR.string.runtime_notification_title))
            .setContentText(localizedContext.getString(TextR.string.runtime_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val ACTION_REFRESH_LOCALIZED_SURFACES = "com.yagay.yauto.action.REFRESH_LOCALIZED_SURFACES"
        private const val CHANNEL_ID = "yauto_runtime"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "YAutoRuntime"

        fun start(context: Context, boot: Boolean = false): Boolean = runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AutomationRuntimeService::class.java).putExtra("boot", boot),
            )
            true
        }.getOrElse { error ->
            Log.e(TAG, "Unable to start automation runtime service", error)
            StartupFailureRecorder.record(context, "runtime:start", error)
            false
        }
    }
}
