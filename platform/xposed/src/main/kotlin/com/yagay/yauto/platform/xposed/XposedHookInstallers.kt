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

internal const val YAUTO_HOOK_PACKAGE = "com.yagay.yauto"

abstract class XposedHookInstallers : XposedMethodHookInstallers() {
    protected fun registerSystemBridge(context: Context, classLoader: ClassLoader) {
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
            ownPackage = YAUTO_HOOK_PACKAGE,
        )
    }

    protected fun installShortXPackageHooks(
        context: Context,
        packageName: String,
        classLoader: ClassLoader,
    ) {
        XposedPackageHookCoordinator(
            systemUi = { installShortXSystemUiHooks(context, classLoader) },
            statusChip = { installShortXStatusChipHooks(context, classLoader) },
            tileLabel = { installShortXTileLabelHooks(context, classLoader) },
            nfc = { installShortXNfcHooks(context, classLoader) },
            mediaProvider = { installShortXMediaProviderHooks(context, classLoader) },
            telephonyProvider = { installShortXTelephonyProviderHooks(context, classLoader) },
            inputConnection = { installShortXInputConnectionHook(context, packageName, classLoader) },
        ).install(packageName)
    }

    protected fun installShortXRuntimeInitHook(
        context: Context,
        packageName: String,
        classLoader: ClassLoader,
    ) {
        val clazz = runCatching {
            classLoader.loadClass("com.android.internal.os.RuntimeInit\$LoggingHandler")
        }.getOrNull() ?: return
        clazz.declaredMethods
            .filter(XposedRuntimeInitHookPolicy::isCrashHandler)
            .forEach { method ->
                val key = XposedRuntimeInitHookPolicy.installationKey(packageName, method)
                if (!installedHooks.add(key)) return@forEach
                method.isAccessible = true
                hook(method).intercept { chain ->
                    val thread = chain.args.firstOrNull { it is Thread } as? Thread
                    val error = chain.args.firstOrNull { it is Throwable } as? Throwable
                    if (error != null) runCatching { crashGuards[packageName]?.recordFatal(error) }
                    emitPackageRuntimeEvent(
                        context,
                        "android.event.process_uncaught_exception",
                        XposedUncaughtExceptionSnapshot.create(packageName, thread, error, method.name),
                    )
                    chain.proceed()
                }
            }
    }

    /**
     * Real status-bar chip inside SystemUI, activated only when SystemUI is an LSPosed
     * target. The ordered receiver requires the YAuto signature permission.
     */

}
