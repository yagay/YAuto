package com.yagay.yauto.platform.xposed

import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight run-time gate for method interceptors. LSPosed doesn't promise that
 * a removed callback can be safely unloaded from every app process. When disabled,
 * installed interceptors must call the original Android method unchanged.
 */
internal class MethodHookSessionRegistry {
    private val tokens = ConcurrentHashMap<String, String>()

    fun canActivate(sessionId: String, token: String): Boolean =
        tokens[sessionId]?.let { it == token } ?: true

    fun activate(sessionId: String, token: String): Boolean =
        tokens.putIfAbsent(sessionId, token)?.let { it == token } ?: true

    fun isActive(sessionId: String, token: String): Boolean = tokens[sessionId] == token

    fun disable(sessionId: String): Boolean = tokens.remove(sessionId) != null
}
