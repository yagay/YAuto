package com.yagay.yauto.platform.xposed

import android.content.Context
import android.content.Intent

/** Shared, signature-gated hook result event envelope for target app processes. */
internal fun emitMethodCalled(
    context: Context,
    sessionId: String,
    eventToken: String,
    packageName: String,
    processName: String,
    className: String,
    methodName: String,
    lifecycle: String,
    captured: Map<String, String> = emptyMap(),
) {
    runCatching {
        context.sendBroadcast(
            Intent(SystemBridgeProtocol.HOOK_EVENT_ACTION)
                .setPackage("com.yagay.yauto")
                .putExtra("sessionId", sessionId)
                .putExtra("eventToken", eventToken)
                .putExtra("package", packageName)
                .putExtra("processName", processName)
                .putExtra("className", className)
                .putExtra("methodName", methodName)
                .putExtra("lifecycle", lifecycle)
                .putExtra("timestampEpochMs", System.currentTimeMillis())
                .apply { captured.forEach { (key, value) -> putExtra(key, value) } }
        )
    }
}
