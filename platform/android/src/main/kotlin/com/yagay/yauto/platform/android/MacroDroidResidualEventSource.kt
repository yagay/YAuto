package com.yagay.yauto.platform.android

import android.content.Context
import android.os.Build
import android.telephony.ServiceState
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.view.accessibility.AccessibilityManager
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

class MacroDroidResidualEventSource(
    context: Context,
) : AndroidEventSource {
    override val id: String = "android.macrodroid.residual.events"
    private val context = context.applicationContext
    private val accessibility = this.context.getSystemService(AccessibilityManager::class.java)
    private val telephony = this.context.getSystemService(TelephonyManager::class.java)
    private val mainExecutor: Executor = this.context.mainExecutor
    private val started = AtomicBoolean(false)
    @Volatile private var emitter: RuntimeEventEmitter? = null
    private var shizukuListener: Any? = null

    private val accessibilityListener = AccessibilityManager.AccessibilityStateChangeListener { enabled ->
        emitter?.emit(
            RuntimeEvent(
                typeId = "android.event.accessibility_state_changed",
                payload = mapOf("enabled" to ConfigValue.BooleanValue(enabled)),
                source = id,
            )
        )
    }

    private val telephonyCallback = object : TelephonyCallback(), TelephonyCallback.ServiceStateListener {
        override fun onServiceStateChanged(serviceState: ServiceState) {
            val inService = serviceState.state == ServiceState.STATE_IN_SERVICE
            emitter?.emit(
                RuntimeEvent(
                    typeId = "android.event.cellular_service_changed",
                    payload = mapOf(
                        "available" to ConfigValue.BooleanValue(inService),
                        "state" to ConfigValue.NumberValue(serviceState.state.toDouble()),
                        "operator" to ConfigValue.StringValue(serviceState.operatorAlphaLong.orEmpty()),
                        "roaming" to ConfigValue.BooleanValue(serviceState.roaming),
                    ),
                    source = id,
                )
            )
        }
    }

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        accessibility.addAccessibilityStateChangeListener(accessibilityListener)
        runCatching { telephony.registerTelephonyCallback(mainExecutor, telephonyCallback) }
        installShizukuDeadListener()
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { accessibility.removeAccessibilityStateChangeListener(accessibilityListener) }
        runCatching { telephony.unregisterTelephonyCallback(telephonyCallback) }
        removeShizukuDeadListener()
        emitter = null
    }

    private fun installShizukuDeadListener() {
        runCatching {
            val shizuku = Class.forName("rikka.shizuku.Shizuku")
            val listenerClass = Class.forName("rikka.shizuku.Shizuku\$OnBinderDeadListener")
            val listener = Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
            ) { _, method, _ ->
                if (method.name.contains("binder", ignoreCase = true) || method.name.contains("dead", ignoreCase = true)) {
                    emitter?.emit(
                        RuntimeEvent(
                            typeId = "android.event.shizuku_stopped",
                            payload = emptyMap(),
                            source = id,
                        )
                    )
                }
                null
            }
            val add = shizuku.methods.firstOrNull {
                it.name == "addBinderDeadListener" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].isAssignableFrom(listenerClass)
            } ?: shizuku.methods.firstOrNull {
                it.name == "addBinderDeadListener" && it.parameterTypes.size == 1
            } ?: return@runCatching
            add.invoke(null, listener)
            shizukuListener = listener
        }
    }

    private fun removeShizukuDeadListener() {
        val listener = shizukuListener ?: return
        shizukuListener = null
        runCatching {
            val shizuku = Class.forName("rikka.shizuku.Shizuku")
            shizuku.methods.firstOrNull {
                it.name == "removeBinderDeadListener" && it.parameterTypes.size == 1
            }?.invoke(null, listener)
        }
    }
}
