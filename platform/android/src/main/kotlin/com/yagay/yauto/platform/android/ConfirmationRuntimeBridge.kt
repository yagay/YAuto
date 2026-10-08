package com.yagay.yauto.platform.android

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

object ConfirmationRuntimeBridge {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean?>>()

    fun register(token: String): CompletableDeferred<Boolean?> =
        CompletableDeferred<Boolean?>().also { pending[token] = it }

    fun complete(token: String, value: Boolean?) {
        pending.remove(token)?.complete(value)
    }

    fun cancel(token: String) {
        pending.remove(token)?.cancel()
    }
}
