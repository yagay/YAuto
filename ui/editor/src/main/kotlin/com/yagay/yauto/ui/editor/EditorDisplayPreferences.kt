package com.yagay.yauto.ui.editor

import android.content.Context

/** UI-only defaults; never alter the meaning of a saved automation. */
object EditorDisplayPreferences {
    private const val FILE = "yauto_editor_preferences"
    private const val ADVANCED = "show_advanced_by_default"
    private const val SYSTEM_APPS = "include_system_apps_by_default"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun showAdvancedByDefault(context: Context): Boolean =
        prefs(context).getBoolean(ADVANCED, false)

    fun setShowAdvancedByDefault(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(ADVANCED, enabled).apply()
    }

    fun showSystemAppsByDefault(context: Context): Boolean =
        prefs(context).getBoolean(SYSTEM_APPS, false)

    fun setShowSystemAppsByDefault(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(SYSTEM_APPS, enabled).apply()
    }
}
