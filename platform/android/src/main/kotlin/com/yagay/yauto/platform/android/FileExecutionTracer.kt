package com.yagay.yauto.platform.android

import android.content.Context
import com.yagay.yauto.core.logging.ExecutionTracer
import com.yagay.yauto.core.logging.TraceEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class FileExecutionTracer(
    context: Context,
    private val json: Json = Json { encodeDefaults = true },
    private val maxBytes: Long = 2L * 1024 * 1024,
) : ExecutionTracer {
    private val dir = File(context.filesDir, "logs").apply { mkdirs() }
    private val file = File(dir, "execution.jsonl")
    private val old = File(dir, "execution.previous.jsonl")

    override suspend fun record(event: TraceEvent) = withContext(Dispatchers.IO) {
        if (file.exists() && file.length() >= maxBytes) {
            old.delete()
            file.renameTo(old)
        }
        file.appendText(json.encodeToString(TraceEvent.serializer(), event) + "\n")
    }
}
