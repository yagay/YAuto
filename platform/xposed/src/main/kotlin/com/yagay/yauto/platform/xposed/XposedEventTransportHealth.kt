package com.yagay.yauto.platform.xposed

import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Receiver-side proof of EVENT DELIVERY, separate from the LSPosed PING and
 * from counting installed Hooks. A connected Hook that silently loses all
 * broadcasts must not be reported as a verified working event channel.
 *
 * Process-local and read-only: no listener registration, root calls, or
 * automation actions occur here. Never store/log session authentication tokens.
 */
internal object XposedEventTransportHealth {
    data class Snapshot(
        val accepted: Long,
        val forwarded: Long,
        val rejected: Long,
        val runtimeFailures: Long,
        val lastRuntimeFailureEpochMs: Long,
        val lastAcceptedType: String,
        val lastForwardedType: String,
        val lastRejectedReason: String,
        val lastRejectedEpochMs: Long,
    )

    private val accepted = AtomicLong(0)
    private val forwarded = AtomicLong(0)
    private val rejected = AtomicLong(0)
    private val runtimeFailures = AtomicLong(0)
    private val lastRuntimeFailureEpochMs = AtomicLong(0)
    private val lastAcceptedType = AtomicReference("")
    private val lastForwardedType = AtomicReference("")
    private val lastRejectedReason = AtomicReference("")
    private val lastRejectedEpochMs = AtomicLong(0)
    private val lastWarningEpochMs = AtomicLong(0)
    private val lastRuntimeWarningElapsedMs = AtomicLong(-60_000L)

    fun onAccepted(type: String) {
        accepted.incrementAndGet()
        lastAcceptedType.set(type.take(96))
    }

    fun onForwarded(type: String) {
        forwarded.incrementAndGet()
        lastForwardedType.set(type.take(96))
    }

    fun onRuntimeFailure(type: String, error: Exception) {
        runtimeFailures.incrementAndGet()
        lastRuntimeFailureEpochMs.set(System.currentTimeMillis())
        // Never include payload, Hook arguments or credentials; rate-limit
        // failures caused by a fast, repeatedly firing OEM system callback.
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val previous = lastRuntimeWarningElapsedMs.get()
        if (nowElapsed - previous >= 30_000L &&
            lastRuntimeWarningElapsedMs.compareAndSet(previous, nowElapsed)) {
            runCatching {
                Log.e("YAutoBridge", "Authenticated event callback failed: " +
                    "type=${type.take(96)} exception=${error.javaClass.simpleName} " +
                    "(count=${runtimeFailures.get()})")
            }
        }
    }

    fun onRejected(type: String, source: String, senderUid: Int, reason: String) {
        rejected.incrementAndGet()
        val safeReason = reason.take(80)
        lastRejectedReason.set(
            "type=${type.take(96)} source=${source.take(60)} uid=$senderUid reason=$safeReason"
        )
        val now = System.currentTimeMillis()
        lastRejectedEpochMs.set(now)
        // Most failures are identical on a broken ROM; don't flood logcat.
        val last = lastWarningEpochMs.get()
        if (now - last > 30_000L && lastWarningEpochMs.compareAndSet(last, now)) {
            runCatching {
                Log.w("YAutoBridge", "Rejected event channel input: " +
                    lastRejectedReason.get() + " (count=${rejected.get()})")
            }
        }
    }

    fun snapshot(): Snapshot = Snapshot(
        accepted.get(),
        forwarded.get(),
        rejected.get(),
        runtimeFailures.get(),
        lastRuntimeFailureEpochMs.get(),
        lastAcceptedType.get(),
        lastForwardedType.get(),
        lastRejectedReason.get(),
        lastRejectedEpochMs.get(),
    )
}

/**
 * Shared failure reporter for both system_server events and application Hook
 * callbacks. Successful sendBroadcast means "submitted", NOT "delivered".
 * Only the receiving side can confirm acceptance.
 */
internal object XposedEventSendFailureReporter {
    private val lastWarningElapsedMs = AtomicLong(-60_000L)
    fun report(type: String, origin: String, error: Throwable) {
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = lastWarningElapsedMs.get()
        if (now - previous >= 30_000L &&
            lastWarningElapsedMs.compareAndSet(previous, now)) {
            Log.w("YAutoBridge", "Event broadcast submission failed: " +
                "type=${type.take(96)} origin=${origin.take(40)} " +
                "exception=${error.javaClass.simpleName}")
        }
    }
}
