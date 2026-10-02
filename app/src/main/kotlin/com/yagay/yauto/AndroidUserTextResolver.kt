package com.yagay.yauto

import android.content.Context
import com.yagay.yauto.core.model.installUserTextResolver
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private object AndroidUserTextResolver {
    private val resourceIds = ConcurrentHashMap<String, Int>()

    fun install(context: Context) {
        val appContext = context.applicationContext
        installUserTextResolver { code, fallback, args ->
            val id = resourceIds.getOrPut(code) {
                appContext.resources.getIdentifier(resourceName(code), "string", appContext.packageName)
            }
            if (id != 0) {
                runCatching {
                    if (args.isEmpty()) appContext.getString(id) else appContext.getString(id, *args)
                }.getOrElse { formatFallback(fallback ?: code, args) }
            } else {
                formatFallback(fallback ?: code, args)
            }
        }
    }

    private fun resourceName(code: String): String = buildString {
        append("runtime_")
        var underscore = false
        code.lowercase(Locale.ROOT).forEach { ch ->
            if (ch.isLetterOrDigit()) {
                append(ch)
                underscore = false
            } else if (!underscore) {
                append('_')
                underscore = true
            }
        }
    }.trimEnd('_')

    private fun formatFallback(pattern: String, args: Array<out Any?>): String {
        if (args.isEmpty()) return pattern
        return runCatching { String.format(Locale.getDefault(), pattern, *args) }.getOrElse { pattern }
    }
}

fun installAndroidUserTextResolver(context: Context) = AndroidUserTextResolver.install(context)
