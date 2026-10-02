package com.yagay.yauto

import android.content.Context
import com.yagay.yauto.core.model.installUserTextResolver
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private object AndroidUserTextResolver {
    private val resourceIds = ConcurrentHashMap<String, Int>()

    fun install(context: Context) {
        val appContext = context.applicationContext
        installUserTextResolver { code, args ->
            val id = resourceIds.getOrPut(code) {
                appContext.resources.getIdentifier(resourceName(code), "string", appContext.packageName)
            }
            if (id == 0) {
                // Missing translations are a CI error. Keep a stable diagnostic key instead of
                // falling back to language-specific business-source prose.
                code
            } else {
                runCatching {
                    if (args.isEmpty()) appContext.getString(id) else appContext.getString(id, *args)
                }.getOrElse { code }
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
}

fun installAndroidUserTextResolver(context: Context) = AndroidUserTextResolver.install(context)
