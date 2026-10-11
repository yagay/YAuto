package com.yagay.yauto.platform.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

data class XposedHardwareKeySnapshot(
    val keyCode: Int,
    val scanCode: Int,
    val deviceId: Int,
    val action: Int,
    val deviceName: String = "",
    val deviceDescriptor: String = "",
    val vendorId: Int = 0,
    val productId: Int = 0,
)

object XposedSystemEventRuntimeBridge {
    @Volatile private var listener: ((RuntimeEvent) -> Unit)? = null
    private val nextHardwareKey = AtomicReference<CompletableDeferred<XposedHardwareKeySnapshot>?>(null)
    private data class ProbeWaiter(val nonce: String, val deferred: CompletableDeferred<Boolean>)
    private val transportProbe = AtomicReference<ProbeWaiter?>(null)

    fun attach(value: ((RuntimeEvent) -> Unit)?) {
        listener = value
    }

    suspend fun awaitNextHardwareKey(timeoutMs: Long): XposedHardwareKeySnapshot? {
        val deferred = CompletableDeferred<XposedHardwareKeySnapshot>()
        nextHardwareKey.getAndSet(deferred)?.cancel()
        return try {
            withTimeoutOrNull(timeoutMs.coerceIn(1_000L, 60_000L)) { deferred.await() }
        } finally {
            nextHardwareKey.compareAndSet(deferred, null)
        }
    }

    suspend fun awaitTransportProbe(nonce: String, timeoutMs: Long): Boolean {
        val waiter = ProbeWaiter(nonce, CompletableDeferred())
        transportProbe.getAndSet(waiter)?.deferred?.cancel()
        return try {
            withTimeoutOrNull(timeoutMs.coerceIn(500L, 10_000L)) {
                waiter.deferred.await()
            } == true
        } finally {
            transportProbe.compareAndSet(waiter, null)
        }
    }

    internal fun dispatch(intent: Intent) {
        if (intent.action != SystemBridgeProtocol.SYSTEM_EVENT_ACTION) return
        val type = intent.getStringExtra("type").orEmpty()
        if (type == SystemBridgeProtocol.EVENT_DELIVERY_PROBE_TYPE) {
            val expected = transportProbe.get()
            if (expected != null && expected.nonce ==
                intent.getStringExtra("nonce").orEmpty() &&
                transportProbe.compareAndSet(expected, null)) {
                if (expected.deferred.complete(true)) {
                    XposedEventTransportHealth.onForwarded(type)
                }
            }
            return
        }
        if (type == SystemBridgeProtocol.HARDWARE_KEY_CAPTURE_TYPE) {
            val waiter = nextHardwareKey.getAndSet(null)
            Log.i("YAutoKeyCapture", "LSPosed event dispatched: " +
                "keyCode=${intent.getIntExtra("keyCode", 0)} " +
                "scanCode=${intent.getIntExtra("scanCode", 0)} " +
                "waiterPresent=${waiter != null}")
            val forwarded = waiter?.complete(
                XposedHardwareKeySnapshot(
                    keyCode = intent.getIntExtra("keyCode", 0),
                    scanCode = intent.getIntExtra("scanCode", 0),
                    deviceId = intent.getIntExtra("deviceId", -1),
                    action = intent.getIntExtra("action", -1),
                    deviceName = intent.getStringExtra("deviceName").orEmpty(),
                    deviceDescriptor = intent.getStringExtra("deviceDescriptor").orEmpty(),
                    vendorId = intent.getIntExtra("vendorId", 0),
                    productId = intent.getIntExtra("productId", 0),
                )
            )
            if (forwarded == true) XposedEventTransportHealth.onForwarded(type)
            return
        }
        if (!type.startsWith("android.event.")) return
        val payload = buildMap<String, ConfigValue> {
            intent.extras?.keySet().orEmpty().forEach { key ->
                if (key == "type" || key == "timestampEpochMs" || key == "bridgeSource" ||
                    key == XposedEventAuth.EXTRA_TOKEN) return@forEach
                when (val value = intent.extras?.get(key)) {
                    is String -> put(key, ConfigValue.StringValue(value))
                    is Int -> put(key, ConfigValue.NumberValue(value.toDouble()))
                    is Long -> put(key, ConfigValue.NumberValue(value.toDouble()))
                    is Boolean -> put(key, ConfigValue.BooleanValue(value))
                }
            }
        }
        listener?.let { callback ->
            try {
                callback.invoke(
                    RuntimeEvent(
                        typeId = type,
                        payload = payload,
                        source = intent.getStringExtra("bridgeSource").orEmpty().ifBlank { "lsposed.system_server" },
                        timestampEpochMs = intent.getLongExtra("timestampEpochMs", System.currentTimeMillis()),
                    )
                )
                XposedEventTransportHealth.onForwarded(type)
            } catch (error: Exception) {
                // A valid broadcast was not delivered to automation runtime.
                XposedEventTransportHealth.onRuntimeFailure(type, error)
            }
        }
    }
}

