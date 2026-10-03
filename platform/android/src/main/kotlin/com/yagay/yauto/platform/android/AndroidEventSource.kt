package com.yagay.yauto.platform.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.nfc.NfcAdapter
import android.os.Build
import android.os.PowerManager
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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
    private var lastDarkMode: Boolean? = null

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == NfcAdapter.ACTION_ADAPTER_STATE_CHANGED) {
                val state = intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, NfcAdapter.STATE_OFF)
                if (state != NfcAdapter.STATE_ON && state != NfcAdapter.STATE_OFF) return
            }
            if (action == Intent.ACTION_CONFIGURATION_CHANGED) {
                val darkMode = currentDarkMode()
                if (lastDarkMode == darkMode) return
                lastDarkMode = darkMode
            }

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
                NfcAdapter.ACTION_ADAPTER_STATE_CHANGED -> "android.event.nfc_state_changed"
                LocationManager.MODE_CHANGED_ACTION -> "android.event.location_mode_changed"
                Intent.ACTION_CONFIGURATION_CHANGED -> "android.event.dark_mode_changed"
                Intent.ACTION_LOCALE_CHANGED -> "android.event.locale_changed"
                Intent.ACTION_TIMEZONE_CHANGED -> "android.event.timezone_changed"
                Intent.ACTION_TIME_TICK -> "android.event.time_tick"
                Intent.ACTION_TIME_CHANGED -> "android.event.time_changed"
                Intent.ACTION_DATE_CHANGED -> "android.event.date_changed"
                else -> "android.event.broadcast"
            }
            val payload = buildMap<String, ConfigValue> {
                put("action", ConfigValue.StringValue(action))
                if (action == Intent.ACTION_AIRPLANE_MODE_CHANGED && intent.hasExtra("state")) {
                    put("state", ConfigValue.BooleanValue(intent.getBooleanExtra("state", false)))
                }
                if (action == NfcAdapter.ACTION_ADAPTER_STATE_CHANGED) {
                    put(
                        "enabled",
                        ConfigValue.BooleanValue(
                            intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, NfcAdapter.STATE_OFF) == NfcAdapter.STATE_ON
                        ),
                    )
                }
                if (action == LocationManager.MODE_CHANGED_ACTION) {
                    val enabled = runCatching {
                        this@SystemBroadcastEventSource.context.getSystemService(LocationManager::class.java).isLocationEnabled
                    }.getOrDefault(false)
                    put("enabled", ConfigValue.BooleanValue(enabled))
                }
                if (action == Intent.ACTION_CONFIGURATION_CHANGED) {
                    put("enabled", ConfigValue.BooleanValue(currentDarkMode()))
                }
                if (action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) {
                    put(
                        "enabled",
                        ConfigValue.BooleanValue(
                            this@SystemBroadcastEventSource.context.getSystemService(PowerManager::class.java).isPowerSaveMode
                        ),
                    )
                }
                if (action == Intent.ACTION_BATTERY_CHANGED) {
                    val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                    if (level >= 0 && scale > 0) {
                        put("percent", ConfigValue.NumberValue(level * 100.0 / scale))
                    }
                    val temperatureTenths = intent.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                    if (temperatureTenths != Int.MIN_VALUE) {
                        put("temperatureC", ConfigValue.NumberValue(temperatureTenths / 10.0))
                    }
                    put(
                        "present",
                        ConfigValue.BooleanValue(intent.getBooleanExtra(android.os.BatteryManager.EXTRA_PRESENT, false)),
                    )
                    put(
                        "plugged",
                        ConfigValue.StringValue(
                            batteryPluggedName(intent.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0))
                        ),
                    )
                    put(
                        "status",
                        ConfigValue.StringValue(
                            batteryStatusName(
                                intent.getIntExtra(
                                    android.os.BatteryManager.EXTRA_STATUS,
                                    android.os.BatteryManager.BATTERY_STATUS_UNKNOWN,
                                )
                            )
                        ),
                    )
                    put(
                        "health",
                        ConfigValue.StringValue(
                            batteryHealthName(
                                intent.getIntExtra(
                                    android.os.BatteryManager.EXTRA_HEALTH,
                                    android.os.BatteryManager.BATTERY_HEALTH_UNKNOWN,
                                )
                            )
                        ),
                    )
                    val voltageMv = intent.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
                    if (voltageMv != Int.MIN_VALUE) {
                        put("voltageMv", ConfigValue.NumberValue(voltageMv.toDouble()))
                    }
                }
                if (action in setOf(Intent.ACTION_TIME_TICK, Intent.ACTION_TIME_CHANGED, Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) {
                    putAll(currentTimePayload())
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
        lastDarkMode = currentDarkMode()

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
            addAction(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
            addAction(LocationManager.MODE_CHANGED_ACTION)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        try {
            register(systemReceiver, systemFilter)
            register(packageReceiver, packageFilter)
        } catch (error: Exception) {
            stop()
            throw error
        }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { context.unregisterReceiver(systemReceiver) }
        runCatching { context.unregisterReceiver(packageReceiver) }
        emitter = null
        lastDarkMode = null
    }

    private fun register(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
    }

    private fun currentDarkMode(): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun currentTimePayload(): Map<String, ConfigValue> {
        val now = ZonedDateTime.now()
        return mapOf(
            "hour" to ConfigValue.NumberValue(now.hour.toDouble()),
            "minute" to ConfigValue.NumberValue(now.minute.toDouble()),
            "weekday" to ConfigValue.NumberValue(now.dayOfWeek.value.toDouble()),
            "day" to ConfigValue.NumberValue(now.dayOfMonth.toDouble()),
            "month" to ConfigValue.NumberValue(now.monthValue.toDouble()),
            "year" to ConfigValue.NumberValue(now.year.toDouble()),
            "date" to ConfigValue.StringValue(now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)),
            "zone" to ConfigValue.StringValue(now.zone.id),
        )
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
        put("metered", ConfigValue.BooleanValue(!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)))
        put("roaming", ConfigValue.BooleanValue(!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)))
        put("restricted", ConfigValue.BooleanValue(!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)))
        put("suspended", ConfigValue.BooleanValue(!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)))
        put("wifi", ConfigValue.BooleanValue(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)))
        put("cellular", ConfigValue.BooleanValue(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)))
        put("ethernet", ConfigValue.BooleanValue(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)))
        put("vpn", ConfigValue.BooleanValue(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)))
        put("bluetooth", ConfigValue.BooleanValue(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)))
    }
}
