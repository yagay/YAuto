package com.yagay.yauto.platform.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import java.util.concurrent.atomic.AtomicBoolean

/** Additional public/system broadcasts that back the reference-completion trigger pack. */
class ReferenceCompletionBroadcastEventSource(context: Context) : AndroidEventSource {
    override val id: String = "android.reference_completion.broadcasts"
    private val context = context.applicationContext
    private val started = AtomicBoolean(false)
    private var emitter: RuntimeEventEmitter? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val sourceIntent = intent ?: return
            val typeId = eventTypeForReferenceBroadcast(sourceIntent.action ?: return) ?: return
            emitter?.emit(RuntimeEvent(typeId = typeId, payload = referenceBroadcastPayload(sourceIntent), source = id))
        }
    }

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        try {
            register(systemFilter())
            register(packageFilter())
            register(mediaFilter())
        } catch (error: Throwable) {
            stop()
            throw error
        }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { context.unregisterReceiver(receiver) }
        emitter = null
    }

    private fun register(filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
    }

    private fun systemFilter() = IntentFilter().apply {
        REFERENCE_SYSTEM_ACTIONS.forEach(::addAction)
    }

    private fun packageFilter() = IntentFilter().apply {
        REFERENCE_PACKAGE_ACTIONS.forEach(::addAction)
        addDataScheme("package")
    }

    private fun mediaFilter() = IntentFilter().apply {
        REFERENCE_MEDIA_ACTIONS.forEach(::addAction)
        addDataScheme("file")
    }
}

internal val REFERENCE_SYSTEM_ACTIONS: List<String> = listOf(
    "android.intent.action.ACTION_SHUTDOWN",
    "android.intent.action.USER_UNLOCKED",
    "android.intent.action.WALLPAPER_CHANGED",
    "android.intent.action.DOCK_EVENT",
    "android.intent.action.DREAMING_STARTED",
    "android.intent.action.DREAMING_STOPPED",
    "android.os.action.DEVICE_IDLE_MODE_CHANGED",
    "android.os.action.LIGHT_DEVICE_IDLE_MODE_CHANGED",
    "android.intent.action.MANAGED_PROFILE_AVAILABLE",
    "android.intent.action.MANAGED_PROFILE_UNAVAILABLE",
    "android.intent.action.MANAGED_PROFILE_UNLOCKED",
    "android.app.action.NEXT_ALARM_CLOCK_CHANGED",
    "android.media.AUDIO_BECOMING_NOISY",
    "android.media.RINGER_MODE_CHANGED",
    "android.bluetooth.adapter.action.STATE_CHANGED",
    "android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED",
    "android.bluetooth.adapter.action.DISCOVERY_STARTED",
    "android.bluetooth.adapter.action.DISCOVERY_FINISHED",
    "android.bluetooth.device.action.BOND_STATE_CHANGED",
    "android.bluetooth.device.action.ACL_CONNECTED",
    "android.bluetooth.device.action.ACL_DISCONNECTED",
    "android.bluetooth.device.action.ACL_DISCONNECT_REQUESTED",
    "android.net.wifi.WIFI_STATE_CHANGED",
    "android.net.wifi.STATE_CHANGE",
    "android.net.wifi.RSSI_CHANGED",
    "android.net.wifi.supplicant.STATE_CHANGE",
    "android.net.wifi.supplicant.CONNECTION_CHANGE",
    "android.intent.action.PACKAGES_SUSPENDED",
    "android.intent.action.PACKAGES_UNSUSPENDED",
    "android.intent.action.EXTERNAL_APPLICATIONS_AVAILABLE",
    "android.intent.action.EXTERNAL_APPLICATIONS_UNAVAILABLE",
    "android.intent.action.CONFIGURATION_CHANGED",
    "android.intent.action.USER_BACKGROUND",
    "android.intent.action.USER_FOREGROUND",
    "android.intent.action.MY_PACKAGE_REPLACED",
)

internal val REFERENCE_PACKAGE_ACTIONS: List<String> = listOf(
    "android.intent.action.PACKAGE_CHANGED",
    "android.intent.action.PACKAGE_FULLY_REMOVED",
    "android.intent.action.PACKAGE_DATA_CLEARED",
    "android.intent.action.PACKAGE_RESTARTED",
)

