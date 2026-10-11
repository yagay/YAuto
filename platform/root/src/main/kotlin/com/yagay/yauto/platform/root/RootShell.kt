package com.yagay.yauto.platform.root

import com.yagay.yauto.core.diagnostics.BoundedProcessRunner
import com.yagay.yauto.core.diagnostics.CommandOutput
import com.yagay.yauto.core.diagnostics.DiagnosticCommandRunner
import kotlinx.coroutines.CancellationException

/**
 * The optional shared process is ONLY for vetted, read-only commands.
 * Side effects, user scripts and long-lived listeners retain an independently
 * cancellable bounded su -c process.
 */
fun interface SharedRootRunner {
    suspend fun execute(command: String, timeoutMs: Long): CommandOutput
}

internal object ReadOnlyRootCommand {
    private val properties = Regex("getprop(?: [A-Za-z0-9_.-]{1,128})?")
    private val settings = Regex("settings get (?:system|secure|global) [A-Za-z0-9_.-]{1,128}")
    fun accepts(command: String): Boolean =
        command == "id" || command == "getenforce" ||
            properties.matches(command) || settings.matches(command)
}

class RootShell(
    private val shared: SharedRootRunner? = null,
) : DiagnosticCommandRunner {
    override suspend fun run(command: String, timeoutMs: Long): CommandOutput = try {
        if (shared != null && ReadOnlyRootCommand.accepts(command)) {
            shared.execute(command, timeoutMs)
        } else {
            BoundedProcessRunner.run(listOf("su", "-c", command), timeoutMs)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        CommandOutput(-1, "", error.message.orEmpty())
    }

    suspend fun isAvailable(): Boolean = run("id", 15_000)
        .let { !it.timedOut && it.exitCode == 0 && "uid=0" in it.stdout }
}
