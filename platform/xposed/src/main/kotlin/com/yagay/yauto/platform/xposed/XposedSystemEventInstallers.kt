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

/** Installs SystemServer event subscriptions, input filters and system behaviors. */
internal val SHORTX_PERMISSION_ALLOWLIST = setOf(
            "android.permission.MANAGE_MEDIA_PROJECTION",
            "android.permission.CAPTURE_VOICE_COMMUNICATION_OUTPUT",
            "android.permission.WRITE_SECURE_SETTINGS",
            "android.permission.READ_CLIPBOARD_IN_BACKGROUND",
        )

abstract class XposedSystemEventInstallers : XposedSystemUiInstallers() {
    private val sharedState = XposedInstallationState()
    protected val systemRegistered get() = sharedState.systemRegistered
    protected val appReceivers get() = sharedState.appReceivers
    protected val systemEventDedup get() = sharedState.systemEventDedup
    protected val hardwareKeyEventDedup get() = sharedState.hardwareKeyEventDedup
    protected val hardwareKeyCaptureUntilElapsed get() = sharedState.hardwareKeyCaptureUntilElapsed
    protected val subscribedSystemEvents get() = sharedState.subscribedSystemEvents
    protected val enabledShortXBehaviors get() = sharedState.enabledShortXBehaviors
    protected val enabledPackageBehaviors get() = sharedState.enabledPackageBehaviors
    internal val methodSessions get() = sharedState.methodSessions
    internal val crashGuards get() = sharedState.crashGuards
    protected val yAutoUid get() = sharedState.yAutoUid

    protected fun installSystemRuntimeHooks(context: Context, classLoader: ClassLoader, eventTypes: Set<String>) {
        ShortXCompatHookCatalog.subscribedCoreRuntimeHooks(eventTypes).forEach { family ->
            when (family) {
                ShortXCoreRuntimeHook.PROCESS_DEATH -> installProcessDeathHooks(context, classLoader)
                ShortXCoreRuntimeHook.TASK_REMOVAL -> installTaskRemovedHooks(context, classLoader)
                ShortXCoreRuntimeHook.BACK_NAVIGATION -> installBackNavigationHooks(context, classLoader)
                ShortXCoreRuntimeHook.ASSISTANT -> installAssistantHooks(context, classLoader)
            }
        }
    }

    protected fun installProcessDeathHooks(context: Context, classLoader: ClassLoader) {
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

    protected fun installTaskRemovedHooks(context: Context, classLoader: ClassLoader) {
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

    protected fun installBackNavigationHooks(context: Context, classLoader: ClassLoader) {
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


    protected fun installShortXHooksForSubscriptions(
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

    protected fun installShortXAccessibilityUserStateConstructorHook(
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

    protected fun installShortXBehaviorHooks(context: Context, classLoader: ClassLoader) {
        installShortXAccessibilityBehaviorHooks(context, classLoader)
        installShortXClipboardBehaviorHooks(context, classLoader)
        installShortXPermissionBehaviorHook(context, classLoader)
    }

    protected fun installShortXAccessibilityBehaviorHooks(context: Context, classLoader: ClassLoader) {
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

    protected fun installShortXClipboardBehaviorHooks(context: Context, classLoader: ClassLoader) {
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

    protected fun installShortXPermissionBehaviorHook(context: Context, classLoader: ClassLoader) {
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

    internal fun installShortXObserverHooks(
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
    protected fun installShortXInputHooks(context: Context, classLoader: ClassLoader) {
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

    protected fun installInputFilterStateHook(context: Context, classLoader: ClassLoader) {
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

    private data class InputHookTarget(
        val className: String,
        val methodNames: Set<String>,
        val matchAnyKeyEventMethod: Boolean = false,
    )

    protected fun installAssistantHooks(context: Context, classLoader: ClassLoader) {
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
        broadcastXposedRuntimeEvent(context, type, extras, now)

    }

}
