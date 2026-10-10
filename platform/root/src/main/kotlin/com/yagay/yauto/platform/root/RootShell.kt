package com.yagay.yauto.platform.root

import com.yagay.yauto.core.diagnostics.CommandOutput
import com.yagay.yauto.core.diagnostics.DiagnosticCommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import com.yagay.yauto.core.diagnostics.BoundedProcessRunner
import kotlinx.coroutines.CancellationException

class RootShell : DiagnosticCommandRunner {
    override suspend fun run(command: String, timeoutMs: Long): CommandOutput = try {
        BoundedProcessRunner.run(listOf("su", "-c", command), timeoutMs)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { CommandOutput(-1, "", error.message.orEmpty()) }

    suspend fun isAvailable(): Boolean = run("id", 15_000).let { it.exitCode == 0 && "uid=0" in it.stdout }
}
