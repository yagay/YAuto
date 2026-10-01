package com.yagay.yauto.platform.shizuku

import android.os.Bundle
import com.yagay.yauto.core.diagnostics.BoundedProcessRunner
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/** Shizuku starts this binder in a separate process under its granted root/shell identity. */
class ShizukuShellService : IShizukuShell.Stub() {
    private val running = ConcurrentHashMap<String, Job>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    override fun execute(requestId: String, command: String, timeoutMs: Long): Bundle = runBlocking {
        require(command.isNotBlank() && command.length <= 32_000)
        require(requestId.length in 1..64)
        val job = currentCoroutineContext()[Job]!!
        running[requestId] = job
        if (cancelled.remove(requestId)) job.cancel()
        try {
        val output = BoundedProcessRunner.run(listOf("sh", "-c", command), timeoutMs.coerceIn(1, 120_000))
        Bundle().apply {
            putInt("exitCode", output.exitCode); putString("stdout", output.stdout)
            putString("stderr", output.stderr); putBoolean("timedOut", output.timedOut)
        }
        } finally { running.remove(requestId, job); cancelled.remove(requestId) }
    }
    override fun cancel(requestId: String) {
        if (requestId.length !in 1..64) return
        running[requestId]?.cancel() ?: run { if (cancelled.size < 128) cancelled.add(requestId) }
    }
    override fun destroy() { exitProcess(0) }
}
