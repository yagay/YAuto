package com.yagay.yauto

import android.app.AlarmManager
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.location.LocationManager
import android.nfc.NfcManager
import android.os.BatteryManager
import android.media.AudioDeviceInfo
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.media.AudioManager
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.platform.android.isUsageStatsAccessGranted

/**
 * Explicit allowlist of read-only state queries. No action executor, event
 * matcher, shell, Root, LSPosed Hook, or state evaluator is run here.
 *
 * A returned boolean false is a valid API result: it proves the interface
 * responded, NOT that a desired state or automation action is working.
 */
internal object ReadOnlyFeatureProbeCatalog {
    val keys: Set<String> = setOf(
        "notification_listener_access",
        "usage_stats_access",
        "accessibility_service_access",
        "airplane_mode",
        "device_idle",
        "device_secure",
        "keyguard_locked",
        "audio.music_active",
        "audio.mode",
        "data_saver",
        "private_dns",
        "vpn_active",
        "screen", "charging", "power_save", "nfc_enabled", "location_enabled",
        "auto_rotate", "dark_mode", "headset_connected", "network",
        "battery_level", "battery_temperature", "battery_present", "battery_plugged",
        "media_volume", "brightness", "screen_timeout", "stay_awake_while_charging", "bluetooth_enabled",
        "next_alarm_set", "network_validated", "network_internet",
        "network_restricted", "network_suspended",
    )

    fun queryKey(featureId: String, kind: FeatureKind): String? {
        if (kind != FeatureKind.STATE && kind != FeatureKind.CONDITION) return null
        val key = when {
            featureId.startsWith("android.state.") -> featureId.removePrefix("android.state.")
            featureId.startsWith("android.condition.") -> featureId.removePrefix("android.condition.")
            else -> return null
        }
        return key.takeIf { it in keys }
    }
}

internal class FeatureReadOnlyInterfaceProbe(context: Context) {
    private val context = context.applicationContext