/**
 * System-event receiver is public because hooked system_server and other app
 * processes use distinct UIDs. Verify the actual sender UID on Android 14+;
 * trusting only intent extras would allow an arbitrary app to spoof a trigger.
 */
class XposedSystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != SystemBridgeProtocol.SYSTEM_EVENT_ACTION) return
        val source = intent.getStringExtra("bridgeSource").orEmpty()
            .ifBlank { "lsposed.system_server" }
        val eventType = intent.getStringExtra("type").orEmpty()
        val isKeyCapture = eventType == SystemBridgeProtocol.HARDWARE_KEY_CAPTURE_TYPE
        if (Build.VERSION.SDK_INT >= 34) {
            val uid = getSentFromUid()
            // Some OEM Android 14+ builds return INVALID_UID (-1) for a
            // manifest receiver even when the broadcast came from hooked
            // system_server. Never equate this with a trusted sender: verify
            // the per-app-session random secret negotiated over the
            // signature-protected ordered system bridge instead.
            val authenticatedUnknownSystemSender =
                uid == Process.INVALID_UID &&
                source == "lsposed.system_server" &&
                XposedEventAuth.acceptLegacy(
                    intent.getStringExtra(XposedEventAuth.EXTRA_TOKEN).orEmpty()
                )
            val packages = if (uid >= 0 && uid != Process.SYSTEM_UID) {
                context.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
            } else emptyList()
            if (!XposedEventSenderVerifier.accept(
                    uid = uid,
                    source = source,
                    claimedPackage = intent.getStringExtra("package").orEmpty(),
                    senderPackages = packages,
                    authenticatedUnknownSystemSender = authenticatedUnknownSystemSender,
                )) {
                XposedEventTransportHealth.onRejected(
                    eventType, source, uid,
                    if (uid == Process.INVALID_UID) "unknown UID without valid system session"
                    else "sender UID is not authorized",
                )
                if (isKeyCapture) Log.w("YAutoKeyCapture", "Rejected LSPosed capture broadcast: " +
                    "senderUid=$uid source=$source authenticated=$authenticatedUnknownSystemSender")
                return
            }
            if (isKeyCapture && authenticatedUnknownSystemSender) {
                Log.i("YAutoKeyCapture", "Accepted authenticated system_server capture " +
                    "despite unavailable broadcast sender UID")
            }
        } else {
            // Android 12/13 lacks broadcast sender UID; fail closed for unauthenticated
            // package-originated broadcasts and verify a system_server session secret.
            if (source != "lsposed.system_server" ||
                !XposedEventAuth.acceptLegacy(intent.getStringExtra(XposedEventAuth.EXTRA_TOKEN).orEmpty())
            ) {
                XposedEventTransportHealth.onRejected(
                    eventType, source, Process.INVALID_UID, "missing authenticated legacy system session",
                )
                if (isKeyCapture) Log.w("YAutoKeyCapture", "Rejected unauthenticated LSPosed key event")
                return
            }
        }
        XposedEventTransportHealth.onAccepted(eventType)
        if (isKeyCapture) Log.i("YAutoKeyCapture", "Authenticated LSPosed capture broadcast received")
        XposedSystemEventRuntimeBridge.dispatch(intent)
    }
}

internal object XposedEventSenderVerifier {
    fun accept(
        uid: Int,
        source: String,
        claimedPackage: String,
        senderPackages: List<String>,
        authenticatedUnknownSystemSender: Boolean = false,
    ): Boolean {
        if (uid == Process.SYSTEM_UID) return true
        // Never trust an absent UID by itself. A session-authenticated
        // system_server broadcast is the only allowed INVALID_UID fallback;
        // arbitrary app and package-Hook broadcasts remain rejected.
        if (uid == Process.INVALID_UID) {
            return source == "lsposed.system_server" && authenticatedUnknownSystemSender
        }
        if (uid < 0 || source != "lsposed.package") return false
        return claimedPackage.isNotBlank() && claimedPackage in senderPackages
    }
}

/** Process-local secret expected by the app and published by the signed system bridge. */
internal object XposedEventAuth {
    const val EXTRA_TOKEN = "yautoSystemEventAuth"
    private val appExpected = AtomicReference("")
    private val systemPublished = AtomicReference("")
    fun expect(token: String) { appExpected.set(token) }
    fun publish(token: String) { systemPublished.set(token) }
    fun publishedToken(): String = systemPublished.get()
    fun acceptLegacy(candidate: String): Boolean {
        val expected = appExpected.get()
        if (expected.length < 32 || candidate.length != expected.length) return false
        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), candidate.toByteArray(Charsets.UTF_8))
    }
}
