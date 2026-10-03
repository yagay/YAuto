package com.yagay.yauto.platform.android

import android.content.Context
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class ForegroundAppTransition(
    val previous: ForegroundAppSnapshot,
    val current: ForegroundAppSnapshot,
)

internal class ForegroundAppTransitionTracker {
    private var last: ForegroundAppSnapshot? = null

    fun reset() {
        last = null
    }

    fun sample(current: ForegroundAppSnapshot): ForegroundAppTransition? {
        val previous = last
        last = current
        if (previous == null || previous.packageName == current.packageName) return null
        return ForegroundAppTransition(previous, current)
    }
}

class UsageStatsForegroundEventSource(
    private val reader: ForegroundAppReader,
    private val primarySourceAvailable: () -> Boolean = { false },
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) : AndroidEventSource {
    constructor(
        context: Context,
        primarySourceAvailable: () -> Boolean = { false },
        pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    ) : this(SystemUsageStatsForegroundReader(context), primarySourceAvailable, pollIntervalMs)

    override val id: String = SOURCE_ID
    private val started = AtomicBoolean(false)
    private val tracker = ForegroundAppTransitionTracker()
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = newScope
        job = newScope.launch {
            while (isActive) {
                if (primarySourceAvailable() || !reader.hasAccess()) {
                    tracker.reset()
                } else {
                    reader.currentForegroundApp()?.let { current ->
                        tracker.sample(current)?.let { transition -> emitTransition(emitter, transition) }
                    }
                }
                delay(pollIntervalMs.coerceAtLeast(MIN_POLL_INTERVAL_MS))
            }
        }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
        tracker.reset()
    }

    private fun emitTransition(emitter: RuntimeEventEmitter, transition: ForegroundAppTransition) {
        emitter.emit(
            RuntimeEvent(
                typeId = "android.event.app_background",
                payload = mapOf(
                    "package" to ConfigValue.StringValue(transition.previous.packageName),
                    "class" to ConfigValue.StringValue(transition.previous.className.orEmpty()),
                    "nextPackage" to ConfigValue.StringValue(transition.current.packageName),
                ),
                source = id,
            )
        )
        emitter.emit(
            RuntimeEvent(
                typeId = "android.event.app_foreground",
                payload = mapOf(
                    "package" to ConfigValue.StringValue(transition.current.packageName),
                    "class" to ConfigValue.StringValue(transition.current.className.orEmpty()),
                    "previousPackage" to ConfigValue.StringValue(transition.previous.packageName),
                ),
                source = id,
            )
        )
    }

    companion object {
        const val SOURCE_ID = "android.usage.foreground"
        private const val DEFAULT_POLL_INTERVAL_MS = 1_500L
        private const val MIN_POLL_INTERVAL_MS = 250L
    }
}
