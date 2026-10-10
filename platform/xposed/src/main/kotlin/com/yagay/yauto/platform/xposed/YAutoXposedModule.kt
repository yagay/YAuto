package com.yagay.yauto.platform.xposed

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.ComponentName
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
    private val enabledShortXBehaviors = AtomicReference<Set<String>>(emptySet())
    private val enabledPackageBehaviors = AtomicReference<Set<String>>(emptySet())
    private val methodSessions = MethodHookSessionRegistry()
    private val crashGuards = ConcurrentHashMap<String, HookCrashGuard>()
    private val systemUiTileLabels = ConcurrentHashMap<String, String>()
    private val systemUiTileReceiverRegistered = AtomicBoolean(false)
    private val yAutoUid = AtomicLong(-1L)
    private val systemUiChipRegistered = AtomicBoolean(false)
    @Volatile private var systemUiChipController: ShortXStatusChipController? = null

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
        if (!XposedPackageInitPolicy.shouldInitialize(param.packageName, param.isFirstPackage, YAUTO_PACKAGE)) return
        runCatching {
            val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
            hook(attach).intercept { chain ->
                val result = chain.proceed()
                val application = chain.thisObject as? Application
                if (application != null) {
                    val guard = runCatching { HookCrashGuard(application) }.getOrNull()
                    if (guard != null) crashGuards[param.packageName] = guard
                    if (guard?.isQuarantined() != true) {
                        runCatching {
                            installShortXRuntimeInitHook(application, param.packageName, param.classLoader)
                            guard?.arm("package-hooks")
                            installShortXPackageHooks(application, param.packageName, param.classLoader)
                        }.onFailure {
                            log(Log.ERROR, "YAuto", "ShortX package hooks failed for ${param.packageName}", it)
                        }
                    } else {
                        log(Log.WARN, "YAuto", "Hook crash-loop safe mode: ${param.packageName}")
                    }
                    // Keep the signature-protected management receiver available.
                    registerAppHookBridge(application, param.packageName, param.classLoader)
                }
                result
            }
        }.onFailure {
            log(Log.ERROR, "YAuto", "Unable to initialize app hook bridge for ${param.packageName}", it)
        }
    }

    private fun registerSystemBridge(context: Context, classLoader: ClassLoader) {
        registerXposedSystemBridge(
            context = context,
            classLoader = classLoader,
            systemRegistered = systemRegistered,
            enabledShortXBehaviors = enabledShortXBehaviors,
            subscribedSystemEvents = subscribedSystemEvents,
            hardwareKeyCaptureUntilElapsed = hardwareKeyCaptureUntilElapsed,
            yAutoUid = yAutoUid,
            installBehaviorHooks = { installShortXBehaviorHooks(context, classLoader) },
            installSubscriptions = { events -> installShortXHooksForSubscriptions(context, classLoader, events) },
            installInputHooks = { installShortXInputHooks(context, classLoader) },
            emitLog = { level, message, error ->
                if (error != null) log(level, "YAuto", message, error)
                else log(level, "YAuto", message)
            },
            ownPackage = YAUTO_PACKAGE,
        )
    }

    private fun installSystemRuntimeHooks(context: Context, classLoader: ClassLoader, eventTypes: Set<String>) {
        ShortXCompatHookCatalog.subscribedCoreRuntimeHooks(eventTypes).forEach { family ->
            when (family) {
                ShortXCoreRuntimeHook.PROCESS_DEATH -> installProcessDeathHooks(context, classLoader)
                ShortXCoreRuntimeHook.TASK_REMOVAL -> installTaskRemovedHooks(context, classLoader)
                ShortXCoreRuntimeHook.BACK_NAVIGATION -> installBackNavigationHooks(context, classLoader)
                ShortXCoreRuntimeHook.ASSISTANT -> installAssistantHooks(context, classLoader)
            }
        }
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


    private fun installShortXHooksForSubscriptions(
        context: Context,
        classLoader: ClassLoader,
        eventTypes: Set<String>,
    ) {
        if (
            "android.event.hardware_key" in eventTypes ||
            "android.event.input_filter_state_changed" in eventTypes
        ) {
            installShortXInputHooks(context, classLoader)
        }
        installSystemRuntimeHooks(context, classLoader, eventTypes)
        val observers = ShortXCompatHookCatalog.subscribedObservers(eventTypes)
        if (observers.isNotEmpty()) {
            installShortXObserverHooks(context, classLoader, observers)
        }
        if ("android.event.accessibility_user_state_created" in eventTypes) {
            installShortXAccessibilityUserStateConstructorHook(context, classLoader)
        }
    }

    private fun installShortXAccessibilityUserStateConstructorHook(
        context: Context,
        classLoader: ClassLoader,
    ) {
        val clazz = runCatching {
            classLoader.loadClass("com.android.server.accessibility.AccessibilityUserState")
        }.getOrNull() ?: return
        clazz.declaredConstructors.forEach { constructor ->
            val key = "shortx-accessibility-user-state|" + constructor.toGenericString()
            if (!installedHooks.add(key)) return@forEach
            constructor.isAccessible = true
            hook(constructor).intercept { chain ->
                val result = chain.proceed()
                emitSystemRuntimeEvent(
                    context = context,
                    type = "android.event.accessibility_user_state_created",
                    dedupKey = "accessibility-user-state:" + System.identityHashCode(chain.thisObject),
                    extras = mapOf("className" to clazz.name),
                    dedupWindowMs = 100L,
                )
                result
            }
        }
    }

    private fun installShortXBehaviorHooks(context: Context, classLoader: ClassLoader) {
        installShortXAccessibilityBehaviorHooks(context, classLoader)
        installShortXClipboardBehaviorHooks(context, classLoader)
        installShortXPermissionBehaviorHook(context, classLoader)
    }

    private fun installShortXAccessibilityBehaviorHooks(context: Context, classLoader: ClassLoader) {
        val targets = listOf(
            Triple(
                "com.android.server.accessibility.AccessibilitySecurityPolicy",
                setOf("checkAccessibilityAccess", "canRetrieveWindowsLocked"),
                true,
            ),
            Triple(
                "com.android.server.accessibility.UiAutomationManager",
                setOf("canRetrieveInteractiveWindowsLocked"),
                true,
            ),
            Triple(
                "com.android.server.accessibility.AccessibilityUserState",
                setOf("suppressingAccessibilityServicesLocked"),
                false,
            ),
        )
        targets.forEach { (className, methodNames, replacement) ->
            val clazz = runCatching { classLoader.loadClass(className) }.getOrNull() ?: return@forEach
            clazz.declaredMethods
                .filter { method ->
                    method.name in methodNames &&
                        (method.returnType == Boolean::class.javaPrimitiveType ||
                            method.returnType == java.lang.Boolean::class.java)
                }
                .forEach { method ->
                    val key = "shortx-behavior-accessibility|" + method.toGenericString()
                    if (!installedHooks.add(key)) return@forEach
                    method.isAccessible = true
                    hook(method).intercept { chain ->
                        if (
                            SystemBridgeProtocol.SHORTX_BEHAVIOR_ACCESSIBILITY in enabledShortXBehaviors.get() &&
                            isYAutoCaller(context)
                        ) {
                            replacement
                        } else {
                            chain.proceed()
                        }
                    }
                }
        }
    }

    private fun installShortXClipboardBehaviorHooks(context: Context, classLoader: ClassLoader) {
        val clazz = runCatching {
            classLoader.loadClass("com.android.server.clipboard.ClipboardService")
        }.getOrNull() ?: return
        clazz.declaredMethods
            .filter { method ->
                method.name == "clipboardAccessAllowed" &&
                    (method.returnType == Boolean::class.javaPrimitiveType ||
                        method.returnType == java.lang.Boolean::class.java)
            }
            .forEach { method ->
                val key = "shortx-behavior-clipboard|" + method.toGenericString()
                if (!installedHooks.add(key)) return@forEach
                method.isAccessible = true
                hook(method).intercept { chain ->
                    if (
                        SystemBridgeProtocol.SHORTX_BEHAVIOR_CLIPBOARD in enabledShortXBehaviors.get() &&
                        isYAutoCaller(context)
                    ) {
                        true
                    } else {
                        chain.proceed()
                    }
                }
            }
    }

    private fun installShortXPermissionBehaviorHook(context: Context, classLoader: ClassLoader) {
        val clazz = runCatching { classLoader.loadClass("android.app.ContextImpl") }.getOrNull() ?: return
        clazz.declaredMethods
            .filter { method ->
                method.name == "checkCallingPermission" &&
                    method.returnType == Int::class.javaPrimitiveType &&
                    method.parameterTypes.firstOrNull() == String::class.java
            }
            .forEach { method ->
                val key = "shortx-behavior-permission|" + method.toGenericString()
                if (!installedHooks.add(key)) return@forEach
                method.isAccessible = true
                hook(method).intercept { chain ->
                    val permission = chain.args.firstOrNull() as? String
                    if (
                        SystemBridgeProtocol.SHORTX_BEHAVIOR_PERMISSION in enabledShortXBehaviors.get() &&
                        isYAutoCaller(context) &&
                        permission in SHORTX_PERMISSION_ALLOWLIST
                    ) {
                        PackageManager.PERMISSION_GRANTED
                    } else {
                        chain.proceed()
                    }
                }
            }
    }

    private fun isYAutoCaller(context: Context): Boolean {
        val uid = yAutoUid.get().toInt()
        return uid >= 0 && Binder.getCallingUid() == uid
    }

    private fun installShortXObserverHooks(
        context: Context,
        classLoader: ClassLoader,
        requested: List<ShortXObserverHookSpec>,
    ) {
        requested.forEach { spec ->
            spec.classNames.forEach { className ->
                val clazz = runCatching { classLoader.loadClass(className) }.getOrNull() ?: return@forEach
                clazz.declaredMethods
                    .filter { it.name in spec.methodNames }
                    .forEach { method ->
                        val key = "shortx-observer|" + spec.id + "|" + method.toGenericString()
                        if (!installedHooks.add(key)) return@forEach
                        try {
                            method.isAccessible = true
                            hook(method).intercept { chain ->
                                if (spec.after) {
                                    val result = chain.proceed()
                                    runCatching {
                                        emitShortXObserverEvent(context, spec, className, method.name, chain.thisObject, chain.args)
                                    }
                                    result
                                } else {
                                    runCatching {
                                        emitShortXObserverEvent(context, spec, className, method.name, chain.thisObject, chain.args)
                                    }
                                    chain.proceed()
                                }
                            }
                        } catch (error: Exception) {
                            installedHooks.remove(key)
                            log(Log.WARN, "YAuto", "ShortX observer unavailable: " + key, error)
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
        args: List<Any?>,
    ) {
        if (spec.eventType !in subscribedSystemEvents.get()) return
        val extras = linkedMapOf<String, Any?>(
            "hookId" to spec.id,
            "className" to className,
            "method" to methodName,
        )
        if (spec.id == "clipboard-read") {
            extras["callingUid"] = Binder.getCallingUid()
        }
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
            ShortXHookPayloadKind.TASK -> {
                val target = args.firstOrNull { it?.javaClass?.name == "com.android.server.wm.Task" }
                    ?: args.firstOrNull { it?.javaClass?.name?.contains("Task") == true }
                val snapshot = taskSnapshot(target)
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
            InputHookTarget(
                "com.android.server.policy.SingleKeyGestureDetector",
                emptySet(),
                matchAnyKeyEventMethod = true,
            ),
            InputHookTarget(
                "com.android.server.policy.KeyCombinationManager",
                emptySet(),
                matchAnyKeyEventMethod = true,
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
                            runCatching {
                                handleSystemKeyEvent(context, event, target.className, method.name)
                            }
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
        val until = hardwareKeyCaptureUntilElapsed.get()
        val nowElapsed = SystemClock.elapsedRealtime()
        if (until <= 0L || nowElapsed > until) {
            hardwareKeyCaptureUntilElapsed.compareAndSet(until, 0L)
            return
        }
        if (!hardwareKeyCaptureUntilElapsed.compareAndSet(until, 0L)) return

        val inputDevice = event.device
        runCatching {
            context.sendBroadcast(
                Intent(SystemBridgeProtocol.SYSTEM_EVENT_ACTION)
                    .setPackage(YAUTO_PACKAGE)
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

    private data class InputHookTarget(
        val className: String,
        val methodNames: Set<String>,
        val matchAnyKeyEventMethod: Boolean = false,
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

    private fun installShortXPackageHooks(
        context: Context,
        packageName: String,
        classLoader: ClassLoader,
    ) {
        routeXposedPackageHooks(
            packageName = packageName,
            installSystemUi = { installShortXSystemUiHooks(context, classLoader) },
            installStatusChip = { installShortXStatusChipHooks(context, classLoader) },
            installTileLabel = { installShortXTileLabelHooks(context, classLoader) },
            installNfc = { installShortXNfcHooks(context, classLoader) },
            installMediaProvider = { installShortXMediaProviderHooks(context, classLoader) },
            installTelephonyProvider = { installShortXTelephonyProviderHooks(context, classLoader) },
            installInputConnection = { installShortXInputConnectionHook(context, packageName, classLoader) },
        )
    }

    private fun installShortXRuntimeInitHook(
        context: Context,
        packageName: String,
        classLoader: ClassLoader,
    ) {
        val clazz = runCatching {
            classLoader.loadClass("com.android.internal.os.RuntimeInit\$LoggingHandler")
        }.getOrNull() ?: return
        clazz.declaredMethods
            .filter { method ->
                method.name == "uncaughtException" &&
                    method.parameterTypes.any { Throwable::class.java.isAssignableFrom(it) }
            }
            .forEach { method ->
                val key = "shortx-runtime-init|" + packageName + "|" + method.toGenericString()
                if (!installedHooks.add(key)) return@forEach
                method.isAccessible = true
                hook(method).intercept { chain ->
                    val thread = chain.args.firstOrNull { it is Thread } as? Thread
                    val error = chain.args.firstOrNull { it is Throwable } as? Throwable
                    if (error != null) runCatching { crashGuards[packageName]?.recordFatal(error) }
                    emitPackageRuntimeEvent(
                        context,
                        "android.event.process_uncaught_exception",
                        mapOf(
                            "package" to packageName,
                            "thread" to thread?.name.orEmpty(),
                            "exceptionClass" to error?.javaClass?.name.orEmpty(),
                            "message" to error?.message.orEmpty().take(1024),
                            "method" to method.name,
                        ),
                    )
                    chain.proceed()
                }
            }
    }

    /**
     * Real status-bar chip inside SystemUI, activated only when SystemUI is an LSPosed
     * target. The ordered receiver requires the YAuto signature permission.
     */
    private fun installShortXStatusChipHooks(context: Context, classLoader: ClassLoader) {
        val controller = ShortXStatusChipController(context) { chipId, gesture ->
            emitPackageRuntimeEvent(context, "android.event.status_chip_interaction",
                mapOf("chipId" to chipId, "gesture" to gesture))
        }
        systemUiChipController = controller
        var hookedCount = 0
        listOf(
            "com.android.systemui.statusbar.phone.PhoneStatusBarView",
            "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView",
        ).forEach { name ->
            val clazz = runCatching { classLoader.loadClass(name) }.getOrNull() ?: return@forEach
            clazz.declaredMethods.filter { it.name in setOf("onFinishInflate", "onAttachedToWindow") }.forEach { method ->
                val key = "yauto-chip|" + method.toGenericString()
                if (!installedHooks.add(key)) return@forEach
                method.isAccessible = true
                hook(method).intercept { chain ->
                    val result = chain.proceed()
                    runCatching { controller.attach(chain.thisObject) }
                    result
                }
                hookedCount++
            }
        }
        if (systemUiChipRegistered.getAndSet(true)) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(received: Context?, intent: Intent?) {
                if (intent?.action != SystemBridgeProtocol.CHIP_ACTION || !isOrderedBroadcast) return
                val response = Bundle().apply { putInt("version", SystemBridgeProtocol.VERSION) }
                try {
                    require(intent.getIntExtra("version", -1) == SystemBridgeProtocol.VERSION) { "Protocol mismatch" }
                    require(hookedCount > 0) { "SystemUI status-bar ViewGroup not available on this ROM" }
                    controller.update(
                        intent.getStringExtra("operation").orEmpty(),
                        intent.getStringExtra("chipId").orEmpty(),
                        intent.getStringExtra("text").orEmpty(),
                        intent.getStringExtra("iconMode").orEmpty().ifBlank { "none" },
                        intent.getStringExtra("icon").orEmpty(),
                        intent.getStringExtra("imageBase64").orEmpty(),
                    )
                    response.putBoolean("success", true)
                    response.putBoolean("accepted", true)
                } catch (error: Exception) {
                    response.putBoolean("success", false)
                    response.putString("error", error.cause?.message ?: error.message)
                    log(Log.ERROR, "YAuto", "Status chip request failed", error)
                }
                setResultExtras(response)
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, IntentFilter(SystemBridgeProtocol.CHIP_ACTION),
                    SystemBridgeProtocol.PERMISSION, null, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, IntentFilter(SystemBridgeProtocol.CHIP_ACTION),
                    SystemBridgeProtocol.PERMISSION, null)
            }
        } catch (error: Exception) {
            systemUiChipRegistered.set(false)
            log(Log.ERROR, "YAuto", "Unable to register SystemUI chip receiver", error)
        }
    }


    /**
     * Opt-in replacement of a single external QS tile label. The real CustomTile
     * still runs and retains its original behavior when no matching override exists.
     * No system/stock tile labels or icons are changed.
     */
    private fun installShortXTileLabelHooks(context: Context, classLoader: ClassLoader) {
        val clazz = runCatching {
            classLoader.loadClass("com.android.systemui.qs.external.CustomTile")
        }.getOrNull() ?: return
        var hookCount = 0
        clazz.declaredMethods.filter { it.name == "getTileLabel" &&
            it.parameterCount == 0 && CharSequence::class.java.isAssignableFrom(it.returnType)
        }.forEach { method ->
            val key = "yauto-qs-label|" + method.toGenericString()
            if (!installedHooks.add(key)) return@forEach
            try {
                method.isAccessible = true
                hook(method).intercept { chain ->
                    val original = chain.proceed()
                    val tile = chain.thisObject
                    val component = runCatching {
                        generateSequence(tile?.javaClass) { it.superclass }
                            .take(5).flatMap { it.declaredFields.asSequence() }
                            .firstOrNull { it.name in setOf("mComponent", "mTileComponent", "component") }
                            ?.also { it.isAccessible = true }
                            ?.get(tile) as? ComponentName
                    }.getOrNull()
                    val label = component?.flattenToString()?.let(systemUiTileLabels::get)
                    label ?: original
                }
                hookCount++
            } catch (error: Exception) {
                installedHooks.remove(key)
                log(Log.WARN, "YAuto", "External QS tile label hook unavailable", error)
            }
        }
        if (hookCount == 0 || !systemUiTileReceiverRegistered.compareAndSet(false, true)) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(received: Context?, intent: Intent?) {
                if (intent?.action != SystemBridgeProtocol.TILE_LABEL_ACTION || !isOrderedBroadcast) return
                val reply = Bundle().apply { putInt("version", SystemBridgeProtocol.VERSION) }
                try {
                    require(intent.getIntExtra("version", -1) == SystemBridgeProtocol.VERSION) {
                        "Protocol mismatch"
                    }
                    val operation = intent.getStringExtra("operation").orEmpty()
                    require(operation in setOf(
                        SystemBridgeProtocol.TILE_LABEL_SET, SystemBridgeProtocol.TILE_LABEL_CLEAR
                    )) { "Unsupported tile label operation" }
                    val requested = intent.getStringExtra("component").orEmpty().trim()
                    val component = ComponentName.unflattenFromString(requested)
                        ?: error("Invalid component name")
                    val key = component.flattenToString()
                    if (operation == SystemBridgeProtocol.TILE_LABEL_CLEAR) {
                        systemUiTileLabels.remove(key)
                    } else {
                        val label = intent.getStringExtra("label").orEmpty()
                        require(label.isNotBlank() && label.length <= 64 && !label.contains('\n')) {
                            "Tile label must be 1-64 characters"
                        }
                        systemUiTileLabels[key] = label
                    }
                    reply.putString("component", key)
                    reply.putInt("activeOverrides", systemUiTileLabels.size)
                    reply.putBoolean("success", true)
                } catch (error: Exception) {
                    reply.putBoolean("success", false)
                    reply.putString("error", error.cause?.message ?: error.message)
                    log(Log.WARN, "YAuto", "Custom tile label operation failed", error)
                }
                setResultExtras(reply)
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, IntentFilter(SystemBridgeProtocol.TILE_LABEL_ACTION),
                    SystemBridgeProtocol.PERMISSION, null, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, IntentFilter(SystemBridgeProtocol.TILE_LABEL_ACTION),
                    SystemBridgeProtocol.PERMISSION, null)
            }
        } catch (error: Exception) {
            systemUiTileReceiverRegistered.set(false)
            log(Log.ERROR, "YAuto", "Custom tile label receiver unavailable", error)
        }
    }

    private fun installShortXSystemUiHooks(context: Context, classLoader: ClassLoader) {
        fun observeConstructors(
            classNames: List<String>,
            eventType: String,
        ) {
            classNames.forEach { className ->
                val clazz = runCatching { classLoader.loadClass(className) }.getOrNull() ?: return@forEach
                clazz.declaredConstructors.forEach { constructor ->
                    val key = "shortx-systemui-constructor|" + eventType + "|" + constructor.toGenericString()
                    if (!installedHooks.add(key)) return@forEach
                    constructor.isAccessible = true
                    hook(constructor).intercept { chain ->
                        val result = chain.proceed()
                        emitPackageRuntimeEvent(
                            context,
                            eventType,
                            mapOf(
                                "className" to className,
                                "method" to "<init>",
                            ),
                        )
                        result
                    }
                }
            }
        }

        fun observe(
            classNames: List<String>,
            methodNames: Set<String>,
            eventType: String,
            after: Boolean = false,
            extras: (Any?, List<Any?>, String) -> Map<String, Any?> = { _, _, _ -> emptyMap() },
        ) {
            classNames.forEach { className ->
                val clazz = runCatching { classLoader.loadClass(className) }.getOrNull() ?: return@forEach
                clazz.declaredMethods.filter { it.name in methodNames }.forEach { method ->
                    val key = "shortx-systemui|" + eventType + "|" + method.toGenericString()
                    if (!installedHooks.add(key)) return@forEach
                    method.isAccessible = true
                    hook(method).intercept { chain ->
                        if (after) {
                            val result = chain.proceed()
                            runCatching {
                                emitPackageRuntimeEvent(context, eventType, extras(chain.thisObject, chain.args, method.name))
                            }
                            result
                        } else {
                            runCatching {
                                emitPackageRuntimeEvent(context, eventType, extras(chain.thisObject, chain.args, method.name))
                            }
                            chain.proceed()
                        }
                    }
                }
            }
        }

        observe(
            classNames = listOf(
                "com.android.systemui.SystemUIApplication",
                "com.android.systemui.application.impl.SystemUIApplicationImpl",
            ),
            methodNames = setOf("onCreate"),
            eventType = "android.event.systemui_app_ready",
            after = true,
            extras = { target, _, method ->
                mapOf(
                    "className" to target?.javaClass?.name.orEmpty(),
                    "method" to method,
                )
            },
        )
        observeConstructors(
            classNames = listOf(
                "com.android.systemui.qs.QSTileHost",
                "com.android.systemui.qs.QSHostAdapter",
                "com.android.systemui.qs.pipeline.domain.adapter.MiuiQSHostAdapter",
            ),
            eventType = "android.event.systemui_qs_host_ready",
        )
        observeConstructors(
            classNames = listOf("com.android.systemui.statusbar.phone.AutoHideControllerImpl"),
            eventType = "android.event.systemui_auto_hide_ready",
        )
        observe(
            classNames = listOf("com.android.systemui.qs.external.CustomTile"),
            methodNames = setOf("getTileLabel"),
            eventType = "android.event.systemui_tile_label_queried",
            extras = { target, _, method ->
                val component = reflectedValue(target, "mComponent", "component") as? android.content.ComponentName
                mapOf(
                    "package" to component?.packageName.orEmpty(),
                    "component" to component?.flattenToString().orEmpty(),
                    "method" to method,
                )
            },
        )
        observe(
            classNames = listOf("com.android.systemui.qs.external.CustomTile"),
            methodNames = setOf("handleClick"),
            eventType = "android.event.systemui_qs_tile_clicked",
            extras = { target, _, method ->
                val component = reflectedValue(target, "mComponent", "component") as? android.content.ComponentName
                mapOf(
                    "package" to component?.packageName.orEmpty(),
                    "component" to component?.flattenToString().orEmpty(),
                    "method" to method,
                )
            },
        )
        observe(
            classNames = listOf("com.android.systemui.statusbar.phone.LightBarTransitionsController"),
            methodNames = setOf("setIconsDark"),
            eventType = "android.event.systemui_icon_dark_changed",
            extras = { _, args, method ->
                mapOf(
                    "dark" to (args.firstOrNull { it is Boolean } as? Boolean ?: false),
                    "method" to method,
                )
            },
        )
        observe(
            classNames = listOf("com.android.systemui.qs.customize.TileQueryHelper"),
            methodNames = setOf("addTile"),
            eventType = "android.event.systemui_tile_discovered",
            after = true,
            extras = { _, args, method ->
                mapOf(
                    "detail" to args.firstOrNull()?.toString().orEmpty().take(512),
                    "method" to method,
                )
            },
        )
        observe(
            classNames = listOf(
                "com.android.systemui.statusbar.phone.PhoneStatusBarView",
                "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView",
            ),
            methodNames = setOf("onFinishInflate"),
            eventType = "android.event.systemui_status_bar_ready",
            after = true,
            extras = { target, _, method ->
                mapOf(
                    "className" to target?.javaClass?.name.orEmpty(),
                    "method" to method,
                )
            },
        )
    }

    private fun installShortXNfcHooks(context: Context, classLoader: ClassLoader) {
        val clazz = runCatching {
            classLoader.loadClass("com.android.nfc.NfcService\$NfcServiceHandler")
        }.getOrNull() ?: return
        clazz.declaredMethods.filter { it.name == "dispatchTagEndpoint" }.forEach { method ->
            val key = "shortx-nfc|" + method.toGenericString()
            if (!installedHooks.add(key)) return@forEach
            method.isAccessible = true
            hook(method).intercept { chain ->
                val endpoint = chain.args.firstOrNull {
                    it?.javaClass?.name?.contains("TagEndpoint") == true
                }
                val uid = reflectedValue(endpoint, "uid", "mUid") as? ByteArray
                    ?: runCatching {
                        endpoint?.javaClass?.methods?.firstOrNull {
                            it.name == "getUid" && it.parameterCount == 0
                        }?.invoke(endpoint) as? ByteArray
                    }.getOrNull()
                val payload = mapOf(
                    "kind" to "tag",
                    "uidHex" to (uid?.joinToString("") { byte -> "%02X".format(byte) } ?: ""),
                    "method" to method.name,
                )
                emitPackageRuntimeEvent(context, "android.event.nfc_tag_system", payload)
                emitPackageRuntimeEvent(context, "android.event.nfc_tag", payload)
                chain.proceed()
            }
        }
    }

    private fun installShortXMediaProviderHooks(context: Context, classLoader: ClassLoader) {
        runCatching { classLoader.loadClass("com.android.providers.media.MediaProvider") }
            .getOrNull()
            ?.declaredMethods
            ?.filter { it.name == "onCreate" }
            ?.forEach { method ->
                val key = "shortx-media-provider-ready|" + method.toGenericString()
                if (!installedHooks.add(key)) return@forEach
                method.isAccessible = true
                hook(method).intercept { chain ->
                    val result = chain.proceed()
                    emitPackageRuntimeEvent(
                        context,
                        "android.event.media_provider_ready",
                        mapOf("method" to method.name),
                    )
                    result
                }
            }

        val classNames = listOf(
            "com.android.providers.media.MediaProvider",
            "com.android.providers.media.MediaDocumentsProvider",
        )
        classNames.forEach { className ->
            val clazz = runCatching { classLoader.loadClass(className) }.getOrNull() ?: return@forEach
            clazz.declaredMethods
                .filter { it.name in setOf("insert", "delete", "update") }
                .forEach { method ->
                    val eventType = when (method.name) {
                        "insert" -> "android.event.media_store_inserted"
                        "delete" -> "android.event.media_store_deleted"
                        "update" -> "android.event.media_store_updated"
                        else -> "android.event.media_store_changed"
                    }
                    val key = "shortx-media-provider|" + method.toGenericString()
                    if (!installedHooks.add(key)) return@forEach
                    method.isAccessible = true
                    hook(method).intercept { chain ->
                        val uri = chain.args.firstOrNull { it is android.net.Uri } as? android.net.Uri
                        val result = chain.proceed()
                        emitPackageRuntimeEvent(
                            context,
                            eventType,
                            mapOf(
                                "uri" to uri?.toString().orEmpty(),
                                "collection" to mediaCollection(uri),
                                "method" to method.name,
                            ),
                        )
                        val genericPayload = mapOf(
                            "uri" to uri?.toString().orEmpty(),
                            "collection" to mediaCollection(uri),
                            "operation" to method.name,
                            "method" to method.name,
                        )
                        emitPackageRuntimeEvent(context, "android.event.media_store_changed", genericPayload)
                        emitPackageRuntimeEvent(context, "android.event.media_provider_changed", genericPayload)
                        result
                    }
                }
        }
    }

    private fun installShortXTelephonyProviderHooks(context: Context, classLoader: ClassLoader) {
        val clazz = runCatching { classLoader.loadClass("com.android.providers.telephony.SmsProvider") }.getOrNull()
            ?: return
        clazz.declaredMethods.filter { it.name == "onCreate" }.forEach { method ->
            val key = "shortx-sms-provider-ready|" + method.toGenericString()
            if (!installedHooks.add(key)) return@forEach
            method.isAccessible = true
            hook(method).intercept { chain ->
                val result = chain.proceed()
                emitPackageRuntimeEvent(
                    context,
                    "android.event.sms_provider_ready",
                    mapOf("method" to method.name),
                )
                result
            }
        }
        clazz.declaredMethods
            .filter { it.name in setOf("insert", "delete", "update") }
            .forEach { method ->
                val key = "shortx-sms-provider|" + method.toGenericString()
                if (!installedHooks.add(key)) return@forEach
                method.isAccessible = true
                hook(method).intercept { chain ->
                    val uri = chain.args.firstOrNull { it is android.net.Uri } as? android.net.Uri
                    val result = chain.proceed()
                    emitPackageRuntimeEvent(
                        context,
                        "android.event.sms_provider_changed",
                        mapOf(
                            "operation" to method.name,
                            "uri" to uri?.toString().orEmpty(),
                            "method" to method.name,
                        ),
                    )
                    result
                }
            }
    }

    private fun installShortXInputConnectionHook(context: Context, packageName: String, classLoader: ClassLoader) {
        val clazz = runCatching { classLoader.loadClass("android.view.inputmethod.RemoteInputConnectionImpl") }.getOrNull()
            ?: return
        clazz.declaredMethods.filter { it.name == "commitText" }.forEach { method ->
            val key = "shortx-input-connection|" + packageName + "|" + method.toGenericString()
            if (!installedHooks.add(key)) return@forEach
            method.isAccessible = true
            hook(method).intercept { chain ->
                val text = chain.args.firstOrNull { it is CharSequence }?.toString().orEmpty()
                emitPackageRuntimeEvent(
                    context,
                    "android.event.input_text_committed",
                    mapOf(
                        "package" to packageName,
                        "text" to text.take(2048),
                        "method" to method.name,
                    ),
                )
                chain.proceed()
            }
        }
    }

    private fun emitPackageRuntimeEvent(
        context: Context,
        type: String,
        extras: Map<String, Any?>,
    ) {
        val intent = Intent(SystemBridgeProtocol.SYSTEM_EVENT_ACTION)
            .setPackage(YAUTO_PACKAGE)
            .putExtra("type", type)
            .putExtra("bridgeSource", "lsposed.package")
            .putExtra("package", context.packageName)
            .putExtra("timestampEpochMs", System.currentTimeMillis())
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
                        if (!installedHooks.add(key)) {
                            if (alreadyActive) hookedCount++
                            else failures += "${member.name}: inactive hook cannot be reattached without process restart"
                            return@forEach
                        }
                        try {
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
                            hookedCount++
                            newlyHooked++
                        } catch (error: Exception) {
                            installedHooks.remove(key)
                            failures += "${member.name}: ${error.message.orEmpty().take(120)}"
                            log(Log.WARN, "YAuto", "Hook target unavailable: $key", error)
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
                if (!installedHooks.add(key)) return@forEach
                method.isAccessible = true
                hook(method).intercept { chain ->
                    if (
                        SystemBridgeProtocol.SHORTX_PACKAGE_BEHAVIOR_RENDERNODE_GUARD !in
                        enabledPackageBehaviors.get()
                    ) {
                        chain.proceed()
                    } else {
                        try {
                            chain.proceed()
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
        hook(constructor).intercept { chain ->
            if (!methodSessions.isActive(sessionId, eventToken)) {
                chain.proceed()
            } else if (lifecycle == "after") {
                val result = chain.proceed()
                emitMethodCalled(context, sessionId, eventToken, packageName, processName,
                    className, "<init>", "after",
                    if (captureValues) MethodHookValueSnapshot.capture(chain.args) else emptyMap())
                result
            } else {
                emitMethodCalled(context, sessionId, eventToken, packageName, processName,
                    className, "<init>", "before",
                    if (captureValues) MethodHookValueSnapshot.capture(chain.args) else emptyMap())
                chain.proceed()
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
        hook(method).intercept { chain ->
            if (!methodSessions.isActive(sessionId, eventToken)) {
                chain.proceed()
            } else if (mode == "replace") {
                val result = parseReplacement(method.returnType, replacementType, replacementValue)
                emitMethodCalled(context, sessionId, eventToken, packageName, processName,
                    chain.thisObject?.javaClass?.name ?: className, method.name, "before",
                    if (captureValues) MethodHookValueSnapshot.capture(chain.args, result, true) else emptyMap())
                result
            } else if (mode == "override_result") {
                // Unlike replace, run the original method first; only its returned
                // value is overridden. Side effects of the method are preserved.
                chain.proceed()
                val result = parseReplacement(method.returnType, replacementType, replacementValue)
                emitMethodCalled(context, sessionId, eventToken, packageName, processName,
                    chain.thisObject?.javaClass?.name ?: className, method.name, "after",
                    if (captureValues) MethodHookValueSnapshot.capture(chain.args, result, true) else emptyMap())
                result
            } else if (lifecycle == "after") {
                val result = chain.proceed()
                emitMethodCalled(context, sessionId, eventToken, packageName, processName,
                    chain.thisObject?.javaClass?.name ?: className, method.name, "after",
                    if (captureValues) MethodHookValueSnapshot.capture(chain.args, result, true) else emptyMap())
                result
            } else {
                emitMethodCalled(context, sessionId, eventToken, packageName, processName,
                    chain.thisObject?.javaClass?.name ?: className, method.name, "before",
                    if (captureValues) MethodHookValueSnapshot.capture(chain.args) else emptyMap())
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
        captured: Map<String, String> = emptyMap(),
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
                    .apply { captured.forEach { (key, value) -> putExtra(key, value) } }
            )
        }
    }

    private companion object {
        const val YAUTO_PACKAGE = "com.yagay.yauto"
        val CLASS_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+")
        val METHOD_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]{0,127}")
        val SHORTX_PERMISSION_ALLOWLIST = setOf(
            "android.permission.MANAGE_MEDIA_PROJECTION",
            "android.permission.CAPTURE_VOICE_COMMUNICATION_OUTPUT",
            "android.permission.WRITE_SECURE_SETTINGS",
            "android.permission.READ_CLIPBOARD_IN_BACKGROUND",
        )
    }
}
