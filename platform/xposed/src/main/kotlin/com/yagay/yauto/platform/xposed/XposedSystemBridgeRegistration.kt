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


/** Ordered, permission-protected system-server IPC, isolated from hook lifecycle. */
internal fun registerXposedSystemBridge(
    context: Context,
    classLoader: ClassLoader,
    systemRegistered: AtomicBoolean,
    enabledShortXBehaviors: AtomicReference<Set<String>>,
    subscribedSystemEvents: AtomicReference<Set<String>>,
    hardwareKeyCaptureUntilElapsed: AtomicLong,
    yAutoUid: AtomicLong,
    installBehaviorHooks: () -> Unit,
    installSubscriptions: (Set<String>) -> Unit,
    installInputHooks: () -> Unit,
    emitLog: (Int, String, Throwable?) -> Unit,
    ownPackage: String,
) {
        if (!systemRegistered.compareAndSet(false, true)) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(received: Context?, intent: Intent?) {
                if (intent?.action != SystemBridgeProtocol.ACTION || !isOrderedBroadcast) return
                val response = Bundle().apply { putInt("version", SystemBridgeProtocol.VERSION) }
                try {
                    require(intent.getIntExtra("version", -1) == SystemBridgeProtocol.VERSION) { "Protocol mismatch" }
                    when (intent.getStringExtra("operation")) {
                        SystemBridgeProtocol.PING -> Unit
                        SystemBridgeProtocol.APP_PROCESS_START -> {
                            val pkg = intent.getStringExtra("package").orEmpty().trim()
                            val requestedUser = intent.getDoubleExtra("userId", -1.0)
                            require(Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+").matches(pkg)) {
                                "Invalid application package"
                            }
                            require(requestedUser.isFinite() && requestedUser in 0.0..99.0 &&
                                requestedUser.toInt().toDouble() == requestedUser) { "Invalid user ID" }
                            val userId = requestedUser.toInt()
                            // Resolve ApplicationInfo for the requested Android user. Do not inject
                            // a process under a guessed UID or emulate it with an Activity start.
                            val pm = context.packageManager
                            val info = if (userId == 0) {
                                pm.getApplicationInfo(pkg, 0)
                            } else {
                                // PackageManager.getApplicationInfoAsUser is hidden on this
                                // SDK. Look up the actual user-specific ApplicationInfo via
                                // reflection inside system_server; never substitute user 0.
                                val lookup = pm.javaClass.methods.firstOrNull {
                                    it.name == "getApplicationInfoAsUser" && it.parameterCount == 3
                                } ?: error("Cross-user package lookup unavailable on this ROM")
                                lookup.isAccessible = true
                                lookup.invoke(pm, pkg, 0, userId) as? ApplicationInfo
                                    ?: error("Target package not installed for requested user")
                            }
                            require(info.uid > 0 && info.packageName == pkg && info.enabled) { "Target app unavailable" }
                            val localServices = classLoader.loadClass("com.android.server.LocalServices")
                            val amInterface = classLoader.loadClass("android.app.ActivityManagerInternal")
                            val getService = localServices.getMethod("getService", Class::class.java)
                            val am = getService.invoke(null, amInterface)
                                ?: error("ActivityManagerInternal unavailable on this ROM")
                            val start = amInterface.methods.firstOrNull { method ->
                                method.name == "startProcess" && method.parameterCount == 6 &&
                                    method.parameterTypes[0] == String::class.java &&
                                    method.parameterTypes[1] == ApplicationInfo::class.java
                            } ?: error("ActivityManagerInternal.startProcess signature unsupported")
                            // This is Android-managed process creation, without bringing up an Activity.
                            // AMS may subsequently reclaim an idle process.
                            start.invoke(am, info.processName, info, false, false, "yauto", null)
                            response.putString("package", pkg)
                            response.putInt("userId", userId)
                            response.putBoolean("dispatched", true)
                        }
                        SystemBridgeProtocol.STATUS_ICON_SET,
                        SystemBridgeProtocol.STATUS_ICON_REMOVE -> {
                            // Only allocate YAuto-prefixed slots: never override stock system icons.
                            val requestedSlot = intent.getStringExtra("slot").orEmpty().trim()
                            require(Regex("[a-z][a-z0-9_]{0,23}").matches(requestedSlot)) {
                                "Invalid YAuto icon slot"
                            }
                            val slot = "yauto_" + requestedSlot
                            val status = context.getSystemService("statusbar")
                                ?: error("Status bar manager unavailable")
                            if (intent.getStringExtra("operation") == SystemBridgeProtocol.STATUS_ICON_REMOVE) {
                                val remove = status.javaClass.methods.firstOrNull {
                                    it.name == "removeIcon" && it.parameterCount == 1 &&
                                        it.parameterTypes[0] == String::class.java
                                } ?: error("StatusBarManager.removeIcon unavailable on this ROM")
                                remove.invoke(status, slot)
                            } else {
                                val name = if (intent.getStringExtra("iconSource") == "android_drawable") {
                                    intent.getStringExtra("drawable").orEmpty().also {
                                        require(Regex("[a-z][a-z0-9_]{0,63}").matches(it)) {
                                            "Invalid framework drawable name"
                                        }
                                    }
                                } else when (intent.getStringExtra("icon")) {
                                    "info" -> "ic_dialog_info"
                                    "warning" -> "ic_dialog_alert"
                                    "lock" -> "ic_lock_idle_lock"
                                    "upload" -> "ic_menu_upload"
                                    "save" -> "ic_menu_save"
                                    else -> error("Unsupported built-in icon")
                                }
                                val resId = context.resources.getIdentifier(name, "drawable", "android")
                                require(resId != 0) { "Android icon resource unavailable" }
                                val set = status.javaClass.methods.firstOrNull {
                                    it.name == "setIcon" && it.parameterCount == 4 &&
                                        it.parameterTypes[0] == String::class.java &&
                                        it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                                        it.parameterTypes[2] == Int::class.javaPrimitiveType &&
                                        it.parameterTypes[3] == String::class.java
                                } ?: error("StatusBarManager.setIcon unavailable on this ROM")
                                set.invoke(status, slot, resId, 0, "YAuto: " + requestedSlot)
                                status.javaClass.methods.firstOrNull {
                                    it.name == "setIconVisibility" && it.parameterCount == 2
                                }?.invoke(status, slot, true)
                            }
                            response.putString("slot", slot)
                        }
                        SystemBridgeProtocol.SHORTX_BEHAVIOR_SET -> {
                            installBehaviorHooks()
                            val behavior = intent.getStringExtra("behavior").orEmpty()
                            require(behavior in setOf(
                                SystemBridgeProtocol.SHORTX_BEHAVIOR_ACCESSIBILITY,
                                SystemBridgeProtocol.SHORTX_BEHAVIOR_CLIPBOARD,
                                SystemBridgeProtocol.SHORTX_BEHAVIOR_PERMISSION,
                            )) { "Unsupported ShortX behavior" }
                            val enabled = intent.getBooleanExtra("enabled", false)
                            enabledShortXBehaviors.updateAndGet { current ->
                                if (enabled) current + behavior else current - behavior
                            }
                            response.putString("behavior", behavior)
                            response.putBoolean("enabled", enabled)
                        }
                        SystemBridgeProtocol.SYSTEM_EVENT_SUBSCRIPTIONS_SET -> {
                            val values = intent.getStringArrayListExtra("eventTypes").orEmpty()
                                .asSequence()
                                .map(String::trim)
                                .filter { it.startsWith("android.event.") }
                                .distinct()
                                .take(256)
                                .toSet()
                            val token = intent.getStringExtra(XposedEventAuth.EXTRA_TOKEN).orEmpty()
                            require(token.length in 32..128) { "Missing system event authentication token" }
                            XposedEventAuth.publish(token)
                            subscribedSystemEvents.set(values)
                            installSubscriptions(values)
                            response.putInt("subscriptionCount", values.size)
                        }
                        SystemBridgeProtocol.HARDWARE_KEY_CAPTURE_START -> {
                            installInputHooks()
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
                    emitLog(Log.ERROR, "System operation failed", error)
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
            runCatching {
                context.packageManager.getApplicationInfo(ownPackage, 0).uid
            }.onSuccess { uid ->
                yAutoUid.set(uid.toLong())
            }
            // Install event hook families on demand after the authenticated subscription update.
            emitLog(Log.INFO, "System bridge ready", null)
        } catch (error: Exception) {
            systemRegistered.set(false)
            throw error
        }
    }



