package com.yagay.yauto.platform.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import java.util.concurrent.atomic.AtomicBoolean

/** Detailed default-network changes used by transport/validation/metered automation rules. */
class NetworkProfileEventSource(context: Context) : AndroidEventSource {
    override val id: String = "android.network.profile"
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val started = AtomicBoolean(false)
    private var emitter: RuntimeEventEmitter? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            emitter?.emit(RuntimeEvent(EVENT_ID, payload(capabilities), source = id))
        }

        override fun onLost(network: Network) {
            emitter?.emit(
                RuntimeEvent(
                    EVENT_ID,
                    mapOf("connected" to ConfigValue.BooleanValue(false)),
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

    private fun payload(caps: NetworkCapabilities): Map<String, ConfigValue> = mapOf(
        "connected" to ConfigValue.BooleanValue(true),
        "internet" to ConfigValue.BooleanValue(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)),
        "validated" to ConfigValue.BooleanValue(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)),
        "metered" to ConfigValue.BooleanValue(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)),
        "wifi" to ConfigValue.BooleanValue(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)),
        "cellular" to ConfigValue.BooleanValue(caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)),
        "ethernet" to ConfigValue.BooleanValue(caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)),
        "vpn" to ConfigValue.BooleanValue(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)),
    )

    private companion object {
        const val EVENT_ID = "android.event.network_profile_changed"
    }
}
