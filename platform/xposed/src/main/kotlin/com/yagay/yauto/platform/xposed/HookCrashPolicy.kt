package com.yagay.yauto.platform.xposed

/** Three fatal Java crashes within two minutes trigger scoped Hook safe mode. */
internal data class HookCrashWindow(
    val startedAtMs: Long = 0L,
    val strikes: Int = 0,
    val quarantined: Boolean = false,
)

internal object HookCrashPolicy {
    const val WINDOW_MS = 120_000L
    const val THRESHOLD = 3

    fun record(previous: HookCrashWindow, nowMs: Long): HookCrashWindow {
        if (previous.quarantined) return previous
        val continuing = previous.strikes > 0 && nowMs >= previous.startedAtMs &&
            nowMs - previous.startedAtMs <= WINDOW_MS
        val count = if (continuing) previous.strikes + 1 else 1
        return HookCrashWindow(
            if (continuing) previous.startedAtMs else nowMs,
            count.coerceAtMost(THRESHOLD),
            count >= THRESHOLD,
        )
    }
}
