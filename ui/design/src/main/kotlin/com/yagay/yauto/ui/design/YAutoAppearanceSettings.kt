package com.yagay.yauto.ui.design

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** Single appearance preference store used by YAutoTheme and UI cards. */
object YAutoAppearanceSettings {
    private const val FILE = "yauto_appearance"
    private const val MODE = "theme_mode"
    private const val DYNAMIC = "dynamic_color"
    private const val COLORFUL = "colorful_cards"
    var revision by mutableIntStateOf(0)
        private set

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun mode(context: Context): String =
        prefs(context).getString(MODE, "system")?.takeIf { it in setOf("system", "light", "dark") }
            ?: "system"
    fun dynamic(context: Context): Boolean = prefs(context).getBoolean(DYNAMIC, true)
    fun colorful(context: Context): Boolean = prefs(context).getBoolean(COLORFUL, true)

    fun setMode(context: Context, value: String) {
        require(value in setOf("system", "light", "dark"))
        prefs(context).edit().putString(MODE, value).apply()
        revision++
    }
    fun setDynamic(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(DYNAMIC, value).apply()
        revision++
    }
    fun setColorful(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(COLORFUL, value).apply()
        revision++
    }
}
