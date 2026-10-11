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
        val rejected: Long,
        val lastAcceptedType: String,
        val lastRejectedReason: String,
        val lastRejectedEpochMs: Long,
    )

    private val accepted = AtomicLong(0)
    private val rejected = AtomicLong(0)
    private val lastAcceptedType = AtomicReference("")
    private val lastRejectedReason = AtomicReference("")
    private val lastRejectedEpochMs = AtomicLong(0)
    private val lastWarningEpochMs = AtomicLong(0)

    fun onAccepted(type: String) {
        accepted.incrementAndGet()
        lastAcceptedType.set(type.take(96))
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
            Log.w("YAutoBridge", "Rejected authenticated event channel input: " +
                lastRejectedReason.get() + " (count=${rejected.get()})")
        }
    }

    fun snapshot(): Snapshot = Snapshot(
        accepted.get(),
        rejected.get(),
        lastAcceptedType.get(),
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
