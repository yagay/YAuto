package com.yagay.yauto.platform.xposed

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * Owns real per-process method Hook sessions, RenderNode interception and management
 * broadcast lifecycle. LSPosed module inheritance is not needed by this component.
 */
internal class XposedMethodHookInstaller(
    private val state: XposedInstallationState,
    private val interceptMethod: (Method, (XposedHookInvocation) -> Any?) -> Unit,
    private val interceptConstructor: (Constructor<*>, (XposedHookInvocation) -> Any?) -> Unit,
    private val reportLog: (Int, String, Throwable?) -> Unit,
) {
    private val installedHooks get() = state.installedHooks
    private val appReceivers get() = state.appReceivers
    private val crashGuards get() = state.crashGuards
    private val methodSessions get() = state.methodSessions
    private val enabledPackageBehaviors get() = state.enabledPackageBehaviors

    private fun log(level: Int, tag: String, message: String, error: Throwable? = null) {
        reportLog(level, message, error)
    }

    fun registerAppHookBridge(context: Context, packageName: String, classLoader: ClassLoader) {
        val processName = runCatching { Application.getProcessName() }.getOrDefault(packageName)
        val receiverKey = "$packageName@$processName"
        if (!appReceivers.add(receiverKey)) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(received: Context?, intent: Intent?) {
                if (intent?.action != SystemBridgeProtocol.HOOK_ACTION || !isOrderedBroadcast) return
                val response = Bundle().apply { putInt("version", SystemBridgeProtocol.VERSION) }
                try {
                    require(intent.getIntExtra("version", -1) == SystemBridgeProtocol.VERSION) { "Protocol mismatch" }
                    val operation = intent.getStringExtra("operation").orEmpty()
                    if (operation == SystemBridgeProtocol.HOOK_CRASH_GUARD_STATUS ||
                        operation == SystemBridgeProtocol.HOOK_CRASH_GUARD_RESET) {
                        val guard = crashGuards[packageName] ?: error("Hook crash guard storage unavailable")
                        val ok = if (operation == SystemBridgeProtocol.HOOK_CRASH_GUARD_RESET)
                            guard.reset() else true
                        response.putBoolean("success", ok)
                        guard.status(response)
                        if (operation == SystemBridgeProtocol.HOOK_CRASH_GUARD_RESET) {
                            response.putBoolean("restartRequired", true)
                        }
                        if (!ok) response.putString("error", "Could not reset Hook crash guard")
                    } else if (operation == SystemBridgeProtocol.SHORTX_PACKAGE_BEHAVIOR_SET) {
                        require(crashGuards[packageName]?.isQuarantined() != true) {
                            "Hook crash-loop safe mode: reset then restart the target app"
                        }
                        val behavior = intent.getStringExtra("behavior").orEmpty()
                        require(
                            behavior == SystemBridgeProtocol.SHORTX_PACKAGE_BEHAVIOR_RENDERNODE_GUARD
                        ) { "Unsupported package behavior" }
                        installShortXRenderNodeGuard(context, packageName, classLoader)
                        val enabled = intent.getBooleanExtra("enabled", false)
                        enabledPackageBehaviors.updateAndGet { current ->
                            if (enabled) current + behavior else current - behavior
                        }
                        response.putBoolean("success", true)
                        response.putString("behavior", behavior)
                        response.putBoolean("enabled", enabled)
                    } else if (operation == SystemBridgeProtocol.HOOK_DISABLE_SESSION) {
                        val sessionId = intent.getStringExtra("sessionId").orEmpty().trim()
                        require(sessionId.matches(Regex("[A-Za-z0-9_.:-]{1,96}"))) { "Invalid session ID" }
                        val disabled = methodSessions.disable(sessionId)
                        response.putBoolean("success", disabled)
                        response.putBoolean("disabled", disabled)
                        if (!disabled) response.putString("error", "Session is not active in this process")
                    } else if (operation == SystemBridgeProtocol.HOOK_QUERY_SESSION) {
                        val sessionId = intent.getStringExtra("sessionId").orEmpty().trim()
                        require(sessionId.matches(Regex("[A-Za-z0-9_.:-]{1,96}"))) { "Invalid session ID" }
                        response.putBoolean("active", methodSessions.hasActiveSession(sessionId))
                        response.putInt("hookedCount", installedHooks.count {
                            it.startsWith("$receiverKey|$sessionId|")
                        })
                        response.putBoolean("success", true)
                    } else {
                    require(operation == SystemBridgeProtocol.HOOK_INSTALL_SESSION) { "Unsupported hook operation" }
                    require(crashGuards[packageName]?.isQuarantined() != true) {
                        "Hook crash-loop safe mode: reset then restart the target app"
                    }
                    val sessionId = intent.getStringExtra("sessionId").orEmpty().trim()
                    val eventToken = intent.getStringExtra("eventToken").orEmpty()
                    val className = intent.getStringExtra("className").orEmpty().trim()
                    val methodName = intent.getStringExtra("methodName").orEmpty().trim()
                    val memberKind = intent.getStringExtra("memberKind").orEmpty().ifBlank { "method" }
                    val captureValues = intent.getBooleanExtra("captureValues", false)
                    val parameterCount = intent.getIntExtra("parameterCount", -1)
                    val parameterTypes = intent.getStringExtra("parameterTypes").orEmpty().trim()
                    val returnType = intent.getStringExtra("returnType").orEmpty().trim()
                    val lifecycle = intent.getStringExtra("lifecycle").orEmpty().ifBlank { "before" }
                    val mode = intent.getStringExtra("mode").orEmpty().ifBlank { "observe" }
                    val replacementType = intent.getStringExtra("replacementType").orEmpty().ifBlank { "null" }
                    val replacementValue = intent.getStringExtra("replacementValue").orEmpty()

                    require(sessionId.matches(Regex("[A-Za-z0-9_.:-]{1,96}"))) { "Invalid session ID" }
                    require(eventToken.length in 16..128) { "Invalid event token" }
                    require(className.matches(CLASS_NAME)) { "Invalid class name" }
                    require(memberKind in setOf("method", "constructor")) { "Invalid hook target" }
                    require(memberKind != "method" || methodName.matches(METHOD_NAME)) { "Invalid method name" }
                    require(memberKind != "constructor" || mode == "observe") {
                        "Constructor hooks only support observation, not replacement"
                    }
                    require(parameterCount in -1..64) { "Invalid parameter count" }
                    require(lifecycle in setOf("before", "after")) { "Invalid hook lifecycle" }
                    require(mode in setOf("observe", "replace", "override_result")) { "Invalid hook mode" }

                    val targetClass = classLoader.loadClass(className)
                    val wantedParams = parameterTypes.split(',').map { it.trim() }.filter { it.isNotBlank() }
                    val members: List<java.lang.reflect.Executable> = if (memberKind == "constructor") {
                        targetClass.declaredConstructors.toList()
                    } else {
                        targetClass.declaredMethods.toList()
                    }
                    val matchingMembers = members.filter { member ->
                        (memberKind == "constructor" || member.name == methodName) &&
                            (parameterCount < 0 || member.parameterCount == parameterCount) &&
                            (wantedParams.isEmpty() || member.parameterTypes.map { it.name } == wantedParams) &&
                            (memberKind == "constructor" || returnType.isBlank() ||
                                (member as Method).returnType.name == returnType)
                    }
                    require(matchingMembers.isNotEmpty()) { "No matching Hook target" }

                    require(methodSessions.canActivate(sessionId, eventToken)) {
                        "Session ID is already active with a different token"
                    }
                    val alreadyActive = methodSessions.isActive(sessionId, eventToken)
                    var hookedCount = 0
                    var newlyHooked = 0
                    val failures = mutableListOf<String>()
                    matchingMembers.forEach { member ->
                        val key = "$receiverKey|$sessionId|${member.toGenericString()}"
                        val installation = XposedHookInstallationGuard.install(installedHooks, key) {
                            if (member is Method) {
                                // Check type compatibility before installing the interceptor,
                                // not when the target process first calls this method.
                                if (mode != "observe") parseReplacement(member.returnType, replacementType, replacementValue)
                                installMethodHook(
                                    context, packageName, processName, className, member,
                                    sessionId, eventToken, lifecycle, mode, replacementType, replacementValue, captureValues,
                                )
                            } else {
                                installConstructorHook(
                                    context, packageName, processName, className,
                                    member as java.lang.reflect.Constructor<*>, sessionId, eventToken, lifecycle, captureValues,
                                )
                            }
                        }
                        when {
                            installation.getOrNull() == true -> {
                                hookedCount++
                                newlyHooked++
                            }
                            installation.getOrNull() == false -> {
                                if (alreadyActive) hookedCount++
                                else failures += "${member.name}: inactive hook cannot be reattached without process restart"
                            }
                            else -> {
                                val error = installation.exceptionOrNull()!!
                                failures += "${member.name}: ${error.message.orEmpty().take(120)}"
                                log(Log.WARN, "YAuto", "Hook target unavailable: $key", error)
                            }
                        }
                    }
                    if (hookedCount > 0) {
                        methodSessions.activate(sessionId, eventToken)
                        crashGuards[packageName]?.arm("session:$sessionId")
                    }
                    response.putBoolean("success", hookedCount > 0)
                    response.putInt("hookedCount", hookedCount)
                    response.putInt("newHookedCount", newlyHooked)
                    response.putInt("failedHookCount", failures.size)
                    if (failures.isNotEmpty()) response.putString("warning", failures.take(3).joinToString("; "))
                    if (hookedCount == 0) response.putString("error",
                        failures.take(2).joinToString("; ").ifBlank { "No new or active hook was installed" })
                    }
                } catch (error: Exception) {
                    response.putBoolean("success", false)
                    response.putString("error", error.cause?.message ?: error.message)
                    log(Log.ERROR, "YAuto", "Method hook installation failed", error)
                }
                setResultExtras(response)
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, IntentFilter(SystemBridgeProtocol.HOOK_ACTION), SystemBridgeProtocol.PERMISSION, null, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, IntentFilter(SystemBridgeProtocol.HOOK_ACTION), SystemBridgeProtocol.PERMISSION, null)
            }
            log(Log.INFO, "YAuto", "App hook bridge ready for $receiverKey")
        } catch (error: Exception) {
            appReceivers.remove(receiverKey)
            log(Log.ERROR, "YAuto", "App hook bridge registration failed for $receiverKey", error)
        }
    }

    private fun installShortXRenderNodeGuard(
        context: Context,
        packageName: String,
        classLoader: ClassLoader,
    ) {
        val clazz = runCatching { classLoader.loadClass("android.graphics.RenderNode") }.getOrNull() ?: return
        clazz.declaredMethods
            .filter { it.name == "addAnimator" && it.returnType == Void.TYPE }
            .forEach { method ->
                val key = "shortx-rendernode-guard|" + method.toGenericString()
                val installation = XposedHookInstallationGuard.install(installedHooks, key) {
                    method.isAccessible = true
                interceptMethod(method) { invocation ->
                    if (
                        SystemBridgeProtocol.SHORTX_PACKAGE_BEHAVIOR_RENDERNODE_GUARD !in
                        enabledPackageBehaviors.get()
                    ) {
                        invocation.proceed()
                    } else {
                        try {
                            invocation.proceed()
                        } catch (error: RuntimeException) {
                            log(
                                Log.WARN,
                                "YAuto",
                                "ShortX-compatible RenderNode.addAnimator crash suppressed",
                                error,
                            )
                            emitPackageRuntimeEvent(
                                context,
                                "android.event.rendernode_crash_suppressed",
                                mapOf(
                                    "package" to packageName,
                                    "exceptionClass" to error.javaClass.name,
                                    "message" to error.message.orEmpty().take(1024),
                                    "method" to method.name,
                                ),
                            )
                            null
                        }
                    }
                }
                }
                installation.exceptionOrNull()?.let { error ->
                    log(Log.WARN, "YAuto", "Hook installation failed: " + key, error)
                }
            }
    }

    private fun installConstructorHook(
        context: Context,
        packageName: String,
        processName: String,
        className: String,
        constructor: java.lang.reflect.Constructor<*>,
        sessionId: String,
        eventToken: String,
        lifecycle: String,
        captureValues: Boolean,
    ) {
        constructor.isAccessible = true
        interceptConstructor(constructor) { invocation ->
            if (!methodSessions.isActive(sessionId, eventToken)) {
                invocation.proceed()
            } else if (lifecycle == "after") {
                val result = invocation.proceed()
                emitMethodCalled(context, sessionId, eventToken, packageName, processName,
                    className, "<init>", "after",
                    if (captureValues) MethodHookValueSnapshot.capture(invocation.args) else emptyMap())
                result
            } else {
                emitMethodCalled(context, sessionId, eventToken, packageName, processName,
                    className, "<init>", "before",
                    if (captureValues) MethodHookValueSnapshot.capture(invocation.args) else emptyMap())
                invocation.proceed()
            }
        }
    }

    private fun installMethodHook(
        context: Context,
        packageName: String,
        processName: String,
        className: String,
        method: Method,
        sessionId: String,
        eventToken: String,
        lifecycle: String,
        mode: String,
        replacementType: String,
        replacementValue: String,
        captureValues: Boolean,
    ) {
        method.isAccessible = true
        interceptMethod(method) { invocation ->
            if (!methodSessions.isActive(sessionId, eventToken)) {
                invocation.proceed()
            } else if (mode == "replace") {
                val result = parseReplacement(method.returnType, replacementType, replacementValue)
                emitMethodCalled(context, sessionId, eventToken, packageName, processName,
                    invocation.thisObject?.javaClass?.name ?: className, method.name, "before",
                    if (captureValues) MethodHookValueSnapshot.capture(invocation.args, result, true) else emptyMap())
                result
            } else if (mode == "override_result") {
                // Unlike replace, run the original method first; only its returned
                // value is overridden. Side effects of the method are preserved.
                invocation.proceed()
                val result = parseReplacement(method.returnType, replacementType, replacementValue)
                emitMethodCalled(context, sessionId, eventToken, packageName, processName,
                    invocation.thisObject?.javaClass?.name ?: className, method.name, "after",
                    if (captureValues) MethodHookValueSnapshot.capture(invocation.args, result, true) else emptyMap())
                result
            } else if (lifecycle == "after") {
                val result = invocation.proceed()
                emitMethodCalled(context, sessionId, eventToken, packageName, processName,
                    invocation.thisObject?.javaClass?.name ?: className, method.name, "after",
                    if (captureValues) MethodHookValueSnapshot.capture(invocation.args, result, true) else emptyMap())
                result
            } else {
                emitMethodCalled(context, sessionId, eventToken, packageName, processName,
                    invocation.thisObject?.javaClass?.name ?: className, method.name, "before",
                    if (captureValues) MethodHookValueSnapshot.capture(invocation.args) else emptyMap())
                invocation.proceed()
            }
        }
    }

    private companion object {
        val CLASS_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+")
        val METHOD_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]{0,127}")
    }
}
