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
                val dir = File(context.applicationContext.filesDir, DIRECTORY)
                if (!dir.isDirectory && !dir.mkdirs()) return@runCatching
                val file = File(dir, FILE_NAME)
                if (file.length() >= MAX_BYTES) {
                    val previous = File(dir, PREVIOUS_FILE_NAME)
                    previous.delete()
                    file.renameTo(previous)
                }
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
}
