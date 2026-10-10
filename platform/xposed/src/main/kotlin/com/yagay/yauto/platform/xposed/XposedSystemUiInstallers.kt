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

/** Installs all SystemUI chip, tile and status events in the SystemUI process. */
abstract class XposedSystemUiInstallers : XposedProviderInstallers() {
    private val systemUiTileLabels = ConcurrentHashMap<String, String>()
    private val systemUiTileReceiverRegistered = AtomicBoolean(false)
    private val systemUiChipRegistered = AtomicBoolean(false)
    @Volatile private var systemUiChipController: ShortXStatusChipController? = null
    protected fun installShortXStatusChipHooks(context: Context, classLoader: ClassLoader) {
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
                val installation = XposedHookInstallationGuard.install(installedHooks, key) {
                    method.isAccessible = true
                hook(method).intercept { chain ->
                    val result = chain.proceed()
                    runCatching { controller.attach(chain.thisObject) }
                    result
                }
                }
                installation.exceptionOrNull()?.let { error ->
                    log(Log.WARN, "YAuto", "Hook installation failed: " + key, error)
                }
                if (installation.getOrNull() == true) hookedCount++
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
    protected fun installShortXTileLabelHooks(context: Context, classLoader: ClassLoader) {
        val clazz = runCatching {
            classLoader.loadClass("com.android.systemui.qs.external.CustomTile")
        }.getOrNull() ?: return
        var hookCount = 0
        clazz.declaredMethods.filter { it.name == "getTileLabel" &&
            it.parameterCount == 0 && CharSequence::class.java.isAssignableFrom(it.returnType)
        }.forEach { method ->
            val key = "yauto-qs-label|" + method.toGenericString()
            val installation = XposedHookInstallationGuard.install(installedHooks, key) {
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
            }
            if (installation.getOrNull() == true) hookCount++
            installation.exceptionOrNull()?.let { error ->
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

    protected fun installShortXSystemUiHooks(context: Context, classLoader: ClassLoader) {
        fun observeConstructors(
            classNames: List<String>,
            eventType: String,
        ) {
            classNames.forEach { className ->
                val clazz = runCatching { classLoader.loadClass(className) }.getOrNull() ?: return@forEach
                clazz.declaredConstructors.forEach { constructor ->
                    val key = "shortx-systemui-constructor|" + eventType + "|" + constructor.toGenericString()
                    val installation = XposedHookInstallationGuard.install(installedHooks, key) {
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
                    installation.exceptionOrNull()?.let { error ->
                        log(Log.WARN, "YAuto", "Hook installation failed: " + key, error)
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
                    val installation = XposedHookInstallationGuard.install(installedHooks, key) {
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
                    installation.exceptionOrNull()?.let { error ->
                        log(Log.WARN, "YAuto", "Hook installation failed: " + key, error)
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

}
