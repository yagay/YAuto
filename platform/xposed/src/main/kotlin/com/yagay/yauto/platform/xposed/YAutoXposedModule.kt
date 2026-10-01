package com.yagay.yauto.platform.xposed

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
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.util.concurrent.atomic.AtomicBoolean

/** Only system_server is scoped. No arbitrary commands or class/method names cross this bridge. */
class YAutoXposedModule : XposedModule() {
    private val registered = AtomicBoolean(false)
    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        try {
            val server = param.classLoader.loadClass("com.android.server.SystemServer")
            server.declaredMethods.filter { it.name == "startOtherServices" }.forEach { method ->
                hook(method).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val field = server.getDeclaredField("mSystemContext").apply { isAccessible = true }
                        val context = field.get(chain.thisObject) as? Context
                        if (context != null) register(context)
                    } catch (error: Exception) { log(Log.ERROR, "YAuto", "System bridge registration failed", error) }
                    result
                }
            }
        } catch (error: Exception) { log(Log.ERROR, "YAuto", "System bridge hooks unavailable", error) }
    }

    private fun register(context: Context) {
        if (!registered.compareAndSet(false, true)) return
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
                            power.javaClass.getMethod("goToSleep", Long::class.javaPrimitiveType).invoke(power, SystemClock.uptimeMillis())
                        }
                        SystemOperations.EXPAND_NOTIFICATIONS, SystemOperations.COLLAPSE_PANELS -> {
                            val status = context.getSystemService("statusbar") ?: error("Status bar service unavailable")
                            val method = if (intent.getStringExtra("operation") == SystemOperations.EXPAND_NOTIFICATIONS) "expandNotificationsPanel" else "collapsePanels"
                            status.javaClass.getMethod(method).invoke(status)
                        }
                        else -> error("Unsupported operation")
                    }
                    response.putBoolean("success", true)
                } catch (error: Exception) {
                    response.putBoolean("success", false); response.putString("error", error.cause?.message ?: error.message)
                    log(Log.ERROR, "YAuto", "System operation failed", error)
                }
                setResultExtras(response)
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, IntentFilter(SystemBridgeProtocol.ACTION), SystemBridgeProtocol.PERMISSION, null, Context.RECEIVER_EXPORTED)
            else { @Suppress("DEPRECATION") context.registerReceiver(receiver, IntentFilter(SystemBridgeProtocol.ACTION), SystemBridgeProtocol.PERMISSION, null) }
            log(Log.INFO, "YAuto", "System bridge ready")
        } catch (error: Exception) { registered.set(false); throw error }
    }
}
