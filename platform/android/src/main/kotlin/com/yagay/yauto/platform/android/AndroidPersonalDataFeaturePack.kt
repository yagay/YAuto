package com.yagay.yauto.platform.android

import android.content.ContentValues
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import java.util.TimeZone

/** Calendar, contacts, call-log and SMS capabilities missing from the reference-app parity surface. */
class AndroidPersonalDataFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.personal_data"
    internal val context = context.applicationContext
    internal val resolver = this.context.contentResolver

    override fun install(registry: FeatureRegistry) {
        registerCalendarQuery(registry)
        registerCalendarWriteActions(registry)
        registerCalendarAttendees(registry)
        registerCalendarReminders(registry)
        registerCalendarCondition(registry)
        registerContactsQuery(registry)
        registerCallLogQuery(registry)
        registerSmsQuery(registry)
        registerSmsSend(registry)
        registerChangedEvent(registry, "android.event.calendar_changed", "Calendar changed", AccessRequirement.CALENDAR)
        registerChangedEvent(registry, "android.event.contacts_changed", "Contacts changed", AccessRequirement.CONTACTS)
        registerChangedEvent(registry, "android.event.call_log_changed", "Call log changed", AccessRequirement.CALL_LOG)
        registerChangedEvent(registry, "android.event.sms_database_changed", "SMS database changed", AccessRequirement.SMS)
        registerCallLogEntryEvents(registry)
        registerSmsEntryEvents(registry)
    }

    internal fun store(name: String, value: ConfigValue, ctx: FeatureExecutionContext) {
        name.trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, value) }
    }

    internal fun permissionMissing(group: String) =
        ActionExecutionResult(false, message = userText("feature.runtime_permission_required", group))

    internal fun invalid(message: String) =
        ActionExecutionResult(false, message = userText("feature.operation_failed", message))

    internal fun failure(error: Throwable) =
        ActionExecutionResult(false, message = userText("feature.operation_failed", error.message ?: error.javaClass.simpleName))
}

internal fun callTypeName(type: Int): String = when (type) {
    CallLog.Calls.INCOMING_TYPE -> "incoming"
    CallLog.Calls.OUTGOING_TYPE -> "outgoing"
    CallLog.Calls.MISSED_TYPE -> "missed"
    CallLog.Calls.REJECTED_TYPE -> "rejected"
    CallLog.Calls.BLOCKED_TYPE -> "blocked"
    CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
    else -> "other"
}

internal fun smsBoxName(type: Int): String = when (type) {
    Telephony.Sms.MESSAGE_TYPE_INBOX -> "inbox"
    Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
    Telephony.Sms.MESSAGE_TYPE_DRAFT -> "draft"
    Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "outbox"
    Telephony.Sms.MESSAGE_TYPE_FAILED -> "failed"
    Telephony.Sms.MESSAGE_TYPE_QUEUED -> "queued"
    else -> "other"
}
