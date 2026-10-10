package com.yagay.yauto.platform.xposed

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent

/** Owns hardware-key event de-duplication, payload capture and key-learning broadcasts.
 * Does not install hooks; the installer passes observed KeyEvents here.
 */
internal class XposedHardwareKeyEventHandler(
    private val state: XposedInstallationState,
    private val publish: (Context, String, String, Map<String, Any?>, Long) -> Unit,
) {
    private fun emitSystemRuntimeEvent(
        context: Context,
        type: String,
        dedupKey: String,
        extras: Map<String, Any?>,
        dedupWindowMs: Long = 1_000L,
    ) = publish(context, type, dedupKey, extras, dedupWindowMs)

    fun handleSystemKeyEvent(
        context: Context,
        event: KeyEvent,
        className: String,
        methodName: String,
    ) {
        emitHardwareKeyCapture(context, event, className + "#" + methodName)

        val identity = XposedHardwareKeyIdentity.from(
            event.deviceId, event.keyCode, event.scanCode, event.action, event.eventTime,
        )
        val now = SystemClock.elapsedRealtime()
        val previous = state.hardwareKeyEventDedup.put(identity, now)
        if (previous != null && now - previous < 250L) return
        if (state.hardwareKeyEventDedup.size > 128) {
            state.hardwareKeyEventDedup.entries.removeIf { now - it.value > 5_000L }
        }

        val inputDevice = event.device
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> "down"
            KeyEvent.ACTION_UP -> "up"
            else -> "other"
        }
        emitSystemRuntimeEvent(
            context = context,
            type = "android.event.hardware_key",
            dedupKey = "hardware-key:" + identity,
            extras = mapOf(
                "keyCode" to event.keyCode,
                "scanCode" to event.scanCode,
                "deviceId" to event.deviceId,
                "deviceName" to inputDevice?.name.orEmpty(),
                "deviceDescriptor" to inputDevice?.descriptor.orEmpty(),
                "vendorId" to (inputDevice?.vendorId ?: 0),
                "productId" to (inputDevice?.productId ?: 0),
                "action" to action,
                "repeatCount" to event.repeatCount,
                "metaState" to event.metaState,
                "flags" to event.flags,
                "source" to event.source,
                "downTime" to event.downTime,
                "eventTime" to event.eventTime,
                "hookClass" to className,
                "hookMethod" to methodName,
            ),
            dedupWindowMs = 250L,
        )
    }

    private fun emitHardwareKeyCapture(
        context: Context,
        event: KeyEvent,
        methodName: String,
    ) {
        // Match ShortX's prompt behavior: publish the learned key on ACTION_UP.
        if (event.action != KeyEvent.ACTION_UP) return
        val until = state.hardwareKeyCaptureUntilElapsed.get()
        val nowElapsed = SystemClock.elapsedRealtime()
        if (until <= 0L || nowElapsed > until) {
            state.hardwareKeyCaptureUntilElapsed.compareAndSet(until, 0L)
            return
        }
        if (!state.hardwareKeyCaptureUntilElapsed.compareAndSet(until, 0L)) return

        val inputDevice = event.device
        runCatching {
            context.sendBroadcast(
                Intent(SystemBridgeProtocol.SYSTEM_EVENT_ACTION)
                    .setPackage(YAUTO_HOOK_PACKAGE)
                    .putExtra("type", SystemBridgeProtocol.HARDWARE_KEY_CAPTURE_TYPE)
                    .putExtra("keyCode", event.keyCode)
                    .putExtra("scanCode", event.scanCode)
                    .putExtra("deviceId", event.deviceId)
                    .putExtra("deviceName", inputDevice?.name.orEmpty())
                    .putExtra("deviceDescriptor", inputDevice?.descriptor.orEmpty())
                    .putExtra("vendorId", inputDevice?.vendorId ?: 0)
                    .putExtra("productId", inputDevice?.productId ?: 0)
                    .putExtra("action", event.action)
                    .putExtra("method", methodName)
                    .putExtra("timestampEpochMs", System.currentTimeMillis())
            )
        }
    }

}

/** Stable identity across the distinct OEM input-hook entrypoints. */
internal object XposedHardwareKeyIdentity {
    fun from(deviceId: Int, keyCode: Int, scanCode: Int, action: Int, eventTime: Long): String =
        listOf(deviceId, keyCode, scanCode, action, eventTime).joinToString(":")
}
