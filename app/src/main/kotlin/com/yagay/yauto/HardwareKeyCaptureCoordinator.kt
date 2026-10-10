package com.yagay.yauto

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

/** Single capture coordinator combines Android, LSPosed and raw OEM hardware events. */
internal suspend fun captureHardwareKey(
    graph: AppGraph,
    timeoutMs: Long,
): HardwareKeyCaptureResult? = coroutineScope {
    val channel = Channel<HardwareKeyCaptureResult>(capacity = 3)
    val jobs = mutableListOf<kotlinx.coroutines.Job>()

    fun launchCapture(block: suspend () -> HardwareKeyCaptureResult?) {
        jobs += launch(start = CoroutineStart.UNDISPATCHED) {
            block()?.let { channel.trySend(it) }
        }
    }

    launchCapture {
        AccessibilityRuntimeBridge.awaitNextKey(timeoutMs)?.let { key ->
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

    val xposedArmed = runCatching {
        graph.xposed.beginHardwareKeyCapture(timeoutMs)
    }.getOrDefault(false)
    if (!xposedArmed) xposedWaiter.cancel()

    if (runCatching { graph.rootShell.isAvailable() }.getOrDefault(false)) {
        launchCapture { graph.hardwareKeys.captureRawKey(timeoutMs) }
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
    mergeHardwareKeyCaptures(candidates)
}

private fun mergeHardwareKeyCaptures(
    captures: List<HardwareKeyCaptureResult>,
): HardwareKeyCaptureResult? {
    if (captures.isEmpty()) return null
    val android = captures
        .filter { it.keyCode > 0 || it.scanCode > 0 }
        .maxByOrNull { capture ->
            val sourceRank = when {
                "lsposed" in capture.sources -> 30
                "accessibility" in capture.sources -> 20
                else -> 10
            }
            sourceRank + (if (capture.keyCode > 0) 2 else 0) + (if (capture.scanCode > 0) 1 else 0)
        }
    val raw = captures.firstOrNull { it.linuxEvKey > 0 || it.mscScan != 0L }
    val primary = android ?: raw ?: captures.first()

    return HardwareKeyCaptureResult(
        keyCode = android?.keyCode ?: primary.keyCode,
        scanCode = android?.scanCode ?: primary.scanCode,
        deviceId = android?.deviceId ?: primary.deviceId,
        action = android?.action ?: primary.action,
        linuxEvKey = raw?.linuxEvKey ?: primary.linuxEvKey,
        mscScan = raw?.mscScan ?: primary.mscScan,
        deviceName = android?.deviceName.orEmpty().ifBlank { primary.deviceName },
        deviceDescriptor = android?.deviceDescriptor.orEmpty().ifBlank { primary.deviceDescriptor },
        vendorId = android?.vendorId?.takeIf { it > 0 } ?: primary.vendorId,
        productId = android?.productId?.takeIf { it > 0 } ?: primary.productId,
        sources = captures.flatMapTo(linkedSetOf()) { it.sources },
    )
}

