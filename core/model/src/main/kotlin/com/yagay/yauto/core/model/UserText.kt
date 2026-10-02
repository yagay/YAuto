package com.yagay.yauto.core.model

/**
 * Stable localization boundary for non-Android core/platform code.
 *
 * Production Android installs a resource-backed resolver from Application.onCreate(). Core only
 * emits stable message keys plus formatting arguments, so adding a language never requires a
 * business-logic source change. Headless JVM tools/tests fall back to the stable key.
 */
typealias UserTextResolver = (code: String, args: Array<out Any?>) -> String

object UserText {
    @Volatile
    private var resolver: UserTextResolver? = null

    fun install(resolver: UserTextResolver) {
        this.resolver = resolver
    }

    fun reset() {
        resolver = null
    }

    fun text(code: String, vararg args: Any?): String =
        resolver?.invoke(code, args) ?: code
}

fun installUserTextResolver(resolver: UserTextResolver) = UserText.install(resolver)
fun resetUserTextResolver() = UserText.reset()

fun userText(code: String, vararg args: Any?): String = UserText.text(code, *args)
