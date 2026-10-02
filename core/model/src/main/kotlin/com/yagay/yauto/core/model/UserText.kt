package com.yagay.yauto.core.model

import java.util.Locale

/**
 * Stable localization boundary for non-Android core/platform code.
 *
 * Core emits stable message keys and a default fallback. Android installs a resource-backed
 * resolver at process start, so adding languages only requires Android resource files. Headless
 * JVM tests/tools keep using the fallback without depending on android.* APIs.
 */
typealias UserTextResolver = (code: String, fallback: String?, args: Array<out Any?>) -> String

object UserText {
    @Volatile
    private var resolver: UserTextResolver? = null

    fun install(resolver: UserTextResolver) {
        this.resolver = resolver
    }

    fun reset() {
        resolver = null
    }

    fun text(code: String, fallback: String? = null, vararg args: Any?): String {
        resolver?.let { installed ->
            return installed(code, fallback, args)
        }
        return formatFallback(fallback ?: code, args)
    }

    private fun formatFallback(pattern: String, args: Array<out Any?>): String {
        if (args.isEmpty()) return pattern
        return runCatching { String.format(Locale.getDefault(), pattern, *args) }.getOrElse { pattern }
    }
}

fun installUserTextResolver(resolver: UserTextResolver) = UserText.install(resolver)
fun resetUserTextResolver() = UserText.reset()

fun userText(code: String, fallback: String? = null, vararg args: Any?): String =
    UserText.text(code, fallback, *args)
