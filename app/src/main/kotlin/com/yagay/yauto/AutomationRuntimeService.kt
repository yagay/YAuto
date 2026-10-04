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
import com.yagay.yauto.platform.android.*
import com.yagay.yauto.platform.xposed.XposedHookRuntimeBridge
import com.yagay.yauto.platform.xposed.XposedSystemEventRuntimeBridge
import com.yagay.yauto.ui.design.R as TextR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

class AutomationRuntimeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val eventSources = AndroidEventSourceManager()
    private var graph: AppGraph? = null

    override fun onCreate() {
        super.onCreate()
        if (!promoteToForeground()) { stopSelf(); return }
        val appGraph = runCatching { (application as YAutoApplication).graph }
            .onFailure { StartupFailureRecorder.record(this, "runtime:graph", it) }.getOrNull()
        if (appGraph == null) { stopSelf(); return }
        graph = appGraph
        val dispatcher = RuntimeEventDispatcher(appGraph, scope)
        registerSource("system-broadcast") { SystemBroadcastEventSource(this) }
        registerSource("reference-completion-broadcast") { ReferenceCompletionBroadcastEventSource(this) }
        registerSource("network") { NetworkEventSource(this) }
        registerSource("tethering") { TetheringEventSource(this) }
        registerSource("network-profile") { NetworkProfileEventSource(this) }
        registerSource("wifi-scan") { WifiScanEventSource(this) }
        registerSource("clipboard") { ClipboardEventSource(this) }
        registerSource("device-setting") { DeviceSettingEventSource(this) }
        registerSource("reference-runtime-signals") { ReferenceRuntimeSignalEventSource(this) }
        registerSource("audio-device") { AudioDeviceEventSource(this) }
        registerSource("audio-focus") { AudioFocusEventSource() }
        registerSource("bluetooth-device") { BluetoothDeviceEventSource(this) }
        registerSource("communication") { CommunicationEventSource(this) }
        registerSource("sim-subscriptions") { SubscriptionChangeEventSource(this) }
        registerSource("configured-weather") { ConfiguredWeatherEventSource(this, appGraph.workspace) }
        registerSource("midi-device") { MidiDeviceEventSource(this) }
        registerSource("usb-device") { UsbDeviceEventSource(this) }
        registerSource("personal-data") { PersonalDataEventSource(this) }
        registerSource("configured-logcat") { ConfiguredLogcatEventSource(appGraph.workspace) }
        registerSource("configured-ble") { ConfiguredBleEventSource(this, appGraph.workspace) }
        registerSource("configured-cell-tower") { ConfiguredCellTowerEventSource(this, appGraph.workspace) }
        registerSource("configured-broadcast") { ConfiguredBroadcastEventSource(this, appGraph.workspace) }
        registerSource("configured-sensor") { ConfiguredSensorEventSource(this, appGraph.workspace) }
        registerSource("configured-location") { ConfiguredLocationEventSource(this, appGraph.workspace) }
        registerSource("configured-interval") { ConfiguredIntervalEventSource(appGraph.workspace) }
        registerSource("configured-file") { ConfiguredFileEventSource(appGraph.workspace) }
        registerSource("media-store") { MediaStoreEventSource(this) }
        registerSource("http-server") { HttpServerEventSource() }
        registerSource("stopwatch") { StopwatchEventSource() }
        registerSource("configured-data-usage") { ConfiguredDataUsageEventSource(this, appGraph.workspace) }
        registerSource("runtime-parity") { ConfiguredRuntimeParityEventSource(this, appGraph.workspace) }
        registerSource("shortx-time") { ConfiguredShortXTimeEventSource(appGraph.workspace) }
        registerSource("sound-level") { ConfiguredSoundLevelEventSource(this, appGraph.workspace) }
        registerSource("locale-plugin-events") { ConfiguredLocalePluginEventSource(this, appGraph.workspace) }
        registerSource("hinge-angle") { HingeAngleEventSource(this) }
        registerSource("usage-foreground") {
            UsageStatsForegroundEventSource(
                context = this,
                primarySourceAvailable = { AccessibilityRuntimeBridge.currentWindow() != null },
            )
        }
        val emitter = RuntimeEventEmitter { dispatcher.dispatch(it) }
        SurfaceRuntimeBridge.attach(emitter)
        AdvancedParityRuntimeBridge.attach(emitter)
        ModeRuntimeBridge.attach(emitter)
        WearRuntimeBridge.attach(emitter)
        VendorBridgeRuntime.attach(emitter)
        XposedSystemEventRuntimeBridge.attach { event -> dispatcher.dispatch(event) }
        XposedHookRuntimeBridge.attach { event ->
            dispatcher.dispatch(XposedHookRuntimeBridge.toRuntimeEvent(event))
            val mapped = when {
                event.sessionId.contains("-activity_start-") && event.methodName == "onStart" -> RuntimeEvent(
                    "android.event.activity_lifecycle",
                    mapOf(
                        "package" to ConfigValue.StringValue(event.packageName),
                        "processName" to ConfigValue.StringValue(event.processName),
                        "className" to ConfigValue.StringValue(event.className),
                        "lifecycle" to ConfigValue.StringValue("started"),
                    ),
                    source = "lsposed.lifecycle",
                    timestampEpochMs = event.timestampEpochMs,
                )
                event.sessionId.contains("-activity_stop-") && event.methodName == "onStop" -> RuntimeEvent(
                    "android.event.activity_lifecycle",
                    mapOf(
                        "package" to ConfigValue.StringValue(event.packageName),
                        "processName" to ConfigValue.StringValue(event.processName),
                        "className" to ConfigValue.StringValue(event.className),
                        "lifecycle" to ConfigValue.StringValue("stopped"),
                    ),
                    source = "lsposed.lifecycle",
                    timestampEpochMs = event.timestampEpochMs,
                )
                event.sessionId.contains("-activity_destroy-") && event.methodName == "onDestroy" -> RuntimeEvent(
                    "android.event.activity_lifecycle",
                    mapOf(
                        "package" to ConfigValue.StringValue(event.packageName),
                        "processName" to ConfigValue.StringValue(event.processName),
                        "className" to ConfigValue.StringValue(event.className),
                        "lifecycle" to ConfigValue.StringValue("destroyed"),
                    ),
                    source = "lsposed.lifecycle",
                    timestampEpochMs = event.timestampEpochMs,
                )
                event.sessionId.contains("-process_start-") && event.methodName == "onCreate" -> {
                    RuntimeEvent(
                        "android.event.app_process_started",
                        mapOf(
                            "package" to ConfigValue.StringValue(event.packageName),
                            "processName" to ConfigValue.StringValue(event.processName),
                            "className" to ConfigValue.StringValue(event.className),
                        ),
                        source = "lsposed.lifecycle",
                        timestampEpochMs = event.timestampEpochMs,
                    )
                }
                else -> null
            }
            if (mapped != null) dispatcher.dispatch(mapped)
        }
        AccessibilityRuntimeBridge.setListener { previous, current ->
            val currentPayload = mapOf("package" to ConfigValue.StringValue(current.packageName), "class" to ConfigValue.StringValue(current.className.orEmpty()))
            dispatcher.dispatch(RuntimeEvent("android.event.window_changed", currentPayload, source = "accessibility.window"))
            if (previous?.packageName != current.packageName) {
                previous?.let { dispatcher.dispatch(RuntimeEvent("android.event.app_background", mapOf("package" to ConfigValue.StringValue(it.packageName), "class" to ConfigValue.StringValue(it.className.orEmpty()), "nextPackage" to ConfigValue.StringValue(current.packageName)), source = "accessibility.window")) }
                dispatcher.dispatch(RuntimeEvent("android.event.app_foreground", currentPayload, source = "accessibility.window"))
            }
        }
        AccessibilityRuntimeBridge.setFingerprintGestureListener { gesture ->
            dispatcher.dispatch(
                RuntimeEvent(
                    "android.event.fingerprint_gesture",
                    mapOf("gesture" to ConfigValue.StringValue(gesture.gesture)),
                    source = "accessibility.fingerprint",
                    timestampEpochMs = gesture.timestampEpochMs,
                )
            )
        }
        AccessibilityRuntimeBridge.setKeyListener { key ->
            val action = when (key.action) { KeyEvent.ACTION_DOWN -> "down"; KeyEvent.ACTION_UP -> "up"; else -> "other" }
            dispatcher.dispatch(RuntimeEvent("android.event.hardware_key", mapOf("keyCode" to ConfigValue.NumberValue(key.keyCode.toDouble()), "action" to ConfigValue.StringValue(action), "repeatCount" to ConfigValue.NumberValue(key.repeatCount.toDouble()), "metaState" to ConfigValue.NumberValue(key.metaState.toDouble()), "deviceId" to ConfigValue.NumberValue(key.deviceId.toDouble())), source = "accessibility.key"))
        }
        AccessibilityRuntimeBridge.setUiEventListener { event ->
            val typeId = when (event.event) {
                "click" -> "android.event.ui_click"
                "long_click" -> "android.event.ui_long_click"
                "text_changed" -> "android.event.ui_text_changed"
                "focused" -> "android.event.ui_focused"
                "scrolled" -> "android.event.ui_scrolled"
                "content_changed" -> "android.event.screen_content_changed"
                "toast" -> "android.event.toast_shown"
                else -> return@setUiEventListener
            }
            dispatcher.dispatch(
                RuntimeEvent(
                    typeId,
                    mapOf(
                        "package" to ConfigValue.StringValue(event.packageName),
                        "class" to ConfigValue.StringValue(event.className.orEmpty()),
                        "text" to ConfigValue.StringValue(event.text),
                        "description" to ConfigValue.StringValue(event.contentDescription),
                        "viewId" to ConfigValue.StringValue(event.viewId),
                        "screenText" to ConfigValue.StringValue(event.screenText),
                        "left" to ConfigValue.NumberValue(event.left.toDouble()),
                        "top" to ConfigValue.NumberValue(event.top.toDouble()),
                        "right" to ConfigValue.NumberValue(event.right.toDouble()),
                        "bottom" to ConfigValue.NumberValue(event.bottom.toDouble()),
                        "x" to ConfigValue.NumberValue(event.centerX.toDouble()),
                        "y" to ConfigValue.NumberValue(event.centerY.toDouble()),
                    ),
                    source = "accessibility.ui",
                )
            )
        }
        eventSources.startAll(emitter).forEach(::reportSourceFailure)
        dispatcher.dispatch(RuntimeEvent("android.event.runtime_started", source = "android.runtime"), statesOnly = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH_LOCALIZED_SURFACES) { if (!promoteToForeground()) { stopSelf(); return START_NOT_STICKY }; return START_STICKY }
        if (intent?.action == ACTION_SHARE_DISPATCH) {
            val appGraph = graph ?: runCatching { (application as YAutoApplication).graph }.getOrNull()
            if (appGraph != null) {
                val dispatcher = RuntimeEventDispatcher(appGraph, scope)
                val text = intent.getStringExtra(EXTRA_SHARE_TEXT).orEmpty()
                val subject = intent.getStringExtra(EXTRA_SHARE_SUBJECT).orEmpty()
                val mimeType = intent.getStringExtra(EXTRA_SHARE_MIME).orEmpty()
                val uris = intent.getStringArrayListExtra(EXTRA_SHARE_URIS).orEmpty()
                if (text.isNotBlank() || subject.isNotBlank()) {
                    dispatcher.dispatch(
                        RuntimeEvent(
                            "android.event.share_text_received",
                            mapOf(
                                "text" to ConfigValue.StringValue(text),
                                "subject" to ConfigValue.StringValue(subject),
                                "mimeType" to ConfigValue.StringValue(mimeType),
                            ),
                            source = "android.share",
                        )
                    )
                }
                if (uris.isNotEmpty()) {
                    dispatcher.dispatch(
                        RuntimeEvent(
                            "android.event.share_file_received",
                            mapOf(
                                "mimeType" to ConfigValue.StringValue(mimeType),
                                "count" to ConfigValue.NumberValue(uris.size.toDouble()),
                                "uris" to ConfigValue.ListValue(uris.map(ConfigValue::StringValue)),
                            ),
                            source = "android.share",
                        )
                    )
                }
            }
            return START_STICKY
        }
        if (intent?.getBooleanExtra("boot", false) == true) runCatching { (application as YAutoApplication).graph }.onFailure { StartupFailureRecorder.record(this, "runtime:boot-graph", it) }.getOrNull()?.let { RuntimeEventDispatcher(it, scope).dispatch(RuntimeEvent("android.event.boot", source = "android.boot")) }
        return START_STICKY
    }

    override fun onDestroy() { AccessibilityRuntimeBridge.setListener(null); AccessibilityRuntimeBridge.setKeyListener(null); AccessibilityRuntimeBridge.setUiEventListener(null); AccessibilityRuntimeBridge.setFingerprintGestureListener(null); SurfaceRuntimeBridge.attach(null); AdvancedParityRuntimeBridge.attach(null); ModeRuntimeBridge.attach(null); WearRuntimeBridge.attach(null); VendorBridgeRuntime.attach(null); XposedHookRuntimeBridge.attach(null); XposedSystemEventRuntimeBridge.attach(null); eventSources.stopAll().forEach(::reportSourceFailure); eventSources.clear(); graph = null; scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun registerSource(component: String, factory: () -> AndroidEventSource) { eventSources.add(component, factory)?.let(::reportSourceFailure) }
    private fun reportSourceFailure(failure: EventSourceFailure) {
        val sourceId = failure.sourceId ?: failure.component; val phase = failure.phase.name.lowercase(); StartupFailureRecorder.record(this, "event-source:${failure.component}:$phase", failure.error); val appGraph = graph ?: return
        scope.launch {
            appGraph.tracer.record(
                TraceEvent(
                    executionId = ExecutionId("source-${UUID.randomUUID()}"),
                    kind = TraceKind.ERROR,
                    level = TraceLevel.ERROR,
                    timestampEpochMs = System.currentTimeMillis(),
                    message = userText("runtime.event_source_failed", sourceId, failure.error.message ?: failure.error::class.simpleName.orEmpty()),
                    success = false,
                    attributes = mapOf("eventSource" to sourceId, "eventSource.component" to failure.component, "eventSource.phase" to phase, "exception" to failure.error::class.qualifiedName.orEmpty()),
                )
            )
        }
    }
    private fun promoteToForeground(): Boolean = runCatching { startForeground(NOTIFICATION_ID, createNotification()); true }.getOrElse { Log.e(TAG, "Unable to promote automation runtime to foreground", it); StartupFailureRecorder.record(this, "runtime:foreground", it); false }
    private fun createNotification(): Notification {
        val localizedContext = AppLanguageManager.localizedContext(this); val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, localizedContext.getString(TextR.string.runtime_notification_channel_name), NotificationManager.IMPORTANCE_LOW).apply { description = localizedContext.getString(TextR.string.runtime_notification_channel_description); setShowBadge(false) })
        return NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle(localizedContext.getString(TextR.string.runtime_notification_title)).setContentText(localizedContext.getString(TextR.string.runtime_notification_text)).setOngoing(true).setOnlyAlertOnce(true).build()
    }
    companion object {
        const val ACTION_REFRESH_LOCALIZED_SURFACES = "com.yagay.yauto.action.REFRESH_LOCALIZED_SURFACES"
        const val ACTION_SHARE_DISPATCH = "com.yagay.yauto.action.SHARE_DISPATCH"
        private const val EXTRA_SHARE_TEXT = "shareText"
        private const val EXTRA_SHARE_SUBJECT = "shareSubject"
        private const val EXTRA_SHARE_MIME = "shareMime"
        private const val EXTRA_SHARE_URIS = "shareUris"
        private const val CHANNEL_ID = "yauto_runtime"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "YAutoRuntime"

        fun start(context: Context, boot: Boolean = false): Boolean = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, AutomationRuntimeService::class.java).putExtra("boot", boot))
            true
        }.getOrElse {
            Log.e(TAG, "Unable to start automation runtime service", it)
            StartupFailureRecorder.record(context, "runtime:start", it)
            false
        }

        fun startShareDispatch(context: Context, text: String, subject: String, mimeType: String, uris: List<String>): Boolean = runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AutomationRuntimeService::class.java)
                    .setAction(ACTION_SHARE_DISPATCH)
                    .putExtra(EXTRA_SHARE_TEXT, text)
                    .putExtra(EXTRA_SHARE_SUBJECT, subject)
                    .putExtra(EXTRA_SHARE_MIME, mimeType)
                    .putStringArrayListExtra(EXTRA_SHARE_URIS, ArrayList(uris)),
            )
            true
        }.getOrElse {
            Log.e(TAG, "Unable to dispatch share trigger", it)
            StartupFailureRecorder.record(context, "runtime:share-dispatch", it)
            false
        }
    }
}
