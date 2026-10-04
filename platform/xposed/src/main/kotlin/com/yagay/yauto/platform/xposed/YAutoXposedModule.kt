package com.yagay.yauto.platform.xposed

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.yagay.yauto.core.capability.SystemOperations
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class YAutoXposedModule : XposedModule() {
    private val systemRegistered = AtomicBoolean(false)
    private val appReceivers = ConcurrentHashMap.newKeySet<String>()
    private val installedHooks = ConcurrentHashMap.newKeySet<String>()

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        try {
            val server = param.classLoader.loadClass("com.android.server.SystemServer")
            server.declaredMethods.filter { it.name == "startOtherServices" }.forEach { method ->
                hook(method).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val field = server.getDeclaredField("mSystemContext").apply { isAccessible = true }
                        val context = field.get(chain.thisObject) as? Context
                        if (context != null) registerSystemBridge(context)
                    } catch (error: Exception) {
                        log(Log.ERROR, "YAuto", "System bridge registration failed", error)
                    }
                    result
                }
            }
        } catch (error: Exception) {
            log(Log.ERROR, "YAuto", "System bridge hooks unavailable", error)
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage || param.packageName == "android" || param.packageName == YAUTO_PACKAGE) return
        runCatching {
            val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
            hook(attach).intercept { chain ->
                val result = chain.proceed()
                val application = chain.thisObject as? Application
                if (application != null) {
                    registerAppHookBridge(application, param.packageName, param.classLoader)
                }
                result
            }
        }.onFailure {
            log(Log.ERROR, "YAuto", "Unable to initialize app hook bridge for ${param.packageName}", it)
        }
    }

    private fun registerSystemBridge(context: Context) {
        if (!systemRegistered.compareAndSet(false, true)) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(received: Context?, intent: Intent?) {
                if (intent?.action != SystemBridgeProtocol.ACTION || !isOrderedBroadcast) return
                val response = Bundle().apply { putInt("version", SystemBridgeProtocol.VERSION) }
                try {
                    require(intent.getIntExtra("version", -1) == SystemBridgeProtocol.VERSION) { "Protocol mismatch" }
                    when (intent.getStringExtra("operation")) {
                        SystemBridgeProtocol.PING -> Unit
                        SystemOperations.SLEEP -> {
                            val power = context.getSystemService(PowerManager::class.java)
                            power.javaClass.getMethod("goToSleep", Long::class.javaPrimitiveType)
                                .invoke(power, SystemClock.uptimeMillis())
                        }
                        SystemOperations.WAKE -> {
                            val power = context.getSystemService(PowerManager::class.java)
                            val method = power.javaClass.methods.firstOrNull {
                                it.name == "wakeUp" && it.parameterTypes.firstOrNull() == Long::class.javaPrimitiveType
                            } ?: error("wakeUp unavailable")
                            when (method.parameterCount) {
                                1 -> method.invoke(power, SystemClock.uptimeMillis())
                                3 -> method.invoke(power, SystemClock.uptimeMillis(), 0, "YAuto")
                                else -> error("Unsupported wakeUp signature")
                            }
                        }
                        SystemOperations.EXPAND_NOTIFICATIONS,
                        SystemOperations.EXPAND_QUICK_SETTINGS,
                        SystemOperations.COLLAPSE_PANELS -> {
                            val status = context.getSystemService("statusbar") ?: error("Status bar service unavailable")
                            val methodName = when (intent.getStringExtra("operation")) {
                                SystemOperations.EXPAND_NOTIFICATIONS -> "expandNotificationsPanel"
                                SystemOperations.EXPAND_QUICK_SETTINGS -> "expandSettingsPanel"
                                else -> "collapsePanels"
                            }
                            val method = status.javaClass.methods.firstOrNull { it.name == methodName }
                                ?: error("$methodName unavailable")
                            if (method.parameterCount == 0) method.invoke(status)
                            else method.invoke(status, *arrayOfNulls(method.parameterCount))
                        }
                        SystemOperations.REBOOT,
                        SystemOperations.REBOOT_RECOVERY,
                        SystemOperations.REBOOT_BOOTLOADER -> {
                            val power = context.getSystemService(PowerManager::class.java)
                            val reason = when (intent.getStringExtra("operation")) {
                                SystemOperations.REBOOT_RECOVERY -> "recovery"
                                SystemOperations.REBOOT_BOOTLOADER -> "bootloader"
                                else -> null
                            }
                            power.javaClass.getMethod("reboot", String::class.java).invoke(power, reason)
                        }
                        SystemOperations.SHUTDOWN -> {
                            val power = context.getSystemService(PowerManager::class.java)
                            val shutdown = power.javaClass.methods.firstOrNull {
                                it.name == "shutdown" && it.parameterCount == 4
                            } ?: error("shutdown unavailable")
                            shutdown.invoke(power, false, "YAuto", false, false)
                        }
                        else -> error("Unsupported operation")
                    }
                    response.putBoolean("success", true)
                } catch (error: Exception) {
                    response.putBoolean("success", false)
                    response.putString("error", error.cause?.message ?: error.message)
                    log(Log.ERROR, "YAuto", "System operation failed", error)
                }
                setResultExtras(response)
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, IntentFilter(SystemBridgeProtocol.ACTION), SystemBridgeProtocol.PERMISSION, null, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, IntentFilter(SystemBridgeProtocol.ACTION), SystemBridgeProtocol.PERMISSION, null)
            }
            log(Log.INFO, "YAuto", "System bridge ready")
        } catch (error: Exception) {
            systemRegistered.set(false)
            throw error
        }
    }

    private fun registerAppHookBridge(context: Context, packageName: String, classLoader: ClassLoader) {
        val processName = runCatching { Application.getProcessName() }.getOrDefault(packageName)
        val receiverKey = "$packageName@$processName"
        if (!appReceivers.add(receiverKey)) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(received: Context?, intent: Intent?) {
                if (intent?.action != SystemBridgeProtocol.HOOK_ACTION || !isOrderedBroadcast) return
                val response = Bundle().apply { putInt("version", SystemBridgeProtocol.VERSION) }
                try {
                    require(intent.getIntExtra("version", -1) == SystemBridgeProtocol.VERSION) { "Protocol mismatch" }
                    require(intent.getStringExtra("operation") == SystemBridgeProtocol.HOOK_INSTALL_SESSION) { "Unsupported hook operation" }
                    val sessionId = intent.getStringExtra("sessionId").orEmpty().trim()
                    val eventToken = intent.getStringExtra("eventToken").orEmpty()
                    val className = intent.getStringExtra("className").orEmpty().trim()
                    val methodName = intent.getStringExtra("methodName").orEmpty().trim()
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
                    require(methodName.matches(METHOD_NAME)) { "Invalid method name" }
                    require(parameterCount in -1..64) { "Invalid parameter count" }
                    require(lifecycle in setOf("before", "after")) { "Invalid hook lifecycle" }
                    require(mode in setOf("observe", "replace")) { "Invalid hook mode" }

                    val targetClass = classLoader.loadClass(className)
                    val wantedParams = parameterTypes.split(',').map { it.trim() }.filter { it.isNotBlank() }
                    val methods = targetClass.declaredMethods.filter { method ->
                        method.name == methodName &&
                            (parameterCount < 0 || method.parameterCount == parameterCount) &&
                            (wantedParams.isEmpty() || method.parameterTypes.map { it.name } == wantedParams) &&
                            (returnType.isBlank() || method.returnType.name == returnType)
                    }
                    require(methods.isNotEmpty()) { "No matching method" }

                    var hookedCount = 0
                    methods.forEach { method ->
                        val key = "$receiverKey|$sessionId|${method.toGenericString()}"
                        if (!installedHooks.add(key)) return@forEach
                        installMethodHook(
                            context, packageName, processName, className, method,
                            sessionId, eventToken, lifecycle, mode, replacementType, replacementValue,
                        )
                        hookedCount++
                    }
                    response.putBoolean("success", true)
                    response.putInt("hookedCount", hookedCount)
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
    ) {
        method.isAccessible = true
        hook(method).intercept { chain ->
            if (mode == "replace") {
                emitMethodCalled(context, sessionId, eventToken, packageName, processName, chain.thisObject?.javaClass?.name ?: className, method.name, "before")
                parseReplacement(method.returnType, replacementType, replacementValue)
            } else if (lifecycle == "after") {
                val result = chain.proceed()
                emitMethodCalled(context, sessionId, eventToken, packageName, processName, chain.thisObject?.javaClass?.name ?: className, method.name, "after")
                result
            } else {
                emitMethodCalled(context, sessionId, eventToken, packageName, processName, chain.thisObject?.javaClass?.name ?: className, method.name, "before")
                chain.proceed()
            }
        }
    }

    private fun emitMethodCalled(
        context: Context,
        sessionId: String,
        eventToken: String,
        packageName: String,
        processName: String,
        className: String,
        methodName: String,
        lifecycle: String,
    ) {
        runCatching {
            context.sendBroadcast(
                Intent(SystemBridgeProtocol.HOOK_EVENT_ACTION)
                    .setPackage(YAUTO_PACKAGE)
                    .putExtra("sessionId", sessionId)
                    .putExtra("eventToken", eventToken)
                    .putExtra("package", packageName)
                    .putExtra("processName", processName)
                    .putExtra("className", className)
                    .putExtra("methodName", methodName)
                    .putExtra("lifecycle", lifecycle)
                    .putExtra("timestampEpochMs", System.currentTimeMillis())
            )
        }
    }

    private fun parseReplacement(returnType: Class<*>, type: String, raw: String): Any? {
        if (returnType == Void.TYPE) return null
        val value: Any? = when (type) {
            "null" -> null
            "boolean" -> raw.equals("true", ignoreCase = true)
            "int" -> raw.toInt()
            "long" -> raw.toLong()
            "float" -> raw.toFloat()
            "double" -> raw.toDouble()
            "string" -> raw
            else -> error("Unsupported replacement type")
        }
        if (value == null && returnType.isPrimitive) error("Primitive return type cannot be null")
        if (value != null && !boxed(returnType).isInstance(value)) {
            error("Replacement type does not match ${returnType.name}")
        }
        return value
    }

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        else -> type
    }

    private companion object {
        const val YAUTO_PACKAGE = "com.yagay.yauto"
        val CLASS_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+")
        val METHOD_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]{0,127}")
    }
}
