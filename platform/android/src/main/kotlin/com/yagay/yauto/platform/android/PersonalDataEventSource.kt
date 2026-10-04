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

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        register(CalendarContract.Events.CONTENT_URI, "android.event.calendar_changed")
        register(ContactsContract.Contacts.CONTENT_URI, "android.event.contacts_changed")
        register(CallLog.Calls.CONTENT_URI, "android.event.call_log_changed")
        register(Telephony.Sms.CONTENT_URI, "android.event.sms_database_changed")
    }

    private fun register(uri: Uri, typeId: String) {
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
            }
        }
        if (runCatching { resolver.registerContentObserver(uri, true, observer); true }.getOrDefault(false)) {
            observers += observer
        }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        observers.forEach { runCatching { resolver.unregisterContentObserver(it) } }
        observers.clear()
        emitter = null
    }
}