    /**
     * Each branch performs a single OS getter. The caller enforces timeout
     * and catches SecurityException/OEM framework failures individually.
     * No SSID, notification content, Bluetooth address or host names leak
     * into exported reports.
     */
    suspend fun query(key: String): FeatureProbeResult {
        require(key in ReadOnlyFeatureProbeCatalog.keys) { "Unregistered read-only probe" }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val (interfaceName, value) = when (key) {
                "notification_listener_access" ->
                    "NotificationManagerCompat.getEnabledListenerPackages" to
                        (context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)).toString()
                "usage_stats_access" ->
                    "AppOpsManager usage access" to isUsageStatsAccessGranted(context).toString()
                "accessibility_service_access" ->
                    "AccessibilityManager.getEnabledAccessibilityServiceList" to
                        context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
                            .getEnabledAccessibilityServiceList(
                                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
                            ).any { it.resolveInfo.serviceInfo.packageName == context.packageName }.toString()
                "airplane_mode" ->
                    "Settings.Global.AIRPLANE_MODE_ON" to
                        (Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0).toString()
                "device_idle" ->
                    "PowerManager.isDeviceIdleMode" to
                        context.getSystemService(PowerManager::class.java).isDeviceIdleMode.toString()
                "device_secure" ->
                    "KeyguardManager.isDeviceSecure" to
                        context.getSystemService(KeyguardManager::class.java).isDeviceSecure.toString()
                "keyguard_locked" ->
                    "KeyguardManager.isKeyguardLocked" to
                        context.getSystemService(KeyguardManager::class.java).isKeyguardLocked.toString()
                "audio.music_active" ->
                    "AudioManager.isMusicActive" to
                        context.getSystemService(AudioManager::class.java).isMusicActive.toString()
                "audio.mode" ->
                    "AudioManager.mode" to
                        context.getSystemService(AudioManager::class.java).mode.toString()
                "data_saver" ->
                    "ConnectivityManager.restrictBackgroundStatus" to
                        context.getSystemService(ConnectivityManager::class.java)
                            .restrictBackgroundStatus.toString()
                "private_dns" ->
                    "Settings.Global.private_dns_mode" to
                        (Settings.Global.getString(context.contentResolver, "private_dns_mode") ?: "unset")
                            .take(32)
                "vpn_active" -> {
                    val manager = context.getSystemService(ConnectivityManager::class.java)
                    "ConnectivityManager.NetworkCapabilities.TRANSPORT_VPN" to
                        (manager.getNetworkCapabilities(manager.activeNetwork)
                            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true).toString()
                }
                "screen" ->
                    "PowerManager.isInteractive" to
                        context.getSystemService(PowerManager::class.java).isInteractive.toString()
                "charging" ->
                    "BatteryManager.isCharging" to
                        context.getSystemService(BatteryManager::class.java).isCharging.toString()
                "power_save" ->
                    "PowerManager.isPowerSaveMode" to
                        context.getSystemService(PowerManager::class.java).isPowerSaveMode.toString()
                "nfc_enabled" -> {
                    val adapter = context.getSystemService(NfcManager::class.java)?.defaultAdapter
                        ?: error("NFC hardware unavailable")
                    "NfcAdapter.isEnabled" to adapter.isEnabled.toString()
                }
                "location_enabled" ->
                    "LocationManager.isLocationEnabled" to
                        context.getSystemService(LocationManager::class.java).isLocationEnabled.toString()
                "auto_rotate" ->
                    "Settings.System.ACCELEROMETER_ROTATION" to
                        (Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1).toString()
                "dark_mode" ->
                    "Configuration.UI_MODE_NIGHT_MASK" to
                        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                            Configuration.UI_MODE_NIGHT_YES).toString()
                "headset_connected" -> {
                    val types = setOf(
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_USB_HEADSET,
                    )
                    val outputs = context.getSystemService(AudioManager::class.java)
                        .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    "AudioManager.getDevices" to outputs.any { it.type in types }.toString()
                }
                "network" -> {
                    val network = context.getSystemService(ConnectivityManager::class.java)
                    val caps = network.getNetworkCapabilities(network.activeNetwork)
                    val transports = if (caps == null) listOf("none") else buildList {
                        add("connected")
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("bluetooth")
                    }
                    "ConnectivityManager.getNetworkCapabilities" to transports.joinToString(",")
                }
                "battery_level", "battery_temperature", "battery_present", "battery_plugged" -> {
                    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                        ?: error("Battery status unavailable")
                    when (key) {
                        "battery_level" -> {
                            val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                            val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                            require(level >= 0 && scale > 0 && level <= scale) { "Invalid battery reading" }
                            "ACTION_BATTERY_CHANGED.level" to (level * 100.0 / scale).toString()
                        }
                        "battery_temperature" -> {
                            val value = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                            require(value != Int.MIN_VALUE) { "Battery temperature unavailable" }
                            "ACTION_BATTERY_CHANGED.temperature" to (value / 10.0).toString()
                        }
                        "battery_present" ->
                            "ACTION_BATTERY_CHANGED.present" to
                                battery.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false).toString()
                        else ->
                            "ACTION_BATTERY_CHANGED.plugged" to
                                battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0).toString()
                    }
                }
                "media_volume" -> {
                    val audio = context.getSystemService(AudioManager::class.java)
                    val minimum = audio.getStreamMinVolume(AudioManager.STREAM_MUSIC)
                    val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    require(maximum > minimum) { "Volume range unavailable" }
                    "AudioManager.getStreamVolume" to
                        ((audio.getStreamVolume(AudioManager.STREAM_MUSIC) - minimum) *
                            100.0 / (maximum - minimum)).toString()
                }
                "brightness" ->
                    "Settings.System.SCREEN_BRIGHTNESS" to
                        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS).toString()
                "stay_awake_while_charging" ->
                    "Settings.Global.STAY_ON_WHILE_PLUGGED_IN" to
                        Settings.Global.getInt(
                            context.contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0
                        ).toString()
                "screen_timeout" ->
                    "Settings.System.SCREEN_OFF_TIMEOUT" to
                        Settings.System.getLong(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT).toString()
                "bluetooth_enabled" -> {
                    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                        ?: error("Bluetooth hardware unavailable")
                    "BluetoothAdapter.isEnabled" to adapter.isEnabled.toString()
                }
                "next_alarm_set" ->
                    "AlarmManager.nextAlarmClock" to
                        (context.getSystemService(AlarmManager::class.java).nextAlarmClock != null).toString()
                "network_validated", "network_internet", "network_restricted", "network_suspended" -> {
                    val manager = context.getSystemService(ConnectivityManager::class.java)
                    val caps = manager.getNetworkCapabilities(manager.activeNetwork)
                    val value = when (key) {
                        "network_validated" ->
                            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                        "network_internet" ->
                            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                        "network_restricted" ->
                            caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                        else ->
                            caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                    }
                    "ConnectivityManager.NetworkCapabilities.$key" to value.toString()
                }
                else -> error("Probe is not available")
            }
            FeatureProbeResult(
                status = FeatureProbeStatus.RESPONDED,
                interfaceName = interfaceName,
                observedValue = value,
            )
        }
    }
}
