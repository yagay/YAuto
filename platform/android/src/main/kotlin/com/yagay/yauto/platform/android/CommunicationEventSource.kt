package com.yagay.yauto.platform.android

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import java.util.concurrent.atomic.AtomicBoolean

/** Optional communication events. Receivers remain registered, but protected payloads are emitted only when permission allows. */
class CommunicationEventSource(context: Context) : AndroidEventSource {
    override val id: String = "android.communication.events"
    private val context = context.applicationContext
    private val started = AtomicBoolean(false)
    @Volatile private var emitter: RuntimeEventEmitter? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Telephony.Sms.Intents.SMS_RECEIVED_ACTION -> emitSms(intent)
                TelephonyManager.ACTION_PHONE_STATE_CHANGED -> emitPhoneState(intent)
            }
        }
    }

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        val filter = IntentFilter().apply {
            addAction(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
            addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
        } catch (error: Exception) {
            started.set(false)
            this.emitter = null
            throw error
        }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { context.unregisterReceiver(receiver) }
        emitter = null
    }

    private fun emitSms(intent: Intent) {
        if (!hasPermission(Manifest.permission.RECEIVE_SMS)) return
        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent).toList() }.getOrDefault(emptyList())
        if (messages.isEmpty()) return
        val address = messages.firstNotNullOfOrNull { message -> message.originatingAddress?.takeIf(String::isNotBlank) }.orEmpty()
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }
        val timestamp = messages.minOfOrNull { it.timestampMillis } ?: System.currentTimeMillis()
        emitter?.emit(
            RuntimeEvent(
                typeId = "android.event.sms_received",
                payload = mapOf(
                    "address" to ConfigValue.StringValue(address),
                    "body" to ConfigValue.StringValue(body),
                    "timestampEpochMs" to ConfigValue.NumberValue(timestamp.toDouble()),
                    "parts" to ConfigValue.NumberValue(messages.size.toDouble()),
                ),
                source = id,
            )
        )
    }

    private fun emitPhoneState(intent: Intent) {
        if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) return
        val rawState = intent.getStringExtra(TelephonyManager.EXTRA_STATE).orEmpty()
        val state = when (rawState) {
            TelephonyManager.EXTRA_STATE_RINGING -> "ringing"
            TelephonyManager.EXTRA_STATE_OFFHOOK -> "offhook"
            TelephonyManager.EXTRA_STATE_IDLE -> "idle"
            else -> "unknown"
        }
        val number = if (hasPermission(Manifest.permission.READ_CALL_LOG) || hasPermission(Manifest.permission.READ_PHONE_NUMBERS)) {
            intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()
        } else ""
        emitter?.emit(
            RuntimeEvent(
                typeId = "android.event.phone_state_changed",
                payload = mapOf(
                    "state" to ConfigValue.StringValue(state),
                    "number" to ConfigValue.StringValue(number),
                ),
                source = id,
            )
        )
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
