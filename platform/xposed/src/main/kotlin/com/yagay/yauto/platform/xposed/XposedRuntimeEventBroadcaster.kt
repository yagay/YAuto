package com.yagay.yauto.platform.xposed

import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

private val lastXposedSendWarningMs = AtomicLong(0L)

/** One event envelope for package and system Xposed bridges. */
internal fun broadcastXposedRuntimeEvent(
    context: Context,
    type: String,
    extras: Map<String, Any?>,
    timestampEpochMs: Long = System.currentTimeMillis(),
    fromPackage: Boolean = false,
) {
    val intent = Intent(SystemBridgeProtocol.SYSTEM_EVENT_ACTION)
        .setPackage("com.yagay.yauto")
        .putExtra("type", type)
        .putExtra("timestampEpochMs", timestampEpochMs)
    if (fromPackage) {
        intent.putExtra("bridgeSource", "lsposed.package")
        intent.putExtra("package", context.packageName)
    }
    extras.forEach { (key, value) ->
        when (value) {
            is String -> intent.putExtra(key, value)
            is Int -> intent.putExtra(key, value)
            is Long -> intent.putExtra(key, value)
            is Boolean -> intent.putExtra(key, value)
        }
    }
    // The system_server key is delivered through the signature-protected ordered bridge.
    if (!fromPackage) XposedEventAuth.publishedToken().takeIf(String::isNotBlank)
        ?.let { intent.putExtra(XposedEventAuth.EXTRA_TOKEN, it) }
    runCatching { context.sendBroadcast(intent) }.onFailure { error ->
        // Broadcast submission failures were formerly swallowed completely.
        // Never log the event extras: they may include session credentials.
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = lastXposedSendWarningMs.get()
        if (now - previous > 30_000L &&
            lastXposedSendWarningMs.compareAndSet(previous, now)) {
            Log.w("YAutoBridge", "Cannot submit LSPosed event: " +
                "type=${type.take(96)} origin=${if (fromPackage) "package" else "system_server"} " +
                "failure=${error.javaClass.simpleName}")
        }
    }
}

internal fun emitPackageRuntimeEvent(
    context: Context,
    type: String,
    extras: Map<String, Any?>,
) = broadcastXposedRuntimeEvent(context, type, extras, fromPackage = true)
