package com.yagay.yauto.platform.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import java.util.concurrent.atomic.AtomicBoolean

fun interface RuntimeEventEmitter {
    fun emit(event: RuntimeEvent)
}

interface AndroidEventSource {
    val id: String
    fun start(emitter: RuntimeEventEmitter)
    fun stop()
}

class SystemBroadcastEventSource(
    context: Context,
) : AndroidEventSource {
    override val id: String = "android.system.broadcasts"
    private val context = context.applicationContext
    private val started = AtomicBoolean(false)
    private var emitter: RuntimeEventEmitter? = null

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val typeId = when (action) {
                Intent.ACTION_SCREEN_ON -> "android.event.screen_on"
                Intent.ACTION_SCREEN_OFF -> "android.event.screen_off"
                Intent.ACTION_USER_PRESENT -> "android.event.user_present"
                Intent.ACTION_POWER_CONNECTED -> "android.event.power_connected"
                Intent.ACTION_POWER_DISCONNECTED -> "android.event.power_disconnected"
                Intent.ACTION_BATTERY_LOW -> "android.event.battery_low"
                Intent.ACTION_BATTERY_OKAY -> "android.event.battery_okay"
                Intent.ACTION_BATTERY_CHANGED -> "android.event.battery_changed"
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> "android.event.power_save_changed"
                Intent.ACTION_DEVICE_STORAGE_LOW -> "android.event.storage_low"
                Intent.ACTION_DEVICE_STORAGE_OK -> "android.event.storage_okay"
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> "android.event.airplane_mode_changed"
                Intent.ACTION_LOCALE_CHANGED -> "android.event.locale_changed"
                Intent.ACTION_TIMEZONE_CHANGED -> "android.event.timezone_changed"
                else -> "android.event.broadcast"
            }
            val payload = buildMap<String, ConfigValue> {
                put("action", ConfigValue.StringValue(action))
                if (action == Intent.ACTION_AIRPLANE_MODE_CHANGED && intent.hasExtra("state")) {
                    put("state", ConfigValue.BooleanValue(intent.getBooleanExtra("state", false)))
                }
            }
            emitter?.emit(RuntimeEvent(typeId, payload, source = id))
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val typeId = when (action) {
                Intent.ACTION_PACKAGE_ADDED -> "android.event.package_added"
                Intent.ACTION_PACKAGE_REMOVED -> "android.event.package_removed"
                Intent.ACTION_PACKAGE_REPLACED -> "android.event.package_replaced"
                else -> return
            }
            val pkg = intent.data?.schemeSpecificPart.orEmpty()
            emitter?.emit(
                RuntimeEvent(
                    typeId = typeId,
                    payload = buildMap {
                        put("package", ConfigValue.StringValue(pkg))
                        put("replacing", ConfigValue.BooleanValue(intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)))
                    },
                    source = id,
                )
            )
        }
    }

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter

        val systemFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_DEVICE_STORAGE_LOW)
            addAction(Intent.ACTION_DEVICE_STORAGE_OK)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        register(systemReceiver, systemFilter)
        register(packageReceiver, packageFilter)
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { context.unregisterReceiver(systemReceiver) }
        runCatching { context.unregisterReceiver(packageReceiver) }
        emitter = null
    }

    private fun register(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
    }
}

class NetworkEventSource(
    context: Context,
) : AndroidEventSource {
    override val id: String = "android.network.default"
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val started = AtomicBoolean(false)
    private var emitter: RuntimeEventEmitter? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            emit("android.event.network_available", network)
        }

        override fun onLost(network: Network) {
            emit("android.event.network_lost", network)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            emitter?.emit(
                RuntimeEvent(
                    typeId = "android.event.network_changed",
                    payload = networkPayload(network, capabilities),
                    source = id,
                )
            )
        }
    }

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
            .onFailure {
                started.set(false)
                this.emitter = null
                throw it
            }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        emitter = null
    }

    private fun emit(typeId: String, network: Network) {
        emitter?.emit(
            RuntimeEvent(
                typeId = typeId,
                payload = networkPayload(network, connectivity.getNetworkCapabilities(network)),
                source = id,
            )
        )
    }

    private fun networkPayload(network: Network, capabilities: NetworkCapabilities?): Map<String, ConfigValue> = buildMap {
        put("network", ConfigValue.StringValue(network.toString()))
        capabilities ?: return@buildMap
        put("internet", ConfigValue.BooleanValue(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)))
        put("validated", ConfigValue.BooleanValue(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)))
        put("wifi", ConfigValue.BooleanValue(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)))
        put("cellular", ConfigValue.BooleanValue(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)))
        put("ethernet", ConfigValue.BooleanValue(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)))
        put("vpn", ConfigValue.BooleanValue(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)))
    }
}
