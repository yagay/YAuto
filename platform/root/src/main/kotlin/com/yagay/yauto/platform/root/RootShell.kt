package com.yagay.yauto.platform.root

import com.yagay.yauto.core.diagnostics.CommandOutput
import com.yagay.yauto.core.diagnostics.DiagnosticCommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class RootShell : DiagnosticCommandRunner {
    override suspend fun run(command: String, timeoutMs: Long): CommandOutput = withContext(Dispatchers.IO) {
        val process = runCatching { ProcessBuilder("su", "-c", command).start() }.getOrElse {
            return@withContext CommandOutput(-1, "", it.message.orEmpty())
        }
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        val stdout = runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("")
        val stderr = runCatching { process.errorStream.bufferedReader().readText() }.getOrDefault("")
        CommandOutput(if (finished) process.exitValue() else -1, stdout.take(1_000_000), stderr.take(200_000), !finished)
    }

    suspend fun isAvailable(): Boolean = run("id", 3_000).let { it.exitCode == 0 && "uid=0" in it.stdout }
}