internal val REFERENCE_MEDIA_ACTIONS: List<String> = listOf(
    "android.intent.action.MEDIA_MOUNTED",
    "android.intent.action.MEDIA_UNMOUNTED",
    "android.intent.action.MEDIA_EJECT",
    "android.intent.action.MEDIA_REMOVED",
    "android.intent.action.MEDIA_BAD_REMOVAL",
    "android.intent.action.MEDIA_CHECKING",
    "android.intent.action.MEDIA_NOFS",
    "android.intent.action.MEDIA_UNMOUNTABLE",
    "android.intent.action.MEDIA_SHARED",
    "android.intent.action.MEDIA_SCANNER_STARTED",
    "android.intent.action.MEDIA_SCANNER_FINISHED",
)

internal val REFERENCE_BROADCAST_EVENT_TYPES: Map<String, String> = linkedMapOf(
    "android.intent.action.ACTION_SHUTDOWN" to "android.event.shutdown",
    "android.intent.action.USER_UNLOCKED" to "android.event.user_unlocked",
    "android.intent.action.WALLPAPER_CHANGED" to "android.event.wallpaper_changed",
    "android.intent.action.DOCK_EVENT" to "android.event.dock_changed",
    "android.intent.action.DREAMING_STARTED" to "android.event.dreaming_started",
    "android.intent.action.DREAMING_STOPPED" to "android.event.dreaming_stopped",
    "android.os.action.DEVICE_IDLE_MODE_CHANGED" to "android.event.device_idle_mode_changed",
    "android.os.action.LIGHT_DEVICE_IDLE_MODE_CHANGED" to "android.event.light_device_idle_mode_changed",
    "android.intent.action.MANAGED_PROFILE_AVAILABLE" to "android.event.managed_profile_available",
    "android.intent.action.MANAGED_PROFILE_UNAVAILABLE" to "android.event.managed_profile_unavailable",
    "android.intent.action.MANAGED_PROFILE_UNLOCKED" to "android.event.managed_profile_unlocked",
    "android.app.action.NEXT_ALARM_CLOCK_CHANGED" to "android.event.next_alarm_changed",
    "android.media.AUDIO_BECOMING_NOISY" to "android.event.audio_becoming_noisy",
    "android.media.RINGER_MODE_CHANGED" to "android.event.ringer_mode_changed",
    "android.bluetooth.adapter.action.STATE_CHANGED" to "android.event.bluetooth_state_changed",
    "android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED" to "android.event.bluetooth_connection_state_changed",
    "android.bluetooth.adapter.action.DISCOVERY_STARTED" to "android.event.bluetooth_discovery_started",
    "android.bluetooth.adapter.action.DISCOVERY_FINISHED" to "android.event.bluetooth_discovery_finished",
    "android.bluetooth.device.action.BOND_STATE_CHANGED" to "android.event.bluetooth_bond_state_changed",
    "android.bluetooth.device.action.ACL_CONNECTED" to "android.event.bluetooth_acl_connected",
    "android.bluetooth.device.action.ACL_DISCONNECTED" to "android.event.bluetooth_acl_disconnected",
    "android.bluetooth.device.action.ACL_DISCONNECT_REQUESTED" to "android.event.bluetooth_acl_disconnect_requested",
    "android.net.wifi.WIFI_STATE_CHANGED" to "android.event.wifi_state_changed",
    "android.net.wifi.STATE_CHANGE" to "android.event.wifi_network_state_changed",
    "android.net.wifi.RSSI_CHANGED" to "android.event.wifi_rssi_changed",
    "android.net.wifi.supplicant.STATE_CHANGE" to "android.event.wifi_supplicant_state_changed",
    "android.net.wifi.supplicant.CONNECTION_CHANGE" to "android.event.wifi_supplicant_connection_changed",
    "android.intent.action.PACKAGE_CHANGED" to "android.event.package_changed",
    "android.intent.action.PACKAGE_FULLY_REMOVED" to "android.event.package_fully_removed",
    "android.intent.action.PACKAGE_DATA_CLEARED" to "android.event.package_data_cleared",
    "android.intent.action.PACKAGE_RESTARTED" to "android.event.package_restarted",
    "android.intent.action.PACKAGES_SUSPENDED" to "android.event.packages_suspended",
    "android.intent.action.PACKAGES_UNSUSPENDED" to "android.event.packages_unsuspended",
    "android.intent.action.EXTERNAL_APPLICATIONS_AVAILABLE" to "android.event.external_apps_available",
    "android.intent.action.EXTERNAL_APPLICATIONS_UNAVAILABLE" to "android.event.external_apps_unavailable",
    "android.intent.action.MEDIA_MOUNTED" to "android.event.media_mounted",
    "android.intent.action.MEDIA_UNMOUNTED" to "android.event.media_unmounted",
    "android.intent.action.MEDIA_EJECT" to "android.event.media_eject",
    "android.intent.action.MEDIA_REMOVED" to "android.event.media_removed",
    "android.intent.action.MEDIA_BAD_REMOVAL" to "android.event.media_bad_removal",
    "android.intent.action.MEDIA_CHECKING" to "android.event.media_checking",
    "android.intent.action.MEDIA_NOFS" to "android.event.media_nofs",
    "android.intent.action.MEDIA_UNMOUNTABLE" to "android.event.media_unmountable",
    "android.intent.action.MEDIA_SHARED" to "android.event.media_shared",
    "android.intent.action.MEDIA_SCANNER_STARTED" to "android.event.media_scanner_started",
    "android.intent.action.MEDIA_SCANNER_FINISHED" to "android.event.media_scanner_finished",
    "android.intent.action.CONFIGURATION_CHANGED" to "android.event.configuration_changed",
    "android.intent.action.USER_BACKGROUND" to "android.event.user_background",
    "android.intent.action.USER_FOREGROUND" to "android.event.user_foreground",
    "android.intent.action.MY_PACKAGE_REPLACED" to "android.event.my_package_replaced",
)

