package com.yagay.yauto.platform.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.CalendarContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import java.util.concurrent.atomic.AtomicBoolean

class PersonalChangeEventSource(
    context: Context,
) : AndroidEventSource {
    override val id: String = "android.personal_changes"
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val started = AtomicBoolean(false)
    private val thread = HandlerThread("YAutoPersonalChanges")
    private var handler: Handler? = null
    private var emitter: RuntimeEventEmitter? = null
    private var calendarObserver: ContentObserver? = null
    private var smsObserver: ContentObserver? = null
    private var lastSentSmsId: Long = -1L

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        thread.start()
        handler = Handler(thread.looper)

        if (hasPermission(Manifest.permission.READ_CALENDAR)) {
            calendarObserver = observer { uri ->
                emitCalendarChanged(uri)
            }.also {
                resolver.registerContentObserver(
                    CalendarContract.Events.CONTENT_URI,
                    true,
                    it,
                )
            }
        }

        if (hasPermission(Manifest.permission.READ_SMS)) {
            lastSentSmsId = latestSentSms()?.id ?: -1L
            smsObserver = observer {
                val latest = latestSentSms() ?: return@observer
                if (latest.id == lastSentSmsId) return@observer
                lastSentSmsId = latest.id
                emitSmsSent(latest)
            }.also {
                resolver.registerContentObserver(Telephony.Sms.Sent.CONTENT_URI, true, it)
            }
        }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        calendarObserver?.let { runCatching { resolver.unregisterContentObserver(it) } }
        smsObserver?.let { runCatching { resolver.unregisterContentObserver(it) } }
        calendarObserver = null
        smsObserver = null
        emitter = null
        thread.quitSafely()
    }

    private fun observer(block: (Uri?) -> Unit): ContentObserver =
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                block(uri)
            }
        }

    private fun emitCalendarChanged(uri: Uri?) {
        val payload = buildMap<String, ConfigValue> {
            put("uri", ConfigValue.StringValue(uri?.toString().orEmpty()))
            queryCalendarEvent(uri)?.let { event ->
                put("eventId", ConfigValue.NumberValue(event.id.toDouble()))
                put("title", ConfigValue.StringValue(event.title))
                put("description", ConfigValue.StringValue(event.description))
                put("calendarId", ConfigValue.NumberValue(event.calendarId.toDouble()))
                put("startEpochMs", ConfigValue.NumberValue(event.startEpochMs.toDouble()))
                put("endEpochMs", ConfigValue.NumberValue(event.endEpochMs.toDouble()))
            }
        }
        emitter?.emit(
            RuntimeEvent(
                "android.event.calendar_changed",
                payload,
                source = id,
            )
        )
    }

    private fun queryCalendarEvent(uri: Uri?): CalendarEvent? {
        val target = uri?.takeIf { CalendarContract.Events.CONTENT_URI.isPrefixOf(it) }
            ?: CalendarContract.Events.CONTENT_URI
        return runCatching {
            resolver.query(
                target,
                arrayOf(
                    CalendarContract.Events._ID,
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.DESCRIPTION,
                    CalendarContract.Events.CALENDAR_ID,
                    CalendarContract.Events.DTSTART,
                    CalendarContract.Events.DTEND,
                ),
                null,
                null,
                CalendarContract.Events.DTSTART + " DESC",
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                CalendarEvent(
                    id = cursor.getLong(0),
                    title = cursor.getString(1).orEmpty(),
                    description = cursor.getString(2).orEmpty(),
                    calendarId = cursor.getLong(3),
                    startEpochMs = cursor.getLong(4),
                    endEpochMs = cursor.getLong(5),
                )
            }
        }.getOrNull()
    }

    private fun latestSentSms(): SentSms? = runCatching {
        resolver.query(
            Telephony.Sms.Sent.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.THREAD_ID,
            ),
            null,
            null,
            Telephony.Sms.DATE + " DESC",
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            SentSms(
                id = cursor.getLong(0),
                address = cursor.getString(1).orEmpty(),
                body = cursor.getString(2).orEmpty(),
                date = cursor.getLong(3),
                threadId = cursor.getLong(4),
            )
        }
    }.getOrNull()

    private fun emitSmsSent(sms: SentSms) {
        emitter?.emit(
            RuntimeEvent(
                "android.event.sms_sent",
                mapOf(
                    "id" to ConfigValue.NumberValue(sms.id.toDouble()),
                    "address" to ConfigValue.StringValue(sms.address),
                    "body" to ConfigValue.StringValue(sms.body),
                    "timestampEpochMs" to ConfigValue.NumberValue(sms.date.toDouble()),
                    "threadId" to ConfigValue.NumberValue(sms.threadId.toDouble()),
                ),
                source = id,
                timestampEpochMs = sms.date.takeIf { it > 0L } ?: System.currentTimeMillis(),
            )
        )
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private data class CalendarEvent(
        val id: Long,
        val title: String,
        val description: String,
        val calendarId: Long,
        val startEpochMs: Long,
        val endEpochMs: Long,
    )

    private data class SentSms(
        val id: Long,
        val address: String,
        val body: String,
        val date: Long,
        val threadId: Long,
    )
}
