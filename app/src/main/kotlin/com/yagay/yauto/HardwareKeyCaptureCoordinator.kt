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
import com.yagay.yauto.ui.home.HomeScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
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
}

/** Single capture coordinator combines Android, LSPosed and raw OEM hardware events. */
internal suspend fun captureHardwareKey(
    graph: AppGraph,
    timeoutMs: Long,
): HardwareKeyCaptureResult? = HardwareKeyCaptureSession.mutex.withLock {
    HardwareKeyCaptureSession.active = true
    try {
        coroutineScope {
    val channel = Channel<HardwareKeyCaptureResult>(capacity = 3)
    val jobs = mutableListOf<kotlinx.coroutines.Job>()

    fun launchCapture(block: suspend () -> HardwareKeyCaptureResult?) {
        jobs += launch(start = CoroutineStart.UNDISPATCHED) {
            block()?.let { channel.trySend(it) }
        }
    }

    launchCapture {
        // Accessibility receives DOWN before UP. Wait for the same release phase
        // used by LSPosed/getevent to avoid returning an incomplete press.
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceIn(1_000L, 60_000L)
        var released: com.yagay.yauto.platform.accessibility.AccessibilityKeySnapshot? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            val observed = AccessibilityRuntimeBridge.awaitNextKey(remaining) ?: break
            if (observed.action == KeyEvent.ACTION_UP) {
                released = observed
                break
            }
        }
        released?.let { key ->
            HardwareKeyCaptureResult(
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

    val xposedWaiter = launch(start = CoroutineStart.UNDISPATCHED) {
        XposedSystemEventRuntimeBridge.awaitNextHardwareKey(timeoutMs)?.let { key ->
            channel.trySend(
                HardwareKeyCaptureResult(
                    keyCode = key.keyCode,
                    scanCode = key.scanCode,
                    deviceId = key.deviceId,
                    action = key.action,
                    deviceName = key.deviceName,
                    deviceDescriptor = key.deviceDescriptor,
                    vendorId = key.vendorId,
                    productId = key.productId,
                    sources = setOf("lsposed"),
                )
            )
        }
    }
    jobs += xposedWaiter

    // Arm the Root getevent reader immediately, in parallel with the system Hook
    // handshake. Serial Root availability checks used to miss the key-up event.
    launchCapture { graph.hardwareKeys.captureRawKey(timeoutMs) }
    jobs += launch {
        if (!runCatching { graph.xposed.beginHardwareKeyCapture(timeoutMs) }.getOrDefault(false)) {
            xposedWaiter.cancel()
        }
    }

    val first = withTimeoutOrNull(timeoutMs.coerceIn(1_000L, 60_000L)) {
        channel.receive()
    } ?: run {
        jobs.forEach { it.cancel() }
        channel.close()
        return@coroutineScope null
    }

    // Merge reports from the same press. Android KeyCode remains primary; raw OEM identity stays hidden.
    delay(240L)
    val candidates = buildList {
        add(first)
        while (true) {
            val next = channel.tryReceive().getOrNull() ?: break
            add(next)
        }
    }

    jobs.forEach { it.cancel() }
    channel.close()
    // Enrichment happens AFTER fast one-shot capture, not inside the Root
    // listener. A raw key that cannot be mapped still works for OEM-only rules.
    val enriched = candidates.map { candidate ->
        if (candidate.linuxEvKey > 0 && candidate.keyCode <= 0) {
            runCatching { graph.hardwareKeys.enrichRawCapture(candidate) }.getOrDefault(candidate)
        } else candidate
    }
    mergeHardwareKeyCaptures(enriched)
        }
    } finally {
        HardwareKeyCaptureSession.active = false
    }
}

private fun mergeHardwareKeyCaptures(
    captures: List<HardwareKeyCaptureResult>,
): HardwareKeyCaptureResult? {
    if (captures.isEmpty()) return null
    // Rank observations, but only merge identities proved compatible. A
    // 240-ms time window alone does not prove two rapid presses are the same.
    val ranked = captures.sortedWith(compareByDescending<HardwareKeyCaptureResult> {
        when {
            "lsposed" in it.sources -> 40
            "root.input" in it.sources -> 30
            "shizuku" in it.sources -> 20
            "accessibility" in it.sources -> 10
            else -> 0
        }
    }.thenByDescending { if (it.keyCode > 0) 2 else if (it.scanCode > 0) 1 else 0 })
    val primary = ranked.first()
    val samePress = ranked.filter { candidate ->
        if (candidate === primary) true else when {
            primary.keyCode > 0 && candidate.keyCode > 0 ->
                primary.keyCode == candidate.keyCode
            primary.linuxEvKey > 0 && candidate.linuxEvKey > 0 ->
                primary.linuxEvKey == candidate.linuxEvKey
            primary.scanCode > 0 && candidate.scanCode > 0 ->
                primary.scanCode == candidate.scanCode
            else -> false
        }
    }
    val android = samePress.firstOrNull { it.keyCode > 0 || it.scanCode > 0 }
    val raw = samePress.firstOrNull { it.linuxEvKey > 0 || it.mscScan != 0L }
    return HardwareKeyCaptureResult(
        keyCode = android?.keyCode ?: primary.keyCode,
        scanCode = android?.scanCode ?: primary.scanCode,
        deviceId = android?.deviceId ?: primary.deviceId,
        action = primary.action,
        linuxEvKey = raw?.linuxEvKey ?: primary.linuxEvKey,
        mscScan = raw?.mscScan ?: primary.mscScan,
        deviceName = android?.deviceName.orEmpty().ifBlank { primary.deviceName },
        deviceDescriptor = raw?.deviceDescriptor.orEmpty().ifBlank {
            android?.deviceDescriptor.orEmpty().ifBlank { primary.deviceDescriptor }
        },
        vendorId = android?.vendorId?.takeIf { it > 0 } ?: primary.vendorId,
        productId = android?.productId?.takeIf { it > 0 } ?: primary.productId,
        sources = samePress.flatMapTo(linkedSetOf()) { it.sources },
    )
}

