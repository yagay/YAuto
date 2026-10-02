package com.yagay.yauto

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Records failures that happen before the normal diagnostics stack is guaranteed to be ready.
 * This recorder must never throw back into the startup path.
 */
object StartupFailureRecorder {
    private const val TAG = "YAutoStartup"
    private const val DIRECTORY = "diagnostics"
    private const val FILE_NAME = "startup-failures.log"
    private const val PREVIOUS_FILE_NAME = "startup-failures.previous.log"
    private const val MAX_BYTES = 512 * 1024L

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val appContext = context.applicationContext
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                record(appContext, "uncaught:${thread.name}", error)
                previous?.uncaughtException(thread, error)
            }
            installed = true
        }
    }

    fun record(context: Context, component: String, error: Throwable) {
        Log.e(TAG, "Startup component failed: $component", error)
        runCatching {
            synchronized(this) {
                val file = currentFile(context) ?: return@runCatching
                rotateIfNeeded(file)
                file.appendText(
                    buildString {
                        append(System.currentTimeMillis())
                        append('\t')
                        append(component)
                        append('\n')
                        append(error.stackTraceToString())
                        append("\n---\n")
                    }
                )
            }
        }
    }

    fun read(context: Context, maxChars: Int = 120_000): String = runCatching {
        buildList {
            val dir = File(context.applicationContext.filesDir, DIRECTORY)
            val previous = File(dir, PREVIOUS_FILE_NAME)
            val current = File(dir, FILE_NAME)
            if (previous.isFile) add(previous.readText())
            if (current.isFile) add(current.readText())
        }.joinToString("\n").takeLast(maxChars)
    }.getOrDefault("")

    fun hasEntries(context: Context): Boolean = runCatching {
        val dir = File(context.applicationContext.filesDir, DIRECTORY)
        File(dir, FILE_NAME).length() > 0L || File(dir, PREVIOUS_FILE_NAME).length() > 0L
    }.getOrDefault(false)

    private fun currentFile(context: Context): File? {
        val dir = File(context.applicationContext.filesDir, DIRECTORY)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        return File(dir, FILE_NAME)
    }

    private fun rotateIfNeeded(file: File) {
        if (file.length() < MAX_BYTES) return
        val previous = File(file.parentFile, PREVIOUS_FILE_NAME)
        previous.delete()
        file.renameTo(previous)
    }
}
