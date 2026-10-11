package com.yagay.yauto.platform.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import java.util.concurrent.ConcurrentHashMap

data class XposedHookEvent(
    val sessionId: String,
    val packageName: String,
    val className: String,
    val methodName: String,
    val processName: String,
    val lifecycle: String,
    val timestampEpochMs: Long,
    val captured: Map<String, String> = emptyMap(),
)

object XposedHookRuntimeBridge {
    private val sessionTokens = ConcurrentHashMap<String, String>()
    @Volatile private var listener: ((XposedHookEvent) -> Unit)? = null

    fun attach(value: ((XposedHookEvent) -> Unit)?) {
        listener = value
    }

    /**
     * Reserve without replacing another live session.
     * true = new, false = same token already reserved, null = invalid/conflict.
     */
    internal fun registerSession(sessionId: String, token: String): Boolean? {
        if (!sessionId.matches(Regex("[A-Za-z0-9_.:-]{1,96}")) || token.length !in 32..128) return null
        val previous = sessionTokens.putIfAbsent(sessionId, token)
        return when (previous) {
            null -> true
            token -> false
            else -> null
        }
    }

    fun unregisterSession(sessionId: String) {
        sessionTokens.remove(sessionId)
    }

    /** Only rollback the reservation we created, not a later session. */
    internal fun unregisterSessionIfMatching(sessionId: String, token: String) {
        sessionTokens.remove(sessionId, token)
    }

    internal fun dispatch(intent: Intent) {
        if (intent.action != SystemBridgeProtocol.HOOK_EVENT_ACTION) return
        val sessionId = intent.getStringExtra("sessionId").orEmpty()
        val token = intent.getStringExtra("eventToken").orEmpty()
        if (sessionId.isBlank() || token.isBlank() || sessionTokens[sessionId] != token) {
            // No token contents or private method argument values are logged.
            XposedEventTransportHealth.onRejected(
                "android.event.lsposed_method_called", "lsposed.method_hook",
                android.os.Process.INVALID_UID, "missing or mismatched session event token",
            )
            return
        }
        XposedEventTransportHealth.onAccepted("android.event.lsposed_method_called")
        listener?.let { callback ->
            try {
                callback.invoke(
                    XposedHookEvent(
                sessionId = sessionId,
                packageName = intent.getStringExtra("package").orEmpty(),
                className = intent.getStringExtra("className").orEmpty(),
                methodName = intent.getStringExtra("methodName").orEmpty(),
                processName = intent.getStringExtra("processName").orEmpty(),
                lifecycle = intent.getStringExtra("lifecycle").orEmpty(),
                timestampEpochMs = intent.getLongExtra("timestampEpochMs", System.currentTimeMillis()),
                captured = MethodHookValueSnapshot.allowedKeys.mapNotNull { key ->
                    intent.getStringExtra(key)?.take(256)?.let { key to it }
                }.toMap(),
                )
            )
                XposedEventTransportHealth.onForwarded("android.event.lsposed_method_called")
            } catch (error: Exception) {
                XposedEventTransportHealth.onRuntimeFailure("android.event.lsposed_method_called", error)
            }
        }
    }

    fun toRuntimeEvent(event: XposedHookEvent): RuntimeEvent = RuntimeEvent(
        typeId = "android.event.lsposed_method_called",
        payload = mapOf(
            "sessionId" to ConfigValue.StringValue(event.sessionId),
            "package" to ConfigValue.StringValue(event.packageName),
            "className" to ConfigValue.StringValue(event.className),
            "methodName" to ConfigValue.StringValue(event.methodName),
            "processName" to ConfigValue.StringValue(event.processName),
            "lifecycle" to ConfigValue.StringValue(event.lifecycle),
        ) + event.captured.mapValues { ConfigValue.StringValue(it.value) },
        source = "lsposed.method_hook",
        timestampEpochMs = event.timestampEpochMs,
    )
}

class XposedHookEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent != null) XposedHookRuntimeBridge.dispatch(intent)
    }
}
