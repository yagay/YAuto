package com.yagay.yauto

import com.topjohnwu.superuser.Shell
import com.yagay.yauto.core.diagnostics.CommandOutput
import com.yagay.yauto.platform.root.SharedRootRunner
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ShortX-style libsu main shell for small, approved read-only queries.
 *
 * libsu serializes access to its cached main Shell. A timed-out query may
 * still finish inside that queue, but the allow-list in RootShell ensures
 * it cannot repeat a user action or other side effect. Every mutating command
 * still runs in an isolated, bounded su -c subprocess.
 */
internal class LibsuSharedRootRunner : SharedRootRunner {
    override suspend fun execute(command: String, timeoutMs: Long): CommandOutput =
        withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
            suspendCancellableCoroutine { continuation ->
                val stdout = ArrayList<String>()
                val stderr = ArrayList<String>()
                try {
                    Shell.cmd(command).to(stdout, stderr).submit { result ->
                        if (continuation.isActive) {
                            continuation.resume(
                                CommandOutput(
                                    exitCode = result.code,
                                    stdout = stdout.joinToString("\n").take(128_000),
                                    stderr = stderr.joinToString("\n").take(32_000),
                                )
                            )
                        }
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        } ?: CommandOutput(-1, "", "Root read-only query timed out", timedOut = true)
}
