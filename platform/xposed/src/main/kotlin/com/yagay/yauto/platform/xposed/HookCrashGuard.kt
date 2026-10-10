package com.yagay.yauto.platform.xposed

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle

/**
 * Persists a crash loop in the target process's device-protected private data.
 * Monitors only app-process Java fatal errors, not native signals or
 * system_server boot loops. Does not swallow the original exception.
 */
internal class HookCrashGuard(context: Context) {
    private val prefs: SharedPreferences = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("yauto_lsposed_crash_guard", Context.MODE_PRIVATE)
    @Volatile private var armed = false
    @Volatile private var quarantined = prefs.getBoolean("quarantined", false)

    fun isQuarantined(): Boolean = quarantined

    fun arm(family: String) {
        if (quarantined) return
        armed = true
        prefs.edit().putString("lastFamily", family.take(96)).apply()
    }

    @Synchronized
    fun recordFatal(error: Throwable) {
        if (!armed || quarantined) return
        val now = System.currentTimeMillis()
        val next = HookCrashPolicy.record(
            HookCrashWindow(
                prefs.getLong("windowStart", 0L),
                prefs.getInt("strikes", 0),
                prefs.getBoolean("quarantined", false),
            ), now,
        )
        // The process is dying: commit synchronously, never async apply.
        val written = prefs.edit()
            .putLong("windowStart", next.startedAtMs)
            .putInt("strikes", next.strikes)
            .putBoolean("quarantined", next.quarantined)
            .putLong("lastCrash", now)
            .putString("lastException", error.javaClass.name.take(160))
            .commit()
        if (written) quarantined = next.quarantined
    }

    fun status(out: Bundle) {
        out.putBoolean("quarantined", quarantined)
        out.putInt("crashCount", prefs.getInt("strikes", 0))
        out.putLong("lastCrash", prefs.getLong("lastCrash", 0))
        out.putString("lastException", prefs.getString("lastException", "").orEmpty())
        out.putString("lastFamily", prefs.getString("lastFamily", "").orEmpty())
        out.putBoolean("restartRequired", quarantined)
    }

    @Synchronized
    fun reset(): Boolean {
        val cleared = prefs.edit().clear().commit()
        if (cleared) {
            quarantined = false
            armed = false
        }
        // Restoring hooks requires restarting the target app process.
        return cleared
    }
}
