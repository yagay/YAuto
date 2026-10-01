package com.yagay.yauto.platform.shizuku

import android.os.Bundle
import com.yagay.yauto.core.diagnostics.BoundedProcessRunner
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/** Shizuku starts this binder in a separate process under its granted root/shell identity. */
class ShizukuShellService : IShizukuShell.Stub() {
    override fun execute(command: String, timeoutMs: Long): Bundle = runBlocking {
        require(command.isNotBlank() && command.length <= 32_000)
        val output = BoundedProcessRunner.run(listOf("sh", "-c", command), timeoutMs.coerceIn(1, 120_000))
        Bundle().apply {
            putInt("exitCode", output.exitCode); putString("stdout", output.stdout)
            putString("stderr", output.stderr); putBoolean("timedOut", output.timedOut)
        }
    }
    override fun destroy() { exitProcess(0) }
}
