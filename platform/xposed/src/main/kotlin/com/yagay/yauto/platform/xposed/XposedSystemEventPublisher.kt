package com.yagay.yauto.platform.xposed

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Publishes subscription-gated SystemServer events. This class owns the delivery
 * lifecycle; Xposed interceptors only describe the event they observed.
 */
internal class XposedSystemEventPublisher(
    private val state: XposedInstallationState,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val broadcast: (Context, String, Map<String, Any?>, Long) -> Unit =
        ::broadcastXposedRuntimeEvent,
) {
    fun emit(
        context: Context,
        type: String,
        dedupKey: String,
        extras: Map<String, Any?>,
        dedupWindowMs: Long = 1_000L,
    ) {
        if (type !in state.subscribedSystemEvents.get()) return
        val now = clockMillis()
        if (!XposedEventDedupPolicy.accept(state.systemEventDedup, dedupKey, now, dedupWindowMs)) return
        broadcast(context, type, extras, now)
    }
}

/** Per-key atomic gate: concurrent Hook callbacks cannot publish duplicates. */
internal object XposedEventDedupPolicy {
    fun accept(
        recent: ConcurrentHashMap<String, Long>,
        key: String,
        now: Long,
        windowMs: Long,
    ): Boolean {
        var accepted = false
        recent.compute(key) { _, previous ->
            val elapsed = previous?.let { now - it }
            if (elapsed != null && elapsed >= 0L && elapsed < windowMs) {
                previous
            } else {
                accepted = true
                now
            }
        }
        if (recent.size > 256) {
            recent.entries.removeIf { now - it.value > 60_000L }
        }
        return accepted
    }
}
