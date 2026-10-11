package com.yagay.yauto.platform.xposed

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.SystemClock
import android.util.Log
import android.view.InputEvent
import android.view.KeyEvent
import java.lang.reflect.Constructor
import java.lang.reflect.Method

internal val SHORTX_PERMISSION_ALLOWLIST = setOf(
            "android.permission.MANAGE_MEDIA_PROJECTION",
            "android.permission.CAPTURE_VOICE_COMMUNICATION_OUTPUT",
            "android.permission.WRITE_SECURE_SETTINGS",
            "android.permission.READ_CLIPBOARD_IN_BACKGROUND",
        )

/**
 * Installs SystemServer events and ShortX-compatible input/behavior interceptors.
 * This component has no dependency on the XposedModule inheritance chain.
 */
internal class XposedSystemServerHookInstaller(
    private val state: XposedInstallationState,
    private val interceptMethod: (Method, (XposedHookInvocation) -> Any?) -> Unit,
    private val interceptConstructor: (Constructor<*>, (XposedHookInvocation) -> Any?) -> Unit,
    private val reportLog: (Int, String, Throwable?) -> Unit,
) {
    private val installedHooks get() = state.installedHooks
    private val systemEventDedup get() = state.systemEventDedup
    private val hardwareKeyEventDedup get() = state.hardwareKeyEventDedup
    private val hardwareKeyCaptureUntilElapsed get() = state.hardwareKeyCaptureUntilElapsed
    private val subscribedSystemEvents get() = state.subscribedSystemEvents
    private val enabledShortXBehaviors get() = state.enabledShortXBehaviors
    private val enabledPackageBehaviors get() = state.enabledPackageBehaviors
    private val yAutoUid get() = state.yAutoUid

    private fun log(level: Int, tag: String, message: String, error: Throwable? = null) {
        reportLog(level, message, error)
    }

    fun installSystemRuntimeHooks(context: Context, classLoader: ClassLoader, eventTypes: Set<String>) {
        ShortXCompatHookCatalog.subscribedCoreRuntimeHooks(eventTypes).forEach { family ->
            when (family) {
                ShortXCoreRuntimeHook.PROCESS_DEATH -> installProcessDeathHooks(context, classLoader)
                ShortXCoreRuntimeHook.TASK_REMOVAL -> installTaskRemovedHooks(context, classLoader)
                ShortXCoreRuntimeHook.BACK_NAVIGATION -> installBackNavigationHooks(context, classLoader)
                ShortXCoreRuntimeHook.ASSISTANT -> installAssistantHooks(context, classLoader)
            }
        }
    }

    fun installProcessDeathHooks(context: Context, classLoader: ClassLoader) {
        val clazz = runCatching { classLoader.loadClass("com.android.server.am.ProcessRecord") }.getOrNull() ?: return
        clazz.declaredMethods
            .filter { method ->
                method.name in setOf("killLocked", "kill", "makeInactive") &&
                    method.returnType == Void.TYPE
            }
            .forEach { method ->
                val key = "system-process-death|" + method.toGenericString()
                val installation = XposedHookInstallationGuard.install(installedHooks, key) {
                    method.isAccessible = true
                    interceptMethod(method) { invocation ->
                    val process = processSnapshot(invocation.thisObject)
                    val result = invocation.proceed()
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
                installation.exceptionOrNull()?.let { error ->
                    log(Log.ERROR, "YAuto", "Process death hook installation failed", error)
                }
            }
    }

    fun installTaskRemovedHooks(context: Context, classLoader: ClassLoader) {
        val recentTasks = runCatching { classLoader.loadClass("com.android.server.wm.RecentTasks") }.getOrNull()
        recentTasks?.declaredMethods
            ?.filter { method ->
                method.name == "remove" &&
                    method.parameterTypes.any { it.name == "com.android.server.wm.Task" }
            }
            ?.forEach { method ->
                val key = "system-task-removed|" + method.toGenericString()
                val installation = XposedHookInstallationGuard.install(installedHooks, key) {
                    method.isAccessible = true
                interceptMethod(method) { invocation ->
                    val task = invocation.args.firstOrNull { it?.javaClass?.name == "com.android.server.wm.Task" }
                    val snapshot = taskSnapshot(task)
                    val result = invocation.proceed()
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
                installation.exceptionOrNull()?.let { error ->
                    log(Log.ERROR, "YAuto", "Hook installation failed: system-task-removed", error)
                }
            }

        val taskClass = runCatching { classLoader.loadClass("com.android.server.wm.Task") }.getOrNull() ?: return
        taskClass.declaredMethods
            .filter { it.name in setOf("removeImmediately", "removeIfPossible") }
            .forEach { method ->
                val key = "system-task-direct-remove|" + method.toGenericString()
                val installation = XposedHookInstallationGuard.install(installedHooks, key) {
                    method.isAccessible = true
                interceptMethod(method) { invocation ->
                    val snapshot = taskSnapshot(invocation.thisObject)
                    val result = invocation.proceed()
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
                installation.exceptionOrNull()?.let { error ->
                    log(Log.ERROR, "YAuto", "Hook installation failed: system-task-direct-remove", error)
                }
            }
    }

    fun installBackNavigationHooks(context: Context, classLoader: ClassLoader) {
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
                val installation = XposedHookInstallationGuard.install(installedHooks, key) {
                    method.isAccessible = true
                interceptMethod(method) { invocation ->
                    if (started) {
                        emitSystemRuntimeEvent(
                            context = context,
                            type = "android.event.back_navigation_started",
                            dedupKey = "back-start",
                            extras = mapOf("method" to method.name),
                            dedupWindowMs = 150L,
                        )
                    }
                    val result = invocation.proceed()
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
                installation.exceptionOrNull()?.let { error ->
                    log(Log.ERROR, "YAuto", "Hook installation failed: system-back-nav", error)
                }
            }
    }


    fun installShortXHooksForSubscriptions(
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

    fun installShortXAccessibilityUserStateConstructorHook(
        context: Context,
        classLoader: ClassLoader,
    ) {
        val clazz = runCatching {
            classLoader.loadClass("com.android.server.accessibility.AccessibilityUserState")
        }.getOrNull() ?: return
        clazz.declaredConstructors.forEach { constructor ->
            val key = "shortx-accessibility-user-state|" + constructor.toGenericString()
            val installation = XposedHookInstallationGuard.install(installedHooks, key) {
                constructor.isAccessible = true
            interceptConstructor(constructor) { invocation ->
                val result = invocation.proceed()
                emitSystemRuntimeEvent(
                    context = context,
                    type = "android.event.accessibility_user_state_created",
                    dedupKey = "accessibility-user-state:" + System.identityHashCode(invocation.thisObject),
                    extras = mapOf("className" to clazz.name),
                    dedupWindowMs = 100L,
                )
                result
            }
            }
            installation.exceptionOrNull()?.let { error ->
                log(Log.ERROR, "YAuto", "Hook installation failed: shortx-accessibility-user-state", error)
            }
        }
    }

    fun installShortXBehaviorHooks(context: Context, classLoader: ClassLoader) {
        installShortXAccessibilityBehaviorHooks(context, classLoader)
        installShortXClipboardBehaviorHooks(context, classLoader)
        installShortXPermissionBehaviorHook(context, classLoader)
    }

    fun installShortXAccessibilityBehaviorHooks(context: Context, classLoader: ClassLoader) {
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
                    val installation = XposedHookInstallationGuard.install(installedHooks, key) {
                        method.isAccessible = true
                    interceptMethod(method) { invocation ->
                        if (
                            SystemBridgeProtocol.SHORTX_BEHAVIOR_ACCESSIBILITY in enabledShortXBehaviors.get() &&
                            isYAutoCaller(context)
                        ) {
                            replacement
                        } else {
                            invocation.proceed()
                        }
                    }
                    }
                    installation.exceptionOrNull()?.let { error ->
                        log(Log.ERROR, "YAuto", "Hook installation failed: shortx-behavior-accessibility", error)
                    }
                }
        }
    }

    fun installShortXClipboardBehaviorHooks(context: Context, classLoader: ClassLoader) {
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
                val installation = XposedHookInstallationGuard.install(installedHooks, key) {
                    method.isAccessible = true
                interceptMethod(method) { invocation ->
                    if (
                        SystemBridgeProtocol.SHORTX_BEHAVIOR_CLIPBOARD in enabledShortXBehaviors.get() &&
                        isYAutoCaller(context)
                    ) {
                        true
                    } else {
                        invocation.proceed()
                    }
                }
                }
                installation.exceptionOrNull()?.let { error ->
                    log(Log.ERROR, "YAuto", "Hook installation failed: shortx-behavior-clipboard", error)
                }
            }
    }

    fun installShortXPermissionBehaviorHook(context: Context, classLoader: ClassLoader) {
        val clazz = runCatching { classLoader.loadClass("android.app.ContextImpl") }.getOrNull() ?: return
        clazz.declaredMethods
            .filter { method ->
                method.name == "checkCallingPermission" &&
                    method.returnType == Int::class.javaPrimitiveType &&
                    method.parameterTypes.firstOrNull() == String::class.java
            }
            .forEach { method ->
                val key = "shortx-behavior-permission|" + method.toGenericString()
                val installation = XposedHookInstallationGuard.install(installedHooks, key) {
                    method.isAccessible = true
                interceptMethod(method) { invocation ->
                    val permission = invocation.args.firstOrNull() as? String
                    if (
                        SystemBridgeProtocol.SHORTX_BEHAVIOR_PERMISSION in enabledShortXBehaviors.get() &&
                        isYAutoCaller(context) &&
                        permission in SHORTX_PERMISSION_ALLOWLIST
                    ) {
                        PackageManager.PERMISSION_GRANTED
                    } else {
                        invocation.proceed()
                    }
                }
                }
                installation.exceptionOrNull()?.let { error ->
                    log(Log.ERROR, "YAuto", "Hook installation failed: shortx-behavior-permission", error)
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
                        val installation = XposedHookInstallationGuard.install(installedHooks, key) {
                            method.isAccessible = true
                            interceptMethod(method) { invocation ->
                                if (spec.after) {
                                    val result = invocation.proceed()
                                    runCatching {
                                        observerEvents.emit(context, spec, className, method.name, invocation.thisObject, invocation.args)
                                    }
                                    result
                                } else {
                                    runCatching {
                                        observerEvents.emit(context, spec, className, method.name, invocation.thisObject, invocation.args)
                                    }
                                    invocation.proceed()
                                }
                            }
                        }
                        installation.exceptionOrNull()?.let { error ->
                            log(Log.WARN, "YAuto", "ShortX observer unavailable: " + key, error)
                        }
                    }
            }
        }
    }

    private val observerEvents by lazy {
        XposedObserverEventEmitter(state) { context, type, identity, extras, windowMs ->
            emitSystemRuntimeEvent(context, type, identity, extras, windowMs)
        }
    }

    /**
     * Clean-room equivalent of ShortX's InputManagerHook.
     *
     * Observe the input chain at several Android framework layers because OEM ROMs move
     * interceptKeyBeforeQueueing/interceptKeyBeforeDispatching between policy callback classes.
     * We never change the return value here; YAuto only observes and forwards KeyEvent metadata.
     */
    fun installShortXInputHooks(context: Context, classLoader: ClassLoader) {
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
                    XposedInputHookMethodPolicy.matches(
                        method, target.methodNames, target.matchAnyKeyEventMethod,
                    )
                }
                .forEach { method ->
                    val key = "shortx-input|" + method.toGenericString()
                    val installation = XposedHookInstallationGuard.install(installedHooks, key) {
                        method.isAccessible = true
                    interceptMethod(method) { invocation ->
                        val event = invocation.args.firstOrNull { it is KeyEvent } as? KeyEvent
                        if (event != null) {
                            runCatching {
                                hardwareKeyEvents.handleSystemKeyEvent(context, event, target.className, method.name)
                            }
                        }
                        invocation.proceed()
                    }
                    }
                    installation.exceptionOrNull()?.let { error ->
                        log(Log.ERROR, "YAuto", "Hook installation failed: shortx-input", error)
                    }
                }
        }

        installInputFilterStateHook(context, classLoader)
        log(Log.INFO, "YAuto", "ShortX-compatible input hooks installed")
    }

    fun installInputFilterStateHook(context: Context, classLoader: ClassLoader) {
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
                val installation = XposedHookInstallationGuard.install(installedHooks, key) {
                    method.isAccessible = true
                interceptMethod(method) { invocation ->
                    val enabled = invocation.args.firstOrNull { it is Boolean } as? Boolean
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
                    invocation.proceed()
                }
                }
                installation.exceptionOrNull()?.let { error ->
                    log(Log.ERROR, "YAuto", "Hook installation failed: shortx-input-filter-state", error)
                }
            }
    }

    private val hardwareKeyEvents by lazy {
        XposedHardwareKeyEventHandler(state) { context, type, dedupKey, extras, windowMs ->
            emitSystemRuntimeEvent(context, type, dedupKey, extras, windowMs)
        }
    }

    private data class InputHookTarget(
        val className: String,
        val methodNames: Set<String>,
        val matchAnyKeyEventMethod: Boolean = false,
    )

    fun installAssistantHooks(context: Context, classLoader: ClassLoader) {
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
                    val installation = XposedHookInstallationGuard.install(installedHooks, key) {
                        method.isAccessible = true
                    interceptMethod(method) { invocation ->
                        val component = invocation.args.filterIsInstance<android.content.ComponentName>().firstOrNull()
                        val info = reflectedValue(
                            invocation.thisObject,
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
                        invocation.proceed()
                    }
                    }
                    installation.exceptionOrNull()?.let { error ->
                        log(Log.ERROR, "YAuto", "Hook installation failed: system-assistant", error)
                    }
                }
        }
    }

    private val systemEventPublisher by lazy { XposedSystemEventPublisher(state) }

    private fun emitSystemRuntimeEvent(
        context: Context,
        type: String,
        dedupKey: String,
        extras: Map<String, Any?>,
        dedupWindowMs: Long = 1_000L,
    ) = systemEventPublisher.emit(context, type, dedupKey, extras, dedupWindowMs)
}
