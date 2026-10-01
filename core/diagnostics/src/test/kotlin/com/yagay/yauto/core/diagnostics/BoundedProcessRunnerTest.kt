package com.yagay.yauto.core.diagnostics

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BoundedProcessRunnerTest {
    @Test fun `large stdout and stderr drain without deadlock and are bounded`() = runBlocking {
        val result = withTimeout(10_000) { BoundedProcessRunner.run(listOf("sh", "-c", "yes x | head -c 200000; yes y | head -c 200000 >&2"), 5000) }
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.length < 129_000)
        assertTrue(result.stderr.length < 33_000)
        assertTrue(result.stdout.contains("truncated"))
        assertTrue(result.stderr.contains("truncated"))
    }
    @Test fun `process timeout is reported`() = runBlocking {
        val output = withTimeout(3000) { BoundedProcessRunner.run(listOf("sleep", "10"), 20) }
        assertTrue(output.timedOut)
        assertEquals(-1, output.exitCode)
    }
    @Test fun `parent cancellation interrupts process wait`() = runBlocking {
        val job = async { BoundedProcessRunner.run(listOf("sleep", "10"), 10_000) }
        delay(50)
        withTimeout(3000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }
}
