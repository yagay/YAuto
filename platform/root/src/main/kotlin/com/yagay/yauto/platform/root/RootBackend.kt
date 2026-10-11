package com.yagay.yauto.platform.root

import com.yagay.yauto.core.capability.*
import com.yagay.yauto.core.diagnostics.CommandOutput
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RootBackend(
    private val shell: RootShell,
) : CapabilityBackend {
    private val availabilityMutex = Mutex()
    @Volatile private var lastCheckMs = 0L
    @Volatile private var lastCheckSuccess = false
    override val id = "root"
    override val priority = 80

    override suspend fun isAvailable(environment: RuntimeEnvironment): Boolean {
        if (environment.rootAvailable) return true
        val now = System.nanoTime() / 1_000_000L
        if (lastCheckMs != 0L && now - lastCheckMs in 0L..5_000L) return lastCheckSuccess
        return availabilityMutex.withLock {
            val current = System.nanoTime() / 1_000_000L
            if (lastCheckMs != 0L && current - lastCheckMs in 0L..5_000L) {
                lastCheckSuccess
            } else {
                val result = shell.isAvailable()
                lastCheckSuccess = result
                lastCheckMs = current
                result
            }
        }
    }

    override fun supports(request: CapabilityRequest, environment: RuntimeEnvironment): Boolean =
        request.capability == CapabilityIds.PRIVILEGED_SHELL ||
            (request.capability == CapabilityIds.SYSTEM_UI && PrivilegedOperationContract.rootSystemUiSupported(request.operationId)) ||
            (request.capability == CapabilityIds.ACCESSIBILITY && ShellAccessibilityOperations.command(request) != null)

    override suspend fun execute(request: CapabilityRequest, environment: RuntimeEnvironment): CapabilityResult {
        val command = when (request.capability) {
            CapabilityIds.SYSTEM_UI -> SystemOperations.shellCommand(request.operationId).orEmpty()
            CapabilityIds.ACCESSIBILITY -> ShellAccessibilityOperations.command(request).orEmpty()
            else -> request.payload.string("command")
        }
        if (command.isBlank()) return CapabilityResult(false, message = userText("capability.shell_empty"))
        val timeoutMs = request.payload.long("timeoutMs", DEFAULT_TIMEOUT_MS).coerceIn(1, MAX_TIMEOUT_MS)
        val out = shell.run(command, timeoutMs)
        val success = out.exitCode == 0 && !out.timedOut
        return CapabilityResult(
            success = success,
            value = if (request.capability == CapabilityIds.ACCESSIBILITY) ConfigValue.BooleanValue(success)
                else ConfigValue.ObjectValue(mapOf(
                "stdout" to ConfigValue.StringValue(out.stdout),
                "stderr" to ConfigValue.StringValue(out.stderr),
                "exitCode" to ConfigValue.NumberValue(out.exitCode.toDouble()),
            )),
            message = rootCommandFailureMessage(out),
        )
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val MAX_TIMEOUT_MS = 300_000L
    }
}

/** Never report a failed su command as success with an empty diagnostic. */
internal fun rootCommandFailureMessage(output: CommandOutput): String? = when {
    output.timedOut -> userText("capability.timed_out")
    output.exitCode == 0 -> null
    output.stderr.isNotBlank() -> output.stderr.trim().take(1024)
    output.stdout.isNotBlank() -> output.stdout.trim().take(1024)
    else -> userText("capability.root_exit_code", output.exitCode)
}
