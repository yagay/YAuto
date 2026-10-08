package com.yagay.yauto.platform.android

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

object PickerRuntimeBridge {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<List<String>?>>()

    fun register(token: String): CompletableDeferred<List<String>?> =
        CompletableDeferred<List<String>?>().also { pending[token] = it }

    fun complete(token: String, values: List<String>?) {
        pending.remove(token)?.complete(values)
    }

    fun cancel(token: String) {
        pending.remove(token)?.cancel()
    }
}
