package com.yagay.yauto.platform.android

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

object VoiceInputRuntimeBridge {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    fun register(token: String): CompletableDeferred<String?> =
        CompletableDeferred<String?>().also { pending[token] = it }

    fun complete(token: String, value: String?) {
        pending.remove(token)?.complete(value)
    }

    fun cancel(token: String) {
        pending.remove(token)?.cancel()
    }
}
