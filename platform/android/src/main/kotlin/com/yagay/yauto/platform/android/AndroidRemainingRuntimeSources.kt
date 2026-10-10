package com.yagay.yauto.platform.android

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.SensorPrivacyManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.CancellationSignal
import android.os.LocaleList
import android.os.PowerManager
import android.os.storage.StorageManager
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.log10
import kotlin.math.sqrt

internal class TorchStateMonitor(context: Context, camera: CameraManager) {
    private val states = ConcurrentHashMap<String, Boolean>()

    init {
        runCatching {
            camera.registerTorchCallback(context.mainExecutor, object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    states[cameraId] = enabled
                }

                override fun onTorchModeUnavailable(cameraId: String) {
                    states[cameraId] = false
                }
            })
        }
    }

    fun enabled(cameraId: String): Boolean = states[cameraId] == true
    fun anyEnabled(): Boolean = states.values.any { it }
    fun value(): ConfigValue.ObjectValue = ConfigValue.ObjectValue(states.mapValues { ConfigValue.BooleanValue(it.value) })
}


class SubscriptionChangeEventSource(context: Context) : AndroidEventSource {
    override val id: String = "android.sim.subscriptions"
    private val context = context.applicationContext
    private val subscriptions = this.context.getSystemService(SubscriptionManager::class.java)
    private val started = AtomicBoolean(false)
    private var emitter: RuntimeEventEmitter? = null
    private var previous: Snapshot? = null

    private data class Snapshot(
        val activeIds: List<Int>,
        val dataId: Int,
        val smsId: Int,
        val voiceId: Int,
    )

    private val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
        override fun onSubscriptionsChanged() {
            val next = snapshot()
            val old = previous
            previous = next
            if (old == null || old == next) return
            emitter?.emit(
                com.yagay.yauto.core.model.RuntimeEvent(
                    typeId = "android.event.sim_subscription_changed",
                    payload = mapOf(
                        "activeIds" to ConfigValue.ListValue(next.activeIds.map { ConfigValue.NumberValue(it.toDouble()) }),
                        "defaultDataId" to ConfigValue.NumberValue(next.dataId.toDouble()),
                        "defaultSmsId" to ConfigValue.NumberValue(next.smsId.toDouble()),
                        "defaultVoiceId" to ConfigValue.NumberValue(next.voiceId.toDouble()),
                        "activeSetChanged" to ConfigValue.BooleanValue(old.activeIds != next.activeIds),
                        "defaultDataChanged" to ConfigValue.BooleanValue(old.dataId != next.dataId),
                        "defaultSmsChanged" to ConfigValue.BooleanValue(old.smsId != next.smsId),
                        "defaultVoiceChanged" to ConfigValue.BooleanValue(old.voiceId != next.voiceId),
                    ),
                    source = id,
                )
            )
        }
    }

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        previous = snapshot()
        runCatching { subscriptions.addOnSubscriptionsChangedListener(context.mainExecutor, listener) }
            .onFailure {
                started.set(false)
                this.emitter = null
                previous = null
                throw it
            }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { subscriptions.removeOnSubscriptionsChangedListener(listener) }
        emitter = null
        previous = null
    }

    private fun snapshot(): Snapshot {
        val active = runCatching {
            subscriptions.activeSubscriptionInfoList.orEmpty().map { it.subscriptionId }.sorted()
        }.getOrDefault(emptyList())
        return Snapshot(
            activeIds = active,
            dataId = SubscriptionManager.getDefaultDataSubscriptionId(),
            smsId = SubscriptionManager.getDefaultSmsSubscriptionId(),
            voiceId = SubscriptionManager.getDefaultVoiceSubscriptionId(),
        )
    }

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    }
}
