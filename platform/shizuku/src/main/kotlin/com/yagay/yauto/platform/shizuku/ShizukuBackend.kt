package com.yagay.yauto.platform.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.yagay.yauto.core.capability.*
import com.yagay.yauto.core.diagnostics.*
import com.yagay.yauto.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ShizukuBackend(context: Context) : CapabilityBackend, ShizukuBridgeContract, DiagnosticCollector {
    override val id = "shizuku"
    override val priority = 70
    private val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShizukuShellService::class.java.name))
        .daemon(false).processNameSuffix("shizuku_shell").version(2)
    private val ipcScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionLock = Mutex()
    @Volatile private var service: IShizukuShell? = null
    private var pending: CompletableDeferred<IShizukuShell>? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val connected = IShizukuShell.Stub.asInterface(binder)
            service = connected; pending?.complete(connected)
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null; pending?.completeExceptionally(IllegalStateException(userText("capability.shizuku.disconnected", "Shizuku service disconnected"))) }
    }

    override fun isAvailable(): Boolean = Shizuku.pingBinder() && !Shizuku.isPreV11()
    override fun hasPermission(): Boolean = isAvailable() && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
    fun requestPermission() { check(isAvailable()) { userText("capability.shizuku.not_running", "Shizuku is not running") }; Shizuku.requestPermission(1001) }
    override suspend fun isAvailable(environment: RuntimeEnvironment) = hasPermission()
    override fun supports(request: CapabilityRequest, environment: RuntimeEnvironment) =
        request.capability == CapabilityIds.PRIVILEGED_SHELL ||
            (request.capability == CapabilityIds.SYSTEM_UI && SystemOperations.shellCommand(request.operationId) != null)

    private suspend fun connect(): IShizukuShell = connectionLock.withLock {
        service?.takeIf { it.asBinder().pingBinder() } ?: withContext(Dispatchers.Main.immediate) {
            check(hasPermission()) { userText("capability.shizuku.permission_denied", "Shizuku permission is not granted") }
            val ready = CompletableDeferred<IShizukuShell>()
            pending = ready
            try {
                Shizuku.bindUserService(args, connection)
                withTimeout(5000) { ready.await() }
            } finally { pending = null }
        }
    }

    override suspend fun execute(request: CapabilityRequest, environment: RuntimeEnvironment): CapabilityResult {
        val command = if (request.capability == CapabilityIds.SYSTEM_UI) SystemOperations.shellCommand(request.operationId).orEmpty() else request.payload.string("command")
        if (command.isBlank()) return CapabilityResult(false, message = userText("capability.shell_empty", "Shell command is empty"))
        val binder = connect()
        val requestId = UUID.randomUUID().toString()
        val output = suspendCancellableCoroutine<android.os.Bundle> { continuation ->
            val call = ipcScope.launch {
                try {
                    val result = binder.execute(requestId, command, request.payload.long("timeoutMs", 10_000).coerceIn(1, 120_000))
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
            }
            continuation.invokeOnCancellation {
                ipcScope.launch { runCatching { binder.cancel(requestId) } }
                call.cancel()
            }
        }
        return CapabilityResult(output.getInt("exitCode", -1) == 0 && !output.getBoolean("timedOut"), id,
            ConfigValue.ObjectValue(mapOf("stdout" to ConfigValue.StringValue(output.getString("stdout").orEmpty()),
                "stderr" to ConfigValue.StringValue(output.getString("stderr").orEmpty()), "exitCode" to ConfigValue.NumberValue(output.getInt("exitCode", -1).toDouble()))),
            if (output.getBoolean("timedOut")) userText("capability.timed_out", "Timed out") else output.getString("stderr")?.takeIf { it.isNotBlank() })
    }
    override suspend fun status() = CollectorStatus(id, hasPermission(), if (hasPermission()) userText("diagnostics.shizuku.connected", "Shizuku connected and authorized") else userText("diagnostics.shizuku.unavailable", "Shizuku unavailable or permission denied"))
    override suspend fun collect(context: DiagnosticContext) = listOf(DiagnosticRecord(DiagnosticSource.ANDROID,
        System.currentTimeMillis(), title = userText("diagnostics.shizuku.title", "Shizuku backend"), message = status().message.orEmpty(), context = context,
        attributes = mapOf("connected" to isAvailable().toString(), "permission" to hasPermission().toString())))
}
