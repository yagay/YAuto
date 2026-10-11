package com.yagay.yauto.platform.accessibility

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Shared, bounded interpreter pool so a script ignoring interruption
 * cannot spawn unlimited threads across repeated automation triggers.
 */
internal object AccessibilityScriptExecutionPool {
    val executor = ThreadPoolExecutor(
        1, 4, 30L, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(16),
        { task -> Thread(task, "YAuto-Accessibility-Script").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
}

/** Protects in-memory console buffers even if a script loops forever. */
internal class BoundedScriptOutputStream(private val maxBytes: Int = 64_000) : OutputStream() {
    private val bytes = ByteArrayOutputStream()
    @Synchronized override fun write(b: Int) {
        if (bytes.size() < maxBytes) bytes.write(b)
    }
    @Synchronized override fun write(data: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        val available = (maxBytes - bytes.size()).coerceAtLeast(0)
        bytes.write(data, off, minOf(available, len))
    }
    @Synchronized fun text(): String = bytes.toString(Charsets.UTF_8.name())
}
