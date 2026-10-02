package com.yagay.yauto

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object AppLanguageManager {
    const val SYSTEM = ""
    const val ENGLISH = "en"
    const val SIMPLIFIED_CHINESE = "zh-CN"

    private const val PREFS = "yauto_language"
    private const val KEY_LANGUAGE_TAG = "language_tag"

    fun currentTag(context: Context): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)
                .applicationLocales
                .toLanguageTags()
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LANGUAGE_TAG, SYSTEM)
                .orEmpty()
        }
    }

    fun set(activity: Activity, languageTag: String) {
        val normalized = normalize(languageTag)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (normalized.isBlank()) LocaleList.getEmptyLocaleList()
                else LocaleList.forLanguageTags(normalized)
            refreshRuntimeSurfaces(activity)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LANGUAGE_TAG, normalized)
                .apply()
            applySaved(activity.applicationContext)
            applyToResources(activity, normalized)
            refreshRuntimeSurfaces(activity)
            activity.recreate()
        }
    }

    fun applySaved(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        applyToResources(context, currentTag(context))
    }

    fun localizedContext(context: Context): Context {
        val languageTag = currentTag(context)
        if (languageTag.isBlank()) return context
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList.forLanguageTags(languageTag))
        return context.createConfigurationContext(configuration)
    }

    private fun normalize(languageTag: String): String = when (languageTag) {
        ENGLISH -> ENGLISH
        SIMPLIFIED_CHINESE -> SIMPLIFIED_CHINESE
        else -> SYSTEM
    }

    private fun refreshRuntimeSurfaces(context: Context) {
        context.startService(
            Intent(context, AutomationRuntimeService::class.java)
                .setAction(AutomationRuntimeService.ACTION_REFRESH_LOCALIZED_SURFACES)
        )
    }

    @Suppress("DEPRECATION")
    private fun applyToResources(context: Context, languageTag: String) {
        val resources = context.resources
        val configuration = Configuration(resources.configuration)
        val locales = if (languageTag.isBlank()) {
            LocaleList.getAdjustedDefault()
        } else {
            LocaleList.forLanguageTags(languageTag)
        }
        configuration.setLocales(locales)
        if (!languageTag.isBlank()) {
            configuration.setLocale(Locale.forLanguageTag(languageTag))
        }
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }
}
