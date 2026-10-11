package com.yagay.yauto.platform.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
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

    internal fun dispatch(intent: Intent) {
        if (intent.action != SystemBridgeProtocol.SYSTEM_EVENT_ACTION) return
        val type = intent.getStringExtra("type").orEmpty()
        if (type == SystemBridgeProtocol.HARDWARE_KEY_CAPTURE_TYPE) {
            nextHardwareKey.getAndSet(null)?.complete(
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
        listener?.invoke(
            RuntimeEvent(
                typeId = type,
                payload = payload,
                source = intent.getStringExtra("bridgeSource").orEmpty().ifBlank { "lsposed.system_server" },
                timestampEpochMs = intent.getLongExtra("timestampEpochMs", System.currentTimeMillis()),
            )
        )
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
        if (Build.VERSION.SDK_INT >= 34) {
            val uid = getSentFromUid()
            val packages = context.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
            if (!XposedEventSenderVerifier.accept(
                    uid = uid,
                    source = source,
                    claimedPackage = intent.getStringExtra("package").orEmpty(),
                    senderPackages = packages,
                )) return
        } else {
            // Android 12/13 lacks broadcast sender UID; fail closed for unauthenticated
            // package-originated broadcasts and verify a system_server session secret.
            if (source != "lsposed.system_server" ||
                !XposedEventAuth.acceptLegacy(intent.getStringExtra(XposedEventAuth.EXTRA_TOKEN).orEmpty())
            ) return
        }
        XposedSystemEventRuntimeBridge.dispatch(intent)
    }
}

internal object XposedEventSenderVerifier {
    fun accept(uid: Int, source: String, claimedPackage: String, senderPackages: List<String>): Boolean {
        if (uid == Process.SYSTEM_UID) return true
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
