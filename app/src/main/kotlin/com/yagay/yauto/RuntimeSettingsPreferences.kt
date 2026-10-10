package com.yagay.yauto

import android.content.Context
import com.yagay.yauto.core.logging.TraceLevel

/**
 * Persistent settings shared by boot receiver, tracer and settings UI.
 * Defaults preserve YAuto's earlier behaviour.
 */
object RuntimeSettingsPreferences {
    private const val FILE_NAME = "yauto_runtime_preferences"
    private const val KEY_START_AT_BOOT = "start_at_boot"
    private const val KEY_TRACE_LEVEL = "trace_level"
    private const val KEY_LOG_SIZE_MB = "log_size_mb"
    val supportedLogSizesMb = listOf(1, 2, 5, 10)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun startAtBoot(context: Context): Boolean = prefs(context).getBoolean(KEY_START_AT_BOOT, true)
    fun setStartAtBoot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_START_AT_BOOT, enabled).apply()
    }

    fun traceLevel(context: Context): TraceLevel =
        prefs(context).getString(KEY_TRACE_LEVEL, TraceLevel.DEBUG.name)
            ?.let { name -> TraceLevel.entries.firstOrNull { it.name == name } }
            ?: TraceLevel.DEBUG

    fun setTraceLevel(context: Context, level: TraceLevel) {
        prefs(context).edit().putString(KEY_TRACE_LEVEL, level.name).apply()
    }

    fun logSizeMb(context: Context): Int =
        prefs(context).getInt(KEY_LOG_SIZE_MB, 2).takeIf { it in supportedLogSizesMb } ?: 2

    fun setLogSizeMb(context: Context, megabytes: Int) {
        require(megabytes in supportedLogSizesMb) { "Unsupported log size" }
        prefs(context).edit().putInt(KEY_LOG_SIZE_MB, megabytes).apply()
    }
}
