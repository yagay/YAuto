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
import android.view.InputEvent
import android.view.KeyEvent
import com.yagay.yauto.core.capability.SystemOperations
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class YAutoXposedModule : XposedModule() {
    private val systemRegistered = AtomicBoolean(false)
    private val appReceivers = ConcurrentHashMap.newKeySet<String>()
    private val installedHooks = ConcurrentHashMap.newKeySet<String>()
    private val systemEventDedup = ConcurrentHashMap<String, Long>()
    private val hardwareKeyEventDedup = ConcurrentHashMap<String, Long>()
    private val hardwareKeyCaptureUntilElapsed = AtomicLong(0L)
    private val subscribedSystemEvents = AtomicReference<Set<String>>(emptySet())

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        try {
            val server = param.classLoader.loadClass("com.android.server.SystemServer")
            server.declaredMethods.filter { it.name == "startOtherServices" }.forEach { method ->
                hook(method).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val field = server.getDeclaredField("mSystemContext").apply { isAccessible = true }
                        val context = field.get(chain.thisObject) as? Context
                        if (context != null) registerSystemBridge(context, param.classLoader)
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

    private fun registerSystemBridge(context: Context, classLoader: ClassLoader) {
        if (!systemRegistered.compareAndSet(false, true)) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(received: Context?, intent: Intent?) {
                if (intent?.action != SystemBridgeProtocol.ACTION || !isOrderedBroadcast) return
                val response = Bundle().apply { putInt("version", SystemBridgeProtocol.VERSION) }
                try {
                    require(intent.getIntExtra("version", -1) == SystemBridgeProtocol.VERSION) { "Protocol mismatch" }
                    when (intent.getStringExtra("operation")) {
                        SystemBridgeProtocol.PING -> Unit
                        SystemBridgeProtocol.SYSTEM_EVENT_SUBSCRIPTIONS_SET -> {
                            val values = intent.getStringArrayListExtra("eventTypes").orEmpty()
                                .asSequence()
                                .map(String::trim)
                                .filter { it.startsWith("android.event.") }
                                .distinct()
                                .take(256)
                                .toSet()
                            subscribedSystemEvents.set(values)
                            response.putInt("subscriptionCount", values.size)
                        }
                        SystemBridgeProtocol.HARDWARE_KEY_CAPTURE_START -> {
                            installShortXInputHooks(context, classLoader)
                            val timeoutMs = intent.getLongExtra("timeoutMs", 10_000L)
                                .coerceIn(1_000L, 60_000L)
                            val until = SystemClock.elapsedRealtime() + timeoutMs
                            hardwareKeyCaptureUntilElapsed.set(until)
                            response.putLong("captureUntilElapsedMs", until)
                        }
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
                        SystemOperations.SENSORS_OFF_ENABLE,
                        SystemOperations.SENSORS_OFF_DISABLE,
                        SystemOperations.SENSORS_OFF_QUERY -> {
                            val manager = context.getSystemService("sensor_privacy")
                                ?: error("Sensor privacy service unavailable")
                            val operation = intent.getStringExtra("operation")
                            if (operation == SystemOperations.SENSORS_OFF_QUERY) {
                                val query = manager.javaClass.methods.firstOrNull {
                                    it.name == "isAllSensorPrivacyEnabled" && it.parameterCount == 0
                                } ?: error("isAllSensorPrivacyEnabled unavailable")
                                response.putBoolean("enabled", query.invoke(manager) as? Boolean == true)
                            } else {
                                val enabled = operation == SystemOperations.SENSORS_OFF_ENABLE
                                val setter = manager.javaClass.methods.firstOrNull {
                                    it.name == "setAllSensorPrivacy" &&
                                        it.parameterCount == 1 &&
                                        (it.parameterTypes[0] == Boolean::class.javaPrimitiveType ||
                                            it.parameterTypes[0] == java.lang.Boolean::class.java)
                                } ?: error("setAllSensorPrivacy unavailable")
                                setter.invoke(manager, enabled)
                                response.putBoolean("enabled", enabled)
                            }
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
            installSystemRuntimeHooks(context, classLoader)
            log(Log.INFO, "YAuto", "System bridge ready")
        } catch (error: Exception) {
            systemRegistered.set(false)
            throw error
        }
    }



    private fun installSystemRuntimeHooks(context: Context, classLoader: ClassLoader) {
        installProcessDeathHooks(context, classLoader)
        installTaskRemovedHooks(context, classLoader)
        installBackNavigationHooks(context, classLoader)
        installAssistantHooks(context, classLoader)
        installShortXInputHooks(context, classLoader)
        installShortXObserverHooks(context, classLoader)
    }

    private fun installProcessDeathHooks(context: Context, classLoader: ClassLoader) {
        val clazz = runCatching { classLoader.loadClass("com.android.server.am.ProcessRecord") }.getOrNull() ?: return
        clazz.declaredMethods
            .filter { method ->
                method.name in setOf("killLocked", "kill", "makeInactive") &&
                    method.returnType == Void.TYPE
            }
            .forEach { method ->
                val key = "system-process-death|" + method.toGenericString()
                if (!installedHooks.add(key)) return@forEach
                method.isAccessible = true
                hook(method).intercept { chain ->
                    val process = processSnapshot(chain.thisObject)
                    val result = chain.proceed()
                    if (process.packageName.isNotBlank() || process.processName.isNotBlank()) {
                        emitSystemRuntimeEvent(
                            context = context,
                            type = "android.event.app_process_stopped",
                            dedupKey = "process:" + process.uid + ":" + process.pid + ":" + process.processName,
                            extras = mapOf(
                                "package" to process.packageName,
                                "processName" to process.processName,
                                "uid" to process.uid,
                                "pid" to process.pid,
                                "reason" to method.name,
                            ),
                        )
                    }
                    result
                }
            }
    }

    private fun installTaskRemovedHooks(context: Context, classLoader: ClassLoader) {
        val recentTasks = runCatching { classLoader.loadClass("com.android.server.wm.RecentTasks") }.getOrNull()
        recentTasks?.declaredMethods
            ?.filter { method ->
                method.name == "remove" &&
                    method.parameterTypes.any { it.name == "com.android.server.wm.Task" }
            }
            ?.forEach { method ->
                val key = "system-task-removed|" + method.toGenericString()
                if (!installedHooks.add(key)) return@forEach
                method.isAccessible = true
                hook(method).intercept { chain ->
                    val task = chain.args.firstOrNull { it?.javaClass?.name == "com.android.server.wm.Task" }
                    val snapshot = taskSnapshot(task)
                    val result = chain.proceed()
                    emitSystemRuntimeEvent(
                        context = context,
                        type = "android.event.task_removed",
                        dedupKey = "task:" + snapshot.taskId + ":" + snapshot.packageName,
                        extras = mapOf(
                            "taskId" to snapshot.taskId,
                            "package" to snapshot.packageName,
                            "activity" to snapshot.activityName,
                            "reason" to method.name,
                        ),
                    )
                    result
                }
            }

        val taskClass = runCatching { classLoader.loadClass("com.android.server.wm.Task") }.getOrNull() ?: return
        taskClass.declaredMethods
            .filter { it.name in setOf("removeImmediately", "removeIfPossible") }
            .forEach { method ->
                val key = "system-task-direct-remove|" + method.toGenericString()
                if (!installedHooks.add(key)) return@forEach
                method.isAccessible = true
                hook(method).intercept { chain ->
                    val snapshot = taskSnapshot(chain.thisObject)
                    val result = chain.proceed()
                    emitSystemRuntimeEvent(
                        context = context,
                        type = "android.event.task_removed",
                        dedupKey = "task:" + snapshot.taskId + ":" + snapshot.packageName,
                        extras = mapOf(
                            "taskId" to snapshot.taskId,
                            "package" to snapshot.packageName,
                            "activity" to snapshot.activityName,
                            "reason" to method.name,
                        ),
                    )
                    result
                }
            }
    }

    private fun installBackNavigationHooks(context: Context, classLoader: ClassLoader) {
        val clazz = runCatching { classLoader.loadClass("com.android.server.wm.BackNavigationController") }.getOrNull() ?: return
        clazz.declaredMethods
            .filter { method ->
                method.name in setOf(
                    "startBackNavigation",
                    "onBackNavigationDone",
                    "finishBackNavigation",
                    "clearBackAnimations",
                    "clearBackAnimateTarget",
                )
            }
            .forEach { method ->
                val started = method.name == "startBackNavigation"
                val key = "system-back-nav|" + method.toGenericString()
                if (!installedHooks.add(key)) return@forEach
                method.isAccessible = true
                hook(method).intercept { chain ->
                    if (started) {
                        emitSystemRuntimeEvent(
                            context = context,
                            type = "android.event.back_navigation_started",
                            dedupKey = "back-start",
                            extras = mapOf("method" to method.name),
                            dedupWindowMs = 150L,
                        )
                    }
                    val result = chain.proceed()
                    if (!started) {
                        emitSystemRuntimeEvent(
                            context = context,
                            type = "android.event.back_navigation_finished",
                            dedupKey = "back-finish",
                            extras = mapOf("method" to method.name),
                            dedupWindowMs = 150L,
                        )
                    }
                    result
                }
            }
    }


    private fun installShortXObserverHooks(context: Context, classLoader: ClassLoader) {
        ShortXCompatHookCatalog.systemServerObservers.forEach { spec ->
            spec.classNames.forEach { className ->
                val clazz = runCatching { classLoader.loadClass(className) }.getOrNull() ?: return@forEach
                clazz.declaredMethods
                    .filter { it.name in spec.methodNames }
                    .forEach { method ->
                        val key = "shortx-observer|" + spec.id + "|" + method.toGenericString()
                        if (!installedHooks.add(key)) return@forEach
                        method.isAccessible = true
                        hook(method).intercept { chain ->
                            if (spec.after) {
                                val result = chain.proceed()
                                emitShortXObserverEvent(context, spec, className, method.name, chain.thisObject, chain.args)
                                result
                            } else {
                                emitShortXObserverEvent(context, spec, className, method.name, chain.thisObject, chain.args)
                                chain.proceed()
                            }
                        }
                    }
            }
        }
    }

    private fun emitShortXObserverEvent(
        context: Context,
        spec: ShortXObserverHookSpec,
        className: String,
        methodName: String,
        thisObject: Any?,
        args: Array<out Any?>,
    ) {
        if (spec.eventType !in subscribedSystemEvents.get()) return
        val extras = linkedMapOf<String, Any?>(
            "hookId" to spec.id,
            "className" to className,
            "method" to methodName,
        )
        args.take(8).forEachIndexed { index, value ->
            when (value) {
                is String -> extras["arg$index"] = value.take(512)
                is Int -> extras["arg$index"] = value
                is Long -> extras["arg$index"] = value
                is Boolean -> extras["arg$index"] = value
                is Enum<*> -> extras["arg$index"] = value.name
            }
        }

        when (spec.payloadKind) {
            ShortXHookPayloadKind.PROCESS -> {
                val target = args.firstOrNull { it?.javaClass?.name?.contains("ProcessRecord") == true } ?: thisObject
                val snapshot = processSnapshot(target)
                extras["package"] = snapshot.packageName
                extras["processName"] = snapshot.processName
                extras["uid"] = snapshot.uid
                extras["pid"] = snapshot.pid
            }
            ShortXHookPayloadKind.ACTIVITY -> {
                val target = args.firstOrNull { it?.javaClass?.name?.contains("ActivityRecord") == true } ?: thisObject
                val snapshot = activitySnapshot(target)
                extras["package"] = snapshot.packageName
                extras["activity"] = snapshot.activityName
                extras["taskId"] = snapshot.taskId
            }
            ShortXHookPayloadKind.NOTIFICATION -> {
                val target = args.firstOrNull { it?.javaClass?.name?.contains("NotificationRecord") == true } ?: thisObject
                val snapshot = notificationSnapshot(target)
                extras["package"] = snapshot.packageName
                extras["notificationKey"] = snapshot.key
                extras["notificationId"] = snapshot.id
                extras["channel"] = snapshot.channelId
            }
            ShortXHookPayloadKind.VPN -> {
                extras["state"] = args.firstOrNull { it is Enum<*> || it is String }?.toString().orEmpty()
                extras["reason"] = args.drop(1).firstOrNull { it is String }?.toString().orEmpty()
            }
            ShortXHookPayloadKind.IME -> {
                val editor = args.firstOrNull { it?.javaClass?.name == "android.view.inputmethod.EditorInfo" }
                extras["package"] = reflectedString(editor, "packageName")
                extras["fieldId"] = reflectedInt(editor, "fieldId")
            }
            ShortXHookPayloadKind.WINDOW -> {
                val target = args.firstOrNull { it?.javaClass?.name?.contains("WindowState") == true } ?: thisObject
                extras["package"] = reflectedString(target, "mOwningPackage", "owningPackage")
                val attrs = reflectedValue(target, "mAttrs", "attrs")
                extras["title"] = reflectedValue(attrs, "title")?.toString().orEmpty()
            }
            ShortXHookPayloadKind.ROTATION -> {
                extras["rotation"] = args.firstOrNull { it is Int } as? Int ?: -1
            }
            ShortXHookPayloadKind.WIDGET -> {
                extras["package"] = args.firstOrNull { it is String }?.toString().orEmpty()
                extras["hostId"] = args.firstOrNull { it is Int } as? Int ?: -1
            }
            ShortXHookPayloadKind.SHORTCUT -> {
                val strings = args.filterIsInstance<String>()
                extras["package"] = strings.firstOrNull().orEmpty()
                extras["shortcutId"] = strings.getOrNull(1).orEmpty()
            }
            ShortXHookPayloadKind.STATUS_BAR_ICON -> {
                extras["slot"] = args.firstOrNull { it is String }?.toString().orEmpty()
            }
            ShortXHookPayloadKind.INTENT_START -> {
                val intent = args.firstOrNull { it is Intent } as? Intent
                    ?: args.asSequence().mapNotNull { reflectedValue(it, "intent", "mIntent") as? Intent }.firstOrNull()
                extras["package"] = intent?.component?.packageName.orEmpty()
                extras["activity"] = intent?.component?.className.orEmpty()
                extras["action"] = intent?.action.orEmpty()
            }
            ShortXHookPayloadKind.SCREEN_STATE -> {
                extras["screenOn"] = args.firstOrNull { it is Boolean } as? Boolean ?: false
            }
            ShortXHookPayloadKind.BACK_PRESS,
            ShortXHookPayloadKind.NONE -> Unit
        }

        val identity = buildString {
            append(spec.id)
            append(':')
            append(extras["package"]?.toString().orEmpty())
            append(':')
            append(extras["activity"]?.toString().orEmpty())
            append(':')
            append(extras["processName"]?.toString().orEmpty())
            append(':')
            append(extras["state"]?.toString().orEmpty())
        }
        emitSystemRuntimeEvent(
            context = context,
            type = spec.eventType,
            dedupKey = identity,
            extras = extras,
            dedupWindowMs = 150L,
        )
    }

    /**
     * Clean-room equivalent of ShortX's InputManagerHook.
     *
     * Observe the input chain at several Android framework layers because OEM ROMs move
     * interceptKeyBeforeQueueing/interceptKeyBeforeDispatching between policy callback classes.
     * We never change the return value here; YAuto only observes and forwards KeyEvent metadata.
     */
    private fun installShortXInputHooks(context: Context, classLoader: ClassLoader) {
        val targets = listOf(
            InputHookTarget(
                "com.android.server.input.InputManagerService",
                setOf("filterInputEvent"),
            ),
            InputHookTarget(
                "com.android.server.wm.InputManagerCallback",
                setOf("interceptKeyBeforeQueueing", "interceptKeyBeforeDispatching"),
            ),
            InputHookTarget(
                "com.android.server.policy.PhoneWindowManager",
                setOf("interceptKeyBeforeQueueing", "interceptKeyBeforeDispatching"),
            ),
        )

        targets.forEach { target ->
            val clazz = runCatching { classLoader.loadClass(target.className) }.getOrNull() ?: return@forEach
            clazz.declaredMethods
                .filter { method ->
                    method.name in target.methodNames &&
                        method.parameterTypes.any {
                            KeyEvent::class.java.isAssignableFrom(it) ||
                                InputEvent::class.java.isAssignableFrom(it)
                        }
                }
                .forEach { method ->
                    val key = "shortx-input|" + method.toGenericString()
                    if (!installedHooks.add(key)) return@forEach
                    method.isAccessible = true
                    hook(method).intercept { chain ->
                        val event = chain.args.firstOrNull { it is KeyEvent } as? KeyEvent
                        if (event != null) {
                            handleSystemKeyEvent(context, event, target.className, method.name)
                        }
                        chain.proceed()
                    }
                }
        }

        installInputFilterStateHook(context, classLoader)
        log(Log.INFO, "YAuto", "ShortX-compatible input hooks installed")
    }

    private fun installInputFilterStateHook(context: Context, classLoader: ClassLoader) {
        val clazz = runCatching {
            classLoader.loadClass("com.android.server.input.NativeInputManagerService\$NativeImpl")
        }.getOrNull() ?: return
        clazz.declaredMethods
            .filter { method ->
                method.name == "setInputFilterEnabled" &&
                    method.parameterTypes.any {
                        it == Boolean::class.javaPrimitiveType || it == java.lang.Boolean::class.java
                    }
            }
            .forEach { method ->
                val key = "shortx-input-filter-state|" + method.toGenericString()
                if (!installedHooks.add(key)) return@forEach
                method.isAccessible = true
                hook(method).intercept { chain ->
                    val enabled = chain.args.firstOrNull { it is Boolean } as? Boolean
                    emitSystemRuntimeEvent(
                        context = context,
                        type = "android.event.input_filter_state_changed",
                        dedupKey = "input-filter:" + enabled,
                        extras = mapOf(
                            "enabled" to (enabled ?: false),
                            "method" to method.name,
                        ),
                        dedupWindowMs = 100L,
                    )
                    chain.proceed()
                }
            }
    }

    private fun handleSystemKeyEvent(
        context: Context,
        event: KeyEvent,
        className: String,
        methodName: String,
    ) {
        emitHardwareKeyCapture(context, event, className + "#" + methodName)

        val identity = buildString {
            append(event.deviceId)
            append(':')
            append(event.keyCode)
            append(':')
            append(event.scanCode)
            append(':')
            append(event.action)
            append(':')
            append(event.eventTime)
        }
        val now = SystemClock.elapsedRealtime()
        val previous = hardwareKeyEventDedup.put(identity, now)
        if (previous != null && now - previous < 250L) return
        if (hardwareKeyEventDedup.size > 128) {
            hardwareKeyEventDedup.entries.removeIf { now - it.value > 5_000L }
        }

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
        val until = hardwareKeyCaptureUntilElapsed.get()
        val nowElapsed = SystemClock.elapsedRealtime()
        if (until <= 0L || nowElapsed > until) {
            hardwareKeyCaptureUntilElapsed.compareAndSet(until, 0L)
            return
        }
        if (!hardwareKeyCaptureUntilElapsed.compareAndSet(until, 0L)) return

        runCatching {
            context.sendBroadcast(
                Intent(SystemBridgeProtocol.SYSTEM_EVENT_ACTION)
                    .setPackage(YAUTO_PACKAGE)
                    .putExtra("type", SystemBridgeProtocol.HARDWARE_KEY_CAPTURE_TYPE)
                    .putExtra("keyCode", event.keyCode)
                    .putExtra("scanCode", event.scanCode)
                    .putExtra("deviceId", event.deviceId)
                    .putExtra("action", event.action)
                    .putExtra("method", methodName)
                    .putExtra("timestampEpochMs", System.currentTimeMillis())
            )
        }
    }

    private data class InputHookTarget(
        val className: String,
        val methodNames: Set<String>,
    )

    private fun installAssistantHooks(context: Context, classLoader: ClassLoader) {
        val classNames = listOf(
            "com.android.server.voiceinteraction.VoiceInteractionManagerServiceImpl",
            "com.android.server.voiceinteraction.VoiceInteractionManagerService\$VoiceInteractionManagerServiceStub",
        )
        classNames.forEach { className ->
            val clazz = runCatching { classLoader.loadClass(className) }.getOrNull() ?: return@forEach
            clazz.declaredMethods
                .filter { method ->
                    method.name in setOf(
                        "showSessionLocked",
                        "showSession",
                        "showSessionForActiveService",
                        "showSessionFromSession",
                    )
                }
                .forEach { method ->
                    val key = "system-assistant|" + method.toGenericString()
                    if (!installedHooks.add(key)) return@forEach
                    method.isAccessible = true
                    hook(method).intercept { chain ->
                        val component = chain.args.filterIsInstance<android.content.ComponentName>().firstOrNull()
                        val info = reflectedValue(
                            chain.thisObject,
                            "mInfo",
                            "mVoiceInteractionServiceInfo",
                            "mServiceInfo",
                        )
                        val serviceInfo = reflectedValue(info, "mServiceInfo", "serviceInfo") ?: info
                        val packageName = component?.packageName
                            ?: reflectedString(serviceInfo, "packageName", "mPackageName")
                        emitSystemRuntimeEvent(
                            context = context,
                            type = "android.event.assistant_activated",
                            dedupKey = "assistant:" + packageName,
                            extras = mapOf(
                                "package" to packageName,
                                "component" to component?.flattenToString().orEmpty(),
                                "method" to method.name,
                            ),
                            dedupWindowMs = 350L,
                        )
                        chain.proceed()
                    }
                }
        }
    }

    private fun emitSystemRuntimeEvent(
        context: Context,
        type: String,
        dedupKey: String,
        extras: Map<String, Any?>,
        dedupWindowMs: Long = 1_000L,
    ) {
        if (type !in subscribedSystemEvents.get()) return
        val now = System.currentTimeMillis()
        val previous = systemEventDedup.put(dedupKey, now)
        if (previous != null && now - previous < dedupWindowMs) return
        if (systemEventDedup.size > 256) {
            systemEventDedup.entries.removeIf { now - it.value > 60_000L }
        }
        val intent = Intent(SystemBridgeProtocol.SYSTEM_EVENT_ACTION)
            .setPackage(YAUTO_PACKAGE)
            .putExtra("type", type)
            .putExtra("timestampEpochMs", now)
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

    private fun processSnapshot(target: Any?): ProcessSnapshot {
        if (target == null) return ProcessSnapshot()
        val info = reflectedValue(target, "info", "mInfo")
        val packageName = reflectedString(info, "packageName")
            .ifBlank { reflectedString(target, "packageName", "mPackageName") }
        return ProcessSnapshot(
            packageName = packageName,
            processName = reflectedString(target, "processName", "mProcessName"),
            uid = reflectedInt(target, "uid", "mUid"),
            pid = reflectedInt(target, "pid", "mPid"),
        )
    }

    private fun taskSnapshot(target: Any?): TaskSnapshot {
        if (target == null) return TaskSnapshot()
        val taskId = reflectedInt(target, "mTaskId", "taskId")
        val component = reflectedValue(target, "realActivity", "origActivity", "mRealActivity") as? android.content.ComponentName
        val intent = reflectedValue(target, "intent", "mIntent") as? Intent
        val resolved = component ?: intent?.component
        return TaskSnapshot(
            taskId = taskId,
            packageName = resolved?.packageName.orEmpty(),
            activityName = resolved?.className.orEmpty(),
        )
    }

    private fun reflectedString(target: Any?, vararg names: String): String =
        reflectedValue(target, *names)?.toString().orEmpty()

    private fun reflectedInt(target: Any?, vararg names: String): Int =
        when (val value = reflectedValue(target, *names)) {
            is Int -> value
            is Number -> value.toInt()
            else -> -1
        }

    private fun reflectedValue(target: Any?, vararg names: String): Any? {
        if (target == null) return null
        var type: Class<*>? = target.javaClass
        while (type != null) {
            for (name in names) {
                val field = runCatching { type.getDeclaredField(name) }.getOrNull() ?: continue
                field.isAccessible = true
                return runCatching { field.get(target) }.getOrNull()
            }
            type = type.superclass
        }
        for (name in names) {
            val getter = target.javaClass.methods.firstOrNull {
                it.parameterCount == 0 &&
                    (it.name.equals(name, true) || it.name.equals("get" + name.replaceFirstChar(Char::uppercase), true))
            } ?: continue
            return runCatching { getter.invoke(target) }.getOrNull()
        }
        return null
    }

    private fun activitySnapshot(target: Any?): ActivitySnapshot {
        if (target == null) return ActivitySnapshot()
        val component = reflectedValue(target, "mActivityComponent", "realActivity") as? android.content.ComponentName
            ?: (reflectedValue(target, "intent", "mIntent") as? Intent)?.component
        val info = reflectedValue(target, "info", "mActivityInfo")
        return ActivitySnapshot(
            packageName = component?.packageName
                ?: reflectedString(target, "packageName", "mPackageName")
                    .ifBlank { reflectedString(info, "packageName") },
            activityName = component?.className
                ?: reflectedString(info, "name"),
            taskId = reflectedInt(reflectedValue(target, "task", "mTask"), "mTaskId", "taskId"),
        )
    }

    private fun notificationSnapshot(target: Any?): NotificationSnapshot {
        if (target == null) return NotificationSnapshot()
        val sbn = reflectedValue(target, "sbn", "mSbn") ?: runCatching {
            target.javaClass.methods.firstOrNull { it.name == "getSbn" && it.parameterCount == 0 }?.invoke(target)
        }.getOrNull()
        val notification = reflectedValue(sbn, "notification", "mNotification")
        return NotificationSnapshot(
            packageName = reflectedString(sbn, "pkg", "packageName", "mPackageName"),
            key = reflectedString(sbn, "key", "mKey"),
            id = reflectedInt(sbn, "id", "mId"),
            channelId = reflectedString(notification, "mChannelId", "channelId"),
        )
    }

    private data class ActivitySnapshot(
        val packageName: String = "",
        val activityName: String = "",
        val taskId: Int = -1,
    )

    private data class NotificationSnapshot(
        val packageName: String = "",
        val key: String = "",
        val id: Int = -1,
        val channelId: String = "",
    )

    private data class ProcessSnapshot(
        val packageName: String = "",
        val processName: String = "",
        val uid: Int = -1,
        val pid: Int = -1,
    )

    private data class TaskSnapshot(
        val taskId: Int = -1,
        val packageName: String = "",
        val activityName: String = "",
    )

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
