package com.yagay.yauto.core.diagnostics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Drain both pipes concurrently while retaining bounded output. Always release the process. */
object BoundedProcessRunner {
    suspend fun run(arguments: List<String>, timeoutMs: Long): CommandOutput = runInterruptible(Dispatchers.IO) {
        require(timeoutMs > 0)
        val process = ProcessBuilder(arguments).start()
        val executor = Executors.newFixedThreadPool(2) { runnable -> Thread(runnable, "yauto-command-output").apply { isDaemon = true } }
        val stdout = executor.submit<String> { process.inputStream.readBounded(128_000) }
        val stderr = executor.submit<String> { process.errorStream.readBounded(32_000) }
        try {
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            fun output(future: java.util.concurrent.Future<String>): String = try { future.get(2, TimeUnit.SECONDS) } catch (interrupted: InterruptedException) { throw interrupted } catch (_: Exception) { "[Output unavailable or pipe did not close]" }
            CommandOutput(if (finished) process.exitValue() else -1, output(stdout), output(stderr), !finished)
        } finally {
            process.destroyForcibly()
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
            stdout.cancel(true); stderr.cancel(true); executor.shutdownNow()
        }
    }

    private fun InputStream.readBounded(limit: Int): String = bufferedReader().use { reader ->
        val output = StringBuilder()
        val buffer = CharArray(8192)
        var truncated = false
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            val keep = minOf(count, (limit - output.length).coerceAtLeast(0))
            output.append(buffer, 0, keep)
            if (keep < count) truncated = true
        }
        output.toString() + if (truncated) "\n[Output truncated]" else ""
    }
}
