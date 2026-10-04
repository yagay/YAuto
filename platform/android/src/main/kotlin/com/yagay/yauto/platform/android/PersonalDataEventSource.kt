package com.yagay.yauto.platform.android

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Telephony
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import java.util.concurrent.atomic.AtomicBoolean

/** Emits provider-change events for permission-gated personal-data sources. */
class PersonalDataEventSource(context: Context) : AndroidEventSource {
    override val id: String = "android.personal_data.observers"
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val started = AtomicBoolean(false)
    private var emitter: RuntimeEventEmitter? = null
    private val observers = mutableListOf<ContentObserver>()
    private var lastCallId: Long? = null
    private var lastSmsId: Long? = null

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        register(CalendarContract.Events.CONTENT_URI, "android.event.calendar_changed")
        register(ContactsContract.Contacts.CONTENT_URI, "android.event.contacts_changed")
        lastCallId = latestCallId()
        lastSmsId = latestSmsId()
        register(CallLog.Calls.CONTENT_URI, "android.event.call_log_changed") { emitLatestCall() }
        register(Telephony.Sms.CONTENT_URI, "android.event.sms_database_changed") { emitLatestSms() }
    }

    private fun register(uri: Uri, typeId: String, afterChange: (() -> Unit)? = null) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, changedUri: Uri?) {
                emitter?.emit(
                    RuntimeEvent(
                        typeId = typeId,
                        payload = mapOf(
                            "uri" to ConfigValue.StringValue((changedUri ?: uri).toString()),
                            "selfChange" to ConfigValue.BooleanValue(selfChange),
                        ),
                        source = id,
                    )
                )
                afterChange?.invoke()
            }
        }
        if (runCatching { resolver.registerContentObserver(uri, true, observer); true }.getOrDefault(false)) {
            observers += observer
        }
    }

    private fun latestCallId(): Long? = runCatching {
        resolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls._ID),
            null,
            null,
            "${CallLog.Calls.DATE} DESC",
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
    }.getOrNull()

    private fun latestSmsId(): Long? = runCatching {
        resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID),
            null,
            null,
            "${Telephony.Sms.DATE} DESC",
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
    }.getOrNull()

    private fun emitLatestCall() {
        runCatching {
            resolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls._ID,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION,
                ),
                null,
                null,
                "${CallLog.Calls.DATE} DESC",
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use
                val rowId = cursor.getLong(0)
                if (lastCallId == rowId) return@use
                lastCallId = rowId
                emitter?.emit(
                    RuntimeEvent(
                        typeId = "android.event.call_log_entry",
                        payload = mapOf(
                            "id" to ConfigValue.NumberValue(rowId.toDouble()),
                            "number" to ConfigValue.StringValue(cursor.getString(1).orEmpty()),
                            "name" to ConfigValue.StringValue(cursor.getString(2).orEmpty()),
                            "type" to ConfigValue.StringValue(callTypeName(cursor.getInt(3))),
                            "dateEpochMs" to ConfigValue.NumberValue(cursor.getLong(4).toDouble()),
                            "durationSeconds" to ConfigValue.NumberValue(cursor.getLong(5).toDouble()),
                        ),
                        source = id,
                    )
                )
            }
        }
    }

    private fun emitLatestSms() {
        runCatching {
            resolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.TYPE,
                    Telephony.Sms.STATUS,
                ),
                null,
                null,
                "${Telephony.Sms.DATE} DESC",
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use
                val rowId = cursor.getLong(0)
                if (lastSmsId == rowId) return@use
                lastSmsId = rowId
                emitter?.emit(
                    RuntimeEvent(
                        typeId = "android.event.sms_database_entry",
                        payload = mapOf(
                            "id" to ConfigValue.NumberValue(rowId.toDouble()),
                            "address" to ConfigValue.StringValue(cursor.getString(1).orEmpty()),
                            "body" to ConfigValue.StringValue(cursor.getString(2).orEmpty()),
                            "dateEpochMs" to ConfigValue.NumberValue(cursor.getLong(3).toDouble()),
                            "box" to ConfigValue.StringValue(smsBoxName(cursor.getInt(4))),
                            "status" to ConfigValue.NumberValue(cursor.getInt(5).toDouble()),
                        ),
                        source = id,
                    )
                )
            }
        }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        observers.forEach { runCatching { resolver.unregisterContentObserver(it) } }
        observers.clear()
        emitter = null
        lastCallId = null
        lastSmsId = null
    }
}
