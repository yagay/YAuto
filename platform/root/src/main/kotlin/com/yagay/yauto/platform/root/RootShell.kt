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
    private val suBinary: () -> String = { "su" },
) : DiagnosticCommandRunner {
    /** ProcessBuilder receives argv directly; never pass user-provided su paths through a shell. */
    fun commandArguments(command: String): List<String> {
        val binary = suBinary()
        require(isSafeSuBinary(binary)) { "Invalid su executable path" }
        return listOf(binary, "-c", command)
    }

    companion object {
        fun isSafeSuBinary(path: String): Boolean =
            path == "su" || (path.startsWith("/") && path.endsWith("/su") &&
                ".." !in path && path.matches(Regex("/[A-Za-z0-9_./-]{2,240}")))
    }
    override suspend fun run(command: String, timeoutMs: Long): CommandOutput = try {
        val binary = suBinary()
        require(isSafeSuBinary(binary)) { "Invalid su executable path" }
        if (binary == "su" && shared != null && ReadOnlyRootCommand.accepts(command)) {
            shared.execute(command, timeoutMs)
        } else {
            BoundedProcessRunner.run(commandArguments(command), timeoutMs)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        CommandOutput(-1, "", error.message.orEmpty())
    }

    suspend fun isAvailable(): Boolean {
        val sharedResult = run("id", 5_000)
        fun granted(output: CommandOutput): Boolean =
            !output.timedOut && output.exitCode == 0 && "uid=0" in output.stdout
        if (granted(sharedResult)) return true
        if (shared == null || suBinary() != "su") return false
        // A cached libsu shell can be unprivileged if authorization was
        // granted only after its creation. Verify with a fresh Root process
        // before allowing the scheduler to classify the device as rootless
        // and enable Shizuku.
        return try {
            granted(BoundedProcessRunner.run(commandArguments("id"), 5_000))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }
}
