package com.yagay.yauto.platform.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.yagay.yauto.core.capability.*
import com.yagay.yauto.core.diagnostics.*
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class XposedBackend(context: Context) : CapabilityBackend, XposedBridgeContract, DiagnosticCollector {
    private val context = context.applicationContext
    @Volatile private var connected = false
    override val id = "lsposed"
    override val priority = 90
    override val protocolVersion = SystemBridgeProtocol.VERSION

    override fun isConnected() = connected

    override fun supportedOperations() = setOf(
        SystemOperations.SLEEP,
        SystemOperations.WAKE,
        SystemOperations.EXPAND_NOTIFICATIONS,
        SystemOperations.EXPAND_QUICK_SETTINGS,
        SystemOperations.COLLAPSE_PANELS,
        SystemOperations.REBOOT,
        SystemOperations.REBOOT_RECOVERY,
        SystemOperations.REBOOT_BOOTLOADER,
        SystemOperations.SHUTDOWN,
        SystemOperations.SENSORS_OFF_ENABLE,
        SystemOperations.SENSORS_OFF_DISABLE,
        SystemOperations.SENSORS_OFF_QUERY,
    )

    override suspend fun isAvailable(environment: RuntimeEnvironment): Boolean =
        requestSystem(SystemBridgeProtocol.PING)?.getBoolean("success") == true

    suspend fun setSystemEventSubscriptions(eventTypes: Set<String>): Boolean {
        val safe = eventTypes
            .asSequence()
            .map(String::trim)
            .filter { it.startsWith("android.event.") }
            .distinct()
            .take(256)
            .toCollection(ArrayList())
        val result = orderedRequest(
            Intent(SystemBridgeProtocol.ACTION)
                .setPackage("android")
                .putExtra("version", protocolVersion)
                .putExtra("operation", SystemBridgeProtocol.SYSTEM_EVENT_SUBSCRIPTIONS_SET)
                .putStringArrayListExtra("eventTypes", safe)
        ).also { connected = it != null }
        return result?.getBoolean("success") == true
    }

    suspend fun beginHardwareKeyCapture(timeoutMs: Long): Boolean {
        val result = orderedRequest(
            Intent(SystemBridgeProtocol.ACTION)
                .setPackage("android")
                .putExtra("version", protocolVersion)
                .putExtra("operation", SystemBridgeProtocol.HARDWARE_KEY_CAPTURE_START)
                .putExtra("timeoutMs", timeoutMs.coerceIn(1_000L, 60_000L))
        ).also { connected = it != null }
        return result?.getBoolean("success") == true
    }

    override fun supports(request: CapabilityRequest, environment: RuntimeEnvironment): Boolean =
        (request.capability == CapabilityIds.SYSTEM_UI && request.operationId in supportedOperations()) ||
            (request.capability == CapabilityIds.LSPOSED &&
                request.operationId in setOf(
                    SystemBridgeProtocol.SHORTX_BEHAVIOR_SET,
                    SystemBridgeProtocol.STATUS_ICON_SET,
                    SystemBridgeProtocol.STATUS_ICON_REMOVE,
                    SystemBridgeProtocol.APP_PROCESS_START,
                    SystemBridgeProtocol.STATUS_CHIP_SHOW,
                    SystemBridgeProtocol.STATUS_CHIP_HIDE,
                    SystemBridgeProtocol.TILE_LABEL_SET,
                    SystemBridgeProtocol.TILE_LABEL_CLEAR,
                )) ||
            (request.capability == CapabilityIds.LSPOSED_HOOK &&
                request.operationId in setOf(
                    SystemBridgeProtocol.HOOK_INSTALL_SESSION,
                    SystemBridgeProtocol.SHORTX_PACKAGE_BEHAVIOR_SET,
                    SystemBridgeProtocol.HOOK_DISABLE_SESSION,
                    SystemBridgeProtocol.HOOK_QUERY_SESSION,
                    SystemBridgeProtocol.HOOK_CRASH_GUARD_STATUS,
                    SystemBridgeProtocol.HOOK_CRASH_GUARD_RESET,
                ))

    override suspend fun execute(request: CapabilityRequest, environment: RuntimeEnvironment): CapabilityResult {
        val result = when {
            request.capability == CapabilityIds.LSPOSED_HOOK &&
                request.operationId == SystemBridgeProtocol.SHORTX_PACKAGE_BEHAVIOR_SET ->
                requestPackageBehavior(request)
            request.capability == CapabilityIds.LSPOSED_HOOK -> requestHook(request)
            request.capability == CapabilityIds.LSPOSED &&
                request.operationId in setOf(SystemBridgeProtocol.STATUS_CHIP_SHOW, SystemBridgeProtocol.STATUS_CHIP_HIDE) ->
                requestSystem(request.operationId, request.payload, "com.android.systemui", SystemBridgeProtocol.CHIP_ACTION)
            request.capability == CapabilityIds.LSPOSED &&
                request.operationId in setOf(SystemBridgeProtocol.TILE_LABEL_SET, SystemBridgeProtocol.TILE_LABEL_CLEAR) ->
                requestSystem(request.operationId, request.payload, "com.android.systemui", SystemBridgeProtocol.TILE_LABEL_ACTION)
            request.capability == CapabilityIds.LSPOSED -> requestSystem(request.operationId, request.payload)
            else -> requestSystem(request.operationId)
        }
        val success = result?.getBoolean("success") == true
        val value = when {
            request.capability == CapabilityIds.LSPOSED_HOOK && result != null -> ConfigValue.ObjectValue(
                mapOf(
                    "hookedCount" to ConfigValue.NumberValue(result.getInt("hookedCount", 0).toDouble()),
                    "newHookedCount" to ConfigValue.NumberValue(result.getInt("newHookedCount", 0).toDouble()),
                    "failedHookCount" to ConfigValue.NumberValue(result.getInt("failedHookCount", 0).toDouble()),
                    "disabled" to ConfigValue.BooleanValue(result.getBoolean("disabled", false)),
                    "active" to ConfigValue.BooleanValue(result.getBoolean("active", false)),
                    "quarantined" to ConfigValue.BooleanValue(result.getBoolean("quarantined", false)),
                    "crashCount" to ConfigValue.NumberValue(result.getInt("crashCount", 0).toDouble()),
                    "lastCrash" to ConfigValue.NumberValue(result.getLong("lastCrash", 0).toDouble()),
                    "lastException" to ConfigValue.StringValue(result.getString("lastException").orEmpty()),
                    "lastFamily" to ConfigValue.StringValue(result.getString("lastFamily").orEmpty()),
                    "restartRequired" to ConfigValue.BooleanValue(result.getBoolean("restartRequired", false)),
                    "targetPackage" to ConfigValue.StringValue(request.payload.string("package")),
                    "sessionId" to ConfigValue.StringValue(request.payload.string("sessionId")),
                )
            )
            request.operationId == SystemOperations.SENSORS_OFF_QUERY && result != null ->
                ConfigValue.BooleanValue(result.getBoolean("enabled", false))
            else -> ConfigValue.NullValue
        }
        return CapabilityResult(
            success = success,
            backendId = id,
            value = value,
            message = result?.getString("error") ?: if (result == null) userText("capability.lsposed_no_response") else null,
        )
    }

    private suspend fun requestSystem(
        operation: String,
        payload: Map<String, ConfigValue> = emptyMap(),
        destination: String = "android",
        action: String = SystemBridgeProtocol.ACTION,
    ): Bundle? {
        val intent = Intent(action)
            .setPackage(destination)
            .putExtra("version", protocolVersion)
            .putExtra("operation", operation)
        payload.forEach { (key, value) ->
            when (value) {
                is ConfigValue.StringValue -> intent.putExtra(key, value.value)
                is ConfigValue.BooleanValue -> intent.putExtra(key, value.value)
                is ConfigValue.NumberValue -> intent.putExtra(key, value.value)
                else -> Unit
            }
        }
        return orderedRequest(intent).also { connected = it != null }
    }

    private suspend fun requestPackageBehavior(request: CapabilityRequest): Bundle? {
        val targetPackage = request.payload.string("package").trim()
        if (!PACKAGE_NAME.matches(targetPackage)) return Bundle().apply {
            putInt("version", protocolVersion)
            putBoolean("success", false)
            putString("error", "Invalid target package")
        }
        val intent = Intent(SystemBridgeProtocol.HOOK_ACTION)
            .setPackage(targetPackage)
            .putExtra("version", protocolVersion)
            .putExtra("operation", SystemBridgeProtocol.SHORTX_PACKAGE_BEHAVIOR_SET)
            .putExtra("behavior", request.payload.string("behavior"))
            .putExtra("enabled", request.payload["enabled"] is ConfigValue.BooleanValue &&
                (request.payload["enabled"] as ConfigValue.BooleanValue).value)
        return orderedRequest(intent).also { connected = it != null }
    }

    private suspend fun requestHook(request: CapabilityRequest): Bundle? {
        val targetPackage = request.payload.string("package").trim()
        if (!PACKAGE_NAME.matches(targetPackage)) return Bundle().apply {
            putInt("version", protocolVersion)
            putBoolean("success", false)
            putString("error", "Invalid target package")
        }
        val sessionId = request.payload.string("sessionId").trim()
        if (request.operationId in setOf(
                SystemBridgeProtocol.HOOK_DISABLE_SESSION,
                SystemBridgeProtocol.HOOK_QUERY_SESSION,
                SystemBridgeProtocol.HOOK_CRASH_GUARD_STATUS,
                SystemBridgeProtocol.HOOK_CRASH_GUARD_RESET,
            )) {
            val isCrashGuard = request.operationId in setOf(
                SystemBridgeProtocol.HOOK_CRASH_GUARD_STATUS,
                SystemBridgeProtocol.HOOK_CRASH_GUARD_RESET,
            )
            if (!isCrashGuard && !sessionId.matches(Regex("[A-Za-z0-9_.:-]{1,96}"))) return Bundle().apply {
                putInt("version", protocolVersion)
                putBoolean("success", false)
                putString("error", "Invalid session ID")
            }
            val response = orderedRequest(
                Intent(SystemBridgeProtocol.HOOK_ACTION)
                    .setPackage(targetPackage)
                    .putExtra("version", protocolVersion)
                    .putExtra("operation", request.operationId)
                    .putExtra("sessionId", sessionId)
            )
            if (response?.getBoolean("success") == true &&
                request.operationId == SystemBridgeProtocol.HOOK_DISABLE_SESSION) {
                XposedHookRuntimeBridge.unregisterSession(sessionId)
            }
            return response
        }
        val eventToken = request.payload.string("eventToken")
        XposedHookRuntimeBridge.registerSession(sessionId, eventToken)
        val intent = Intent(SystemBridgeProtocol.HOOK_ACTION)
            .setPackage(targetPackage)
            .putExtra("version", protocolVersion)
            .putExtra("operation", request.operationId)
            .putExtra("sessionId", sessionId)
            .putExtra("eventToken", eventToken)
            .putExtra("className", request.payload.string("className"))
            .putExtra("memberKind", request.payload.string("memberKind", "method"))
            .putExtra("captureValues", (request.payload["captureValues"] as? ConfigValue.BooleanValue)?.value ?: false)
            .putExtra("methodName", request.payload.string("methodName"))
            .putExtra("parameterCount", request.payload["parameterCount"].numberOrNull()?.toInt() ?: -1)
            .putExtra("parameterTypes", request.payload.string("parameterTypes"))
            .putExtra("returnType", request.payload.string("returnType"))
            .putExtra("lifecycle", request.payload.string("lifecycle", "before"))
            .putExtra("mode", request.payload.string("mode", "observe"))
            .putExtra("replacementType", request.payload.string("replacementType", "null"))
            .putExtra("replacementValue", request.payload.string("replacementValue"))
        val result = orderedRequest(intent)
        if (result?.getBoolean("success") != true) XposedHookRuntimeBridge.unregisterSession(sessionId)
        return result
    }

    private suspend fun orderedRequest(intent: Intent): Bundle? =
        withTimeoutOrNull(2_500L) {
            suspendCancellableCoroutine { continuation ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        val data = getResultExtras(false)
                        if (continuation.isActive) {
                            continuation.resume(data?.takeIf { it.getInt("version") == protocolVersion })
                        }
                    }
                }
                context.sendOrderedBroadcast(
                    intent,
                    null,
                    receiver,
                    Handler(Looper.getMainLooper()),
                    0,
                    null,
                    null,
                )
            }
        }

    override suspend fun status(): CollectorStatus {
        val available = isAvailable(RuntimeEnvironment(android.os.Build.VERSION.SDK_INT))
        return CollectorStatus(
            id,
            available,
            if (available) userText("diagnostics.xposed.connected") else userText("diagnostics.xposed.scope_required"),
        )
    }

    override suspend fun collect(context: DiagnosticContext) = listOf(
        DiagnosticRecord(
            DiagnosticSource.LSPOSED,
            System.currentTimeMillis(),
            title = userText("diagnostics.xposed.title"),
            message = status().message.orEmpty(),
            context = context,
        )
    )

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    }
}
