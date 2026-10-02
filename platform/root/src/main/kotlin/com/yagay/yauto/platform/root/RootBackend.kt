package com.yagay.yauto.platform.root

import com.yagay.yauto.core.capability.*
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText

class RootBackend(
    private val shell: RootShell,
) : CapabilityBackend {
    override val id = "root"
    override val priority = 80

    override suspend fun isAvailable(environment: RuntimeEnvironment): Boolean = environment.rootAvailable || shell.isAvailable()
    override fun supports(request: CapabilityRequest, environment: RuntimeEnvironment): Boolean =
        request.capability == CapabilityIds.PRIVILEGED_SHELL ||
            (request.capability == CapabilityIds.SYSTEM_UI && SystemOperations.shellCommand(request.operationId) != null)

    override suspend fun execute(request: CapabilityRequest, environment: RuntimeEnvironment): CapabilityResult {
        val command = if (request.capability == CapabilityIds.SYSTEM_UI) SystemOperations.shellCommand(request.operationId).orEmpty() else request.payload.string("command")
        if (command.isBlank()) return CapabilityResult(false, message = userText("capability.shell_empty"))
        val timeoutMs = request.payload.long("timeoutMs", DEFAULT_TIMEOUT_MS).coerceIn(1, MAX_TIMEOUT_MS)
        val out = shell.run(command, timeoutMs)
        return CapabilityResult(
            success = out.exitCode == 0 && !out.timedOut,
            value = ConfigValue.ObjectValue(mapOf(
                "stdout" to ConfigValue.StringValue(out.stdout),
                "stderr" to ConfigValue.StringValue(out.stderr),
                "exitCode" to ConfigValue.NumberValue(out.exitCode.toDouble()),
            )),
            message = if (out.timedOut) userText("capability.timed_out") else out.stderr.takeIf { it.isNotBlank() },
        )
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val MAX_TIMEOUT_MS = 300_000L
    }
}
