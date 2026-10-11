package com.yagay.yauto

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.diagnostics.CollectorStatus
import com.yagay.yauto.core.diagnostics.DiagnosticSnapshot
import com.yagay.yauto.core.importer.ImportInput
import com.yagay.yauto.core.model.Automation
import com.yagay.yauto.core.model.AutomationId
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.Flow
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.storage.WorkspaceBackup
import com.yagay.yauto.core.storage.WorkspaceBackupCodec
import com.yagay.yauto.core.storage.WorkspaceData
import com.yagay.yauto.core.storage.featureIds
import com.yagay.yauto.core.storage.merge
import com.yagay.yauto.core.storage.references
import com.yagay.yauto.ui.design.R as TextR
import com.yagay.yauto.ui.design.rememberPageNavigation
import com.yagay.yauto.ui.design.PageBackHandler
import com.yagay.yauto.ui.diagnostics.DiagnosticsScreen
import com.yagay.yauto.ui.editor.AutomationEditorScreen
import com.yagay.yauto.ui.editor.FlowEditorScreen
import com.yagay.yauto.ui.editor.GlobalVariablesScreen
import com.yagay.yauto.ui.editor.FeatureAvailabilityTone
import com.yagay.yauto.ui.editor.FeatureAvailabilityUi
import com.yagay.yauto.ui.editor.LocalFeatureAvailability
import com.yagay.yauto.ui.editor.LocalEditorVariableNames
import com.yagay.yauto.ui.editor.LocalFeaturePermissionGateway
import com.yagay.yauto.ui.editor.LocalFeatureTestGateway
import com.yagay.yauto.ui.editor.LocalHardwareKeyCatalogLoader
import com.yagay.yauto.ui.editor.LocalHardwareKeyCapture
import com.yagay.yauto.platform.accessibility.AccessibilityRuntimeBridge
import com.yagay.yauto.platform.xposed.XposedSystemEventRuntimeBridge
import com.yagay.yauto.core.registry.HardwareKeyCaptureResult
import com.yagay.yauto.core.registry.hardwareKeyCapturePriority
import com.yagay.yauto.core.registry.mergeHardwareKeyCaptures
import com.yagay.yauto.ui.home.HomeScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/** One learner at a time; the automation runtime must not fire a mapped
 * rule while its physical button is being recorded by the editor.
 */
internal object HardwareKeyCaptureSession {
    val mutex = Mutex()
    @Volatile var active: Boolean = false

    fun begin() {
        active = true
        RootHardwareKeyEventSource.pauseReadersForLearning()
    }

    fun end() {
        active = false
        RootHardwareKeyEventSource.resumeReadersAfterLearning()
    }
}

/**
 * Single, short-lived learning session. Arm passive observers together so the
 * FIRST physical key press is not lost while a privileged handshake is pending.
 * This differs from automation execution: no provider sends key actions and
 * only one learned identity wins: true Android KeyCode first, Linux raw only as fallback.
 * Background Root listeners are paused throughout the learning session.
 */
internal suspend fun captureHardwareKey(
    graph: AppGraph,
    timeoutMs: Long,
): HardwareKeyCaptureResult? = HardwareKeyCaptureSession.mutex.withLock {
    HardwareKeyCaptureSession.begin()
    try {
        coroutineScope {
            val totalMs = timeoutMs.coerceIn(1_000L, 60_000L)
            val deadline = SystemClock.elapsedRealtime() + totalMs
            fun remainingMs() = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            val observations = Channel<HardwareKeyCaptureResult>(Channel.UNLIMITED)

            // The getevent and Hook observers are *read-only*, one-shot and
            // cancellable. Delaying Root until after the LSPosed half-window
            // caused OEM buttons pressed at the prompt to be lost forever.
            val rootObserver = launch(Dispatchers.IO) {
                try {
                    graph.hardwareKeys.captureRawKey(totalMs)?.let { raw ->
                        if (raw.linuxEvKey > 0) observations.send(raw)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // No Root grant, no usable /dev/input or unsupported ROM.
                }
            }
            val xposedObserver = launch {
                // Subscribe before the authenticated system_server handshake.
                val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                    XposedSystemEventRuntimeBridge.awaitNextHardwareKey(totalMs)
                }
                var started = false
                try {
                    started = graph.xposed.beginHardwareKeyCapture(totalMs)
                    if (started) {
                        waiter.await()?.let { snapshot ->
                            if (snapshot.action == KeyEvent.ACTION_UP &&
                                (snapshot.keyCode > 0 || snapshot.scanCode > 0)) {
                                observations.send(HardwareKeyCaptureResult(
                                    keyCode = snapshot.keyCode,
                                    scanCode = snapshot.scanCode,
                                    deviceId = snapshot.deviceId,
                                    action = snapshot.action,
                                    deviceName = snapshot.deviceName,
                                    deviceDescriptor = snapshot.deviceDescriptor,
                                    vendorId = snapshot.vendorId,
                                    productId = snapshot.productId,
                                    sources = setOf("lsposed"),
                                ))
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // An unavailable Hook must not block Root learning.
                } finally {
                    waiter.cancel()
                    if (started) {
                        // A locally cancelled observer must not leave the remote
                        // one-shot capture lease armed in system_server.
                        withContext(NonCancellable) {
                            withTimeoutOrNull(1_250L) {
                                runCatching { graph.xposed.endHardwareKeyCapture() }
                            }
                        }
                    }
                }
            }
            val accessibilityObserver = launch {
                while (remainingMs() > 0L) {
                    val key = AccessibilityRuntimeBridge.awaitNextKey(remainingMs()) ?: break
                    if (key.action == KeyEvent.ACTION_UP && (key.keyCode > 0 || key.scanCode > 0)) {
                        observations.send(HardwareKeyCaptureResult(
                            keyCode = key.keyCode,
                            scanCode = key.scanCode,
                            deviceId = key.deviceId,
                            action = key.action,
                            deviceName = key.deviceName,
                            deviceDescriptor = key.deviceDescriptor,
                            vendorId = key.vendorId,
                            productId = key.productId,
                            sources = setOf("accessibility"),
                        ))
                        break
                    }
                }
            }
            try {
                val first = withTimeoutOrNull(remainingMs()) { observations.receive() }
                    ?: return@coroutineScope null
                val candidates = mutableListOf(first)
                // An OEM button can emit Linux EV_KEY 735 before the Android
                // framework reports KeyEvent.keyCode 780. A raw Root event is
                // never evidence that Android has no code. Allow the framework
                // receiver time to deliver its authenticated broadcast and
                // always favor a real Android KeyCode over an unconverted scan.
                if ("lsposed" !in first.sources || first.keyCode <= 0) {
                    // Root may finish before the system_server LSPosed
                    // capture handshake and event delivery. Give the Android
                    // observer a longer opportunity before using Linux EV_KEY.
                    val graceMs = if (first.keyCode > 0) 350L else 3_000L
                    withTimeoutOrNull(minOf(graceMs, remainingMs())) {
                        while (true) candidates += observations.receive()
                    }
                }
                // A key-layout guess is not an observed Android KeyEvent.
                // Preserve the genuine Linux EV_KEY as fallback rather than
                // labeling an ambiguous guessed mapping as Android KeyCode.
                candidates.maxByOrNull(::hardwareKeyCapturePriority) ?: first
            } finally {
                rootObserver.cancel()
                xposedObserver.cancel()
                accessibilityObserver.cancel()
                observations.cancel()
            }
        }
    } finally {
        HardwareKeyCaptureSession.end()
    }
}
