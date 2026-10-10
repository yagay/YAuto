package com.yagay.yauto.platform.xposed

import android.content.Context
import android.content.Intent

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
    runCatching { context.sendBroadcast(intent) }
}

internal fun emitPackageRuntimeEvent(
    context: Context,
    type: String,
    extras: Map<String, Any?>,
) = broadcastXposedRuntimeEvent(context, type, extras, fromPackage = true)
