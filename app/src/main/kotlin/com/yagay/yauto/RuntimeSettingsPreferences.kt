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
    private const val KEY_PREFER_ROOT = "prefer_root_when_available"
    private const val KEY_TRACE_LEVEL = "trace_level"
    private const val KEY_LOG_SIZE_MB = "log_size_mb"
    private const val KEY_SU_BINARY = "su_binary"
    private const val KEY_SHARED_ROOT = "shared_read_only_root"
    private const val KEY_EMERGENCY_KEY = "emergency_power_key"
    private const val KEY_KEY_PROMPT = "key_event_prompt"
    val supportedLogSizesMb = listOf(1, 2, 5, 10)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Dynamic routing preference. Explicit saved backends are never overridden. */
    fun preferRootWhenAvailable(context: Context): Boolean = prefs(context).getBoolean(KEY_PREFER_ROOT, true)
    fun setPreferRootWhenAvailable(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PREFER_ROOT, enabled).apply()
    }

    /** Custom su only changes YAuto-owned Root processes, never com.android.shell grants. */
    fun suBinary(context: Context): String = prefs(context).getString(KEY_SU_BINARY, "su")
        ?.takeIf(com.yagay.yauto.platform.root.RootShell::isSafeSuBinary) ?: "su"

    fun setSuBinary(context: Context, binary: String) {
        require(com.yagay.yauto.platform.root.RootShell.isSafeSuBinary(binary))
        prefs(context).edit().putString(KEY_SU_BINARY, binary).apply()
    }

    fun sharedRootQueries(context: Context): Boolean = prefs(context).getBoolean(KEY_SHARED_ROOT, true)
    fun setSharedRootQueries(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHARED_ROOT, enabled).apply()
    }

    fun emergencyPowerKey(context: Context): Boolean = prefs(context).getBoolean(KEY_EMERGENCY_KEY, false)
    fun setEmergencyPowerKey(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_EMERGENCY_KEY, enabled).apply()
    }

    fun keyEventPrompt(context: Context): Boolean = prefs(context).getBoolean(KEY_KEY_PROMPT, false)
    fun setKeyEventPrompt(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEY_PROMPT, enabled).apply()
    }

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
