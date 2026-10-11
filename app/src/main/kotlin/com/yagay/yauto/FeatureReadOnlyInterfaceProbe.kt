package com.yagay.yauto

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
