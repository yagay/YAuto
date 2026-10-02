package com.yagay.yauto.platform.android

import android.content.Context
import java.util.UUID

class ShortcutTokenStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("yauto_shortcuts", Context.MODE_PRIVATE)

    fun tokenFor(id: String): String {
        val key = "token_$id"
        val existing = prefs.getString(key, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(key, created).apply()
        return created
    }

    fun validate(id: String, token: String): Boolean =
        id.isNotBlank() && token.isNotBlank() && prefs.getString("token_$id", null) == token

    fun remove(id: String) { prefs.edit().remove("token_$id").apply() }
}