internal fun eventTypeForReferenceBroadcast(action: String): String? = REFERENCE_BROADCAST_EVENT_TYPES[action]

internal fun referenceBroadcastPayload(intent: Intent): Map<String, ConfigValue> = buildMap {
    val action = intent.action.orEmpty()
    put("action", ConfigValue.StringValue(action))
    intent.dataString?.let { put("data", ConfigValue.StringValue(it)) }
    intent.data?.takeIf { it.scheme == "package" }?.schemeSpecificPart?.let { put("package", ConfigValue.StringValue(it)) }
    val changedPackages = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST)?.filter(String::isNotBlank).orEmpty()
    if (changedPackages.isNotEmpty()) put("packages", ConfigValue.ListValue(changedPackages.map(ConfigValue::StringValue)))
    if (intent.hasExtra(Intent.EXTRA_UID)) put("uid", ConfigValue.NumberValue(intent.getIntExtra(Intent.EXTRA_UID, -1).toDouble()))
    if (intent.hasExtra("android.intent.extra.DOCK_STATE")) put("dockState", ConfigValue.NumberValue(intent.getIntExtra("android.intent.extra.DOCK_STATE", 0).toDouble()))
    if (intent.hasExtra("android.bluetooth.adapter.extra.STATE")) put("state", ConfigValue.NumberValue(intent.getIntExtra("android.bluetooth.adapter.extra.STATE", -1).toDouble()))
    if (intent.hasExtra("android.bluetooth.adapter.extra.PREVIOUS_STATE")) put("previousState", ConfigValue.NumberValue(intent.getIntExtra("android.bluetooth.adapter.extra.PREVIOUS_STATE", -1).toDouble()))
    if (intent.hasExtra("android.bluetooth.device.extra.BOND_STATE")) put("bondState", ConfigValue.NumberValue(intent.getIntExtra("android.bluetooth.device.extra.BOND_STATE", -1).toDouble()))
    if (intent.hasExtra("wifi_state")) put("wifiState", ConfigValue.NumberValue(intent.getIntExtra("wifi_state", -1).toDouble()))
    if (intent.hasExtra("previous_wifi_state")) put("previousWifiState", ConfigValue.NumberValue(intent.getIntExtra("previous_wifi_state", -1).toDouble()))
    if (intent.hasExtra("newRssi")) put("rssi", ConfigValue.NumberValue(intent.getIntExtra("newRssi", Int.MIN_VALUE).toDouble()))
    if (intent.hasExtra("connected")) put("connected", ConfigValue.BooleanValue(intent.getBooleanExtra("connected", false)))
}
