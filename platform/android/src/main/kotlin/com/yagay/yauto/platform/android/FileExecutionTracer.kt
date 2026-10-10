package com.yagay.yauto.platform.android

import android.content.Context
import com.yagay.yauto.core.logging.ExecutionTracer
import com.yagay.yauto.core.logging.TraceEvent
import com.yagay.yauto.core.logging.TraceLevel
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class FileExecutionTracer(
    context: Context,
    private val json: Json = Json { encodeDefaults = true },
    private val maxBytes: Long = 2L * 1024 * 1024,
    private val maxBytesProvider: () -> Long = { maxBytes },
) : ExecutionTracer {
    private val dir = File(context.filesDir, "logs").apply { mkdirs() }
    private val file = File(dir, "execution.jsonl")
    private val old = File(dir, "execution.previous.jsonl")
    private val lock = Mutex()
    private var writer: BufferedWriter? = null
    private var pendingSinceFlush = 0

    override suspend fun record(event: TraceEvent) = withContext(Dispatchers.IO) {
        val line = json.encodeToString(TraceEvent.serializer(), event)
        lock.withLock {
            rotateIfNeeded(line.length + 1L)
            val target = writer ?: openWriter().also { writer = it }
            target.append(line)
            target.newLine()
            pendingSinceFlush += 1

            // Batch normal trace writes but flush important failures immediately. Keeping the
            // writer open avoids an open/stat/close cycle for every runtime event.
            if (pendingSinceFlush >= FLUSH_BATCH_SIZE || event.level >= TraceLevel.WARN) {
                target.flush()
                pendingSinceFlush = 0
            }
        }
    }

    private fun rotateIfNeeded(incomingChars: Long) {
        if (!file.exists() || file.length() + incomingChars <
            maxBytesProvider().coerceIn(128L * 1024L, 32L * 1024L * 1024L)) return
        writer?.flush()
        writer?.close()
        writer = null
        pendingSinceFlush = 0
        old.delete()
        file.renameTo(old)
    }

    /** Clears both active and rotated logs without racing a concurrent trace append. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        lock.withLock {
            writer?.close()
            writer = null
            pendingSinceFlush = 0
            file.delete()
            old.delete()
        }
    }

    private fun openWriter(): BufferedWriter =
        BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8))

    private companion object {
        const val FLUSH_BATCH_SIZE = 16
    }
}
