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
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class YAutoXposedModule : XposedHookInstallers() {
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
        if (!XposedPackageInitPolicy.shouldInitialize(param.packageName, param.isFirstPackage, YAUTO_HOOK_PACKAGE)) return
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

}
