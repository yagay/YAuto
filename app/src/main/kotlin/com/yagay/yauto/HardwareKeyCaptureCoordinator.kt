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
import com.yagay.yauto.core.registry.mergeHardwareKeyCaptures
import com.yagay.yauto.ui.home.HomeScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
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
        try {
            RootHardwareKeyEventSource.resumeReadersAfterLearning()
        } finally {
            active = false
        }
    }
}

/**
 * Learn one Android key from one active observer at a time. An LSPosed handshake
 * proves the system hook is available, not that every OEM key reaches KeyEvent.
 * If a key cannot be observed, use the remaining window for raw Root input and
 * finally Accessibility. Never run three long-lived key learners in parallel.
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

            // Install the one-shot waiter BEFORE starting the system-server Hook:
            // the first release may arrive during the ordered-broadcast handshake.
            val xposedWaiter = async(start = CoroutineStart.UNDISPATCHED) {
                XposedSystemEventRuntimeBridge.awaitNextHardwareKey(totalMs)
            }
            var xposedStarted = false
            val xposedResult = try {
                val started = try {
                    graph.xposed.beginHardwareKeyCapture(totalMs)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                xposedStarted = started
                if (started && remainingMs() > 0L) {
                    // A successful Hook can still miss OEM-only keys. Reserve a
                    // useful part of the same deadline for the next provider.
                    withTimeoutOrNull(minOf(remainingMs(), (totalMs / 2).coerceAtLeast(1_000L))) {
                        xposedWaiter.await()
                    }
                } else null
            } finally {
                xposedWaiter.cancel()
            }
            // Explicitly retire system_server's remaining capture lease before
            // activating Root or Accessibility. A timed-out local waiter alone
            // would otherwise leave the one-shot Hook armed.
            if (xposedStarted && xposedResult == null) {
                try {
                    graph.xposed.endHardwareKeyCapture()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The system capture will still expire at its bounded deadline.
                }
            }
            if (xposedResult != null && xposedResult.action == KeyEvent.ACTION_UP &&
                (xposedResult.keyCode > 0 || xposedResult.scanCode > 0)
            ) {
                return@coroutineScope HardwareKeyCaptureResult(
                    keyCode = xposedResult.keyCode,
                    scanCode = xposedResult.scanCode,
                    deviceId = xposedResult.deviceId,
                    action = xposedResult.action,
                    deviceName = xposedResult.deviceName,
                    deviceDescriptor = xposedResult.deviceDescriptor,
                    vendorId = xposedResult.vendorId,
                    productId = xposedResult.productId,
                    sources = setOf("lsposed"),
                )
            }

            // A failed handshake must not consume the entire learning window.
            // Root getevent is only active during this stage and cancellation
            // terminates its bounded subprocess through BoundedProcessRunner.
            val rootBudget = minOf(remainingMs() - 1_000L, (totalMs / 3).coerceAtLeast(1_000L))
            if (rootBudget >= 1_000L) {
                val raw = withTimeoutOrNull(rootBudget + 500L) {
                    graph.hardwareKeys.captureRawKey(rootBudget)
                }
                if (raw != null) {
                    return@coroutineScope if (raw.linuxEvKey > 0 && raw.keyCode <= 0) {
                        try {
                            graph.hardwareKeys.enrichRawCapture(raw)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            raw
                        }
                    } else raw
                }
            }

            // Accessibility is the final fallback, not a simultaneous competing
            // observer. Only ACTION_UP is recorded so gestures have a release.
            while (remainingMs() > 0L) {
                val key = AccessibilityRuntimeBridge.awaitNextKey(remainingMs()) ?: break
                if (key.action == KeyEvent.ACTION_UP &&
                    (key.keyCode > 0 || key.scanCode > 0)
                ) {
                    return@coroutineScope HardwareKeyCaptureResult(
                        keyCode = key.keyCode,
                        scanCode = key.scanCode,
                        deviceId = key.deviceId,
                        action = key.action,
                        deviceName = key.deviceName,
                        deviceDescriptor = key.deviceDescriptor,
                        vendorId = key.vendorId,
                        productId = key.productId,
                        sources = setOf("accessibility"),
                    )
                }
            }
            null
        }
    } finally {
        HardwareKeyCaptureSession.end()
    }
}
