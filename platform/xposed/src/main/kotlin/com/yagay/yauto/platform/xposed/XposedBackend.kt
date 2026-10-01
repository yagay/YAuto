package com.yagay.yauto.platform.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.yagay.yauto.core.capability.*
import com.yagay.yauto.core.diagnostics.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class XposedBackend(context: Context) : CapabilityBackend, XposedBridgeContract, DiagnosticCollector {
    private val context = context.applicationContext
    @Volatile private var connected = false
    override val id = "lsposed"
    override val priority = 90
    override val protocolVersion = SystemBridgeProtocol.VERSION
    override fun isConnected() = connected
    override fun supportedOperations() = setOf(SystemOperations.SLEEP, SystemOperations.EXPAND_NOTIFICATIONS, SystemOperations.COLLAPSE_PANELS)
    override suspend fun isAvailable(environment: RuntimeEnvironment): Boolean = request(SystemBridgeProtocol.PING)?.getBoolean("success") == true
    override fun supports(request: CapabilityRequest, environment: RuntimeEnvironment) = request.capability == CapabilityIds.SYSTEM_UI && request.operationId in supportedOperations()
    override suspend fun execute(request: CapabilityRequest, environment: RuntimeEnvironment): CapabilityResult {
        val result = request(request.operationId)
        return CapabilityResult(result?.getBoolean("success") == true, id, message = result?.getString("error") ?: if (result == null) "LSPosed bridge did not respond" else null)
    }
    private suspend fun request(operation: String): Bundle? {
        val result = withTimeoutOrNull(2000) {
            suspendCancellableCoroutine<Bundle?> { continuation ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        val data = getResultExtras(false)
                        if (continuation.isActive) continuation.resume(data?.takeIf { it.getInt("version") == protocolVersion })
                    }
                }
                context.sendOrderedBroadcast(Intent(SystemBridgeProtocol.ACTION).setPackage("android")
                    .putExtra("version", protocolVersion).putExtra("operation", operation), null,
                    receiver, Handler(Looper.getMainLooper()), 0, null, null)
            }
        }
        connected = result != null
        return result
    }
    override suspend fun status(): CollectorStatus {
        val available = isAvailable(RuntimeEnvironment(android.os.Build.VERSION.SDK_INT))
        return CollectorStatus(id, available, if (available) "LSPosed system bridge connected" else "Enable YAuto for Android/system_server and restart")
    }
    override suspend fun collect(context: DiagnosticContext) = listOf(DiagnosticRecord(DiagnosticSource.LSPOSED,
        System.currentTimeMillis(), title = "LSPosed system bridge", message = status().message.orEmpty(), context = context))
}
