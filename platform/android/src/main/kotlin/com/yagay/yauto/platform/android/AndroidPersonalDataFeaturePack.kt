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
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver

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

    private fun registerCalendarQuery(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.calendar.events.query"), FeatureKind.ACTION,
                "Query calendar events", "Query calendar events in a time window and store structured results",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Number("fromOffsetMinutes", "Start offset minutes", min = -5_256_000.0, max = 5_256_000.0),
                    FieldSchema.Number("toOffsetMinutes", "End offset minutes", min = -5_256_000.0, max = 5_256_000.0),
                    FieldSchema.Text("titleContains", "Title contains"),
                    FieldSchema.Number("limit", "Maximum results", min = 1.0, max = 500.0),
                    FieldSchema.Variable("resultVariable", "Store event list", true),
                ),
                accessRequirements = setOf(AccessRequirement.CALENDAR),
                keywords = setOf("calendar", "events", "schedule", "appointments", "query"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (!runtimePermissionGranted(context, "calendar", feature.typeId)) return@registerAction permissionMissing("calendar")
            val now = System.currentTimeMillis()
            val from = now + ((feature.config["fromOffsetMinutes"].numberOrNull() ?: -1440.0) * 60_000.0).toLong()
            val to = now + ((feature.config["toOffsetMinutes"].numberOrNull() ?: 43_200.0) * 60_000.0).toLong()
            if (to < from) return@registerAction invalid("Calendar time window is invalid")
            val titleFilter = feature.config.string("titleContains").resolveVariables(ctx.variables)
            val limit = (feature.config["limit"].numberOrNull() ?: 100.0).toInt().coerceIn(1, 500)
            val rows = runCatching {
                val output = ArrayList<ConfigValue>()
                resolver.query(
                    CalendarContract.Events.CONTENT_URI,
                    arrayOf(
                        CalendarContract.Events._ID,
                        CalendarContract.Events.TITLE,
                        CalendarContract.Events.DESCRIPTION,
                        CalendarContract.Events.EVENT_LOCATION,
                        CalendarContract.Events.DTSTART,
                        CalendarContract.Events.DTEND,
                        CalendarContract.Events.ALL_DAY,
                        CalendarContract.Events.CALENDAR_DISPLAY_NAME,
                    ),
                    "${CalendarContract.Events.DTSTART} BETWEEN ? AND ?",
                    arrayOf(from.toString(), to.toString()),
                    "${CalendarContract.Events.DTSTART} ASC",
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events._ID)
                    val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
                    val descriptionIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.DESCRIPTION)
                    val locationIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.EVENT_LOCATION)
                    val startIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
                    val endIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTEND)
                    val allDayIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)
                    val calendarIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.CALENDAR_DISPLAY_NAME)
                    while (cursor.moveToNext() && output.size < limit) {
                        val title = cursor.getString(titleIndex).orEmpty()
                        if (titleFilter.isNotBlank() && !title.contains(titleFilter, ignoreCase = true)) continue
                        output += ConfigValue.ObjectValue(
                            mapOf(
                                "id" to ConfigValue.NumberValue(cursor.getLong(idIndex).toDouble()),
                                "title" to ConfigValue.StringValue(title),
                                "description" to ConfigValue.StringValue(cursor.getString(descriptionIndex).orEmpty()),
                                "location" to ConfigValue.StringValue(cursor.getString(locationIndex).orEmpty()),
                                "startEpochMs" to ConfigValue.NumberValue(cursor.getLong(startIndex).toDouble()),
                                "endEpochMs" to ConfigValue.NumberValue(cursor.getLong(endIndex).toDouble()),
                                "allDay" to ConfigValue.BooleanValue(cursor.getInt(allDayIndex) != 0),
                                "calendar" to ConfigValue.StringValue(cursor.getString(calendarIndex).orEmpty()),
                            )
                        )
                    }
                }
                ConfigValue.ListValue(output)
            }.getOrElse { return@registerAction failure(it) }
            store(feature.config.string("resultVariable"), rows, ctx)
            ActionExecutionResult(true, rows)
        }
    }

    private fun registerCalendarWriteActions(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.calendar.event.insert"),
                FeatureKind.ACTION,
                "Insert calendar event",
                "Insert an event directly into Android CalendarProvider",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Number("calendarId", "Calendar ID", true, min = 0.0),
                    FieldSchema.Text("title", "Title", true),
                    FieldSchema.Text("description", "Description", multiline = true),
                    FieldSchema.Text("location", "Location"),
                    FieldSchema.Number("startEpochMs", "Start epoch ms", true, min = 0.0),
                    FieldSchema.Number("endEpochMs", "End epoch ms", true, min = 0.0),
                    FieldSchema.Toggle("allDay", "All-day event"),
                    FieldSchema.Text("timeZone", "Time zone ID"),
                    FieldSchema.Variable("resultVariable", "Store event ID"),
                ),
                fieldBehaviors = mapOf(
                    "title" to FieldBehavior(supportsVariables = true),
                    "description" to FieldBehavior(supportsVariables = true),
                    "location" to FieldBehavior(supportsVariables = true),
                ),
                accessRequirements = setOf(AccessRequirement.CALENDAR),
                keywords = setOf("calendar", "add event", "create event", "macrodroid", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (!runtimePermissionGranted(context, "calendar", feature.typeId)) return@registerAction permissionMissing("calendar")
            val calendarId = feature.config["calendarId"].numberOrNull()?.toLong() ?: return@registerAction invalid("Calendar ID is required")
            val start = feature.config["startEpochMs"].numberOrNull()?.toLong() ?: return@registerAction invalid("Start time is required")
            val end = feature.config["endEpochMs"].numberOrNull()?.toLong() ?: return@registerAction invalid("End time is required")
            if (end < start) return@registerAction invalid("Calendar end time is before start time")
            val values = calendarEventValues(feature, ctx, calendarId, start, end)
            val eventId = runCatching {
                resolver.insert(CalendarContract.Events.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull()
                    ?: error("CalendarProvider did not return an event ID")
            }.getOrElse { return@registerAction failure(it) }
            val output = ConfigValue.NumberValue(eventId.toDouble())
            store(feature.config.string("resultVariable"), output, ctx)
            ActionExecutionResult(true, output)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.calendar.event.update"),
                FeatureKind.ACTION,
                "Update calendar event",
                "Replace editable fields of an existing Android calendar event",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Number("eventId", "Event ID", true, min = 0.0),
                    FieldSchema.Number("calendarId", "Calendar ID", true, min = 0.0),
                    FieldSchema.Text("title", "Title", true),
                    FieldSchema.Text("description", "Description", multiline = true),
                    FieldSchema.Text("location", "Location"),
                    FieldSchema.Number("startEpochMs", "Start epoch ms", true, min = 0.0),
                    FieldSchema.Number("endEpochMs", "End epoch ms", true, min = 0.0),
                    FieldSchema.Toggle("allDay", "All-day event"),
                    FieldSchema.Text("timeZone", "Time zone ID"),
                ),
                fieldBehaviors = mapOf(
                    "title" to FieldBehavior(supportsVariables = true),
                    "description" to FieldBehavior(supportsVariables = true),
                    "location" to FieldBehavior(supportsVariables = true),
                ),
                accessRequirements = setOf(AccessRequirement.CALENDAR),
                keywords = setOf("calendar", "edit event", "update event", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (!runtimePermissionGranted(context, "calendar", feature.typeId)) return@registerAction permissionMissing("calendar")
            val eventId = feature.config["eventId"].numberOrNull()?.toLong() ?: return@registerAction invalid("Event ID is required")
            val calendarId = feature.config["calendarId"].numberOrNull()?.toLong() ?: return@registerAction invalid("Calendar ID is required")
            val start = feature.config["startEpochMs"].numberOrNull()?.toLong() ?: return@registerAction invalid("Start time is required")
            val end = feature.config["endEpochMs"].numberOrNull()?.toLong() ?: return@registerAction invalid("End time is required")
            if (end < start) return@registerAction invalid("Calendar end time is before start time")
            val changed = runCatching {
                resolver.update(
                    CalendarContract.Events.CONTENT_URI,
                    calendarEventValues(feature, ctx, calendarId, start, end),
                    CalendarContract.Events._ID + "=?",
                    arrayOf(eventId.toString()),
                )
            }.getOrElse { return@registerAction failure(it) }
            ActionExecutionResult(changed > 0, ConfigValue.NumberValue(changed.toDouble()))
        }
    }

    private fun calendarEventValues(
        feature: com.yagay.yauto.core.model.FeatureRef,
        ctx: FeatureExecutionContext,
        calendarId: Long,
        start: Long,
        end: Long,
    ): ContentValues = ContentValues().apply {
        put(CalendarContract.Events.CALENDAR_ID, calendarId)
        put(CalendarContract.Events.TITLE, feature.config.string("title").resolveVariables(ctx.variables))
        put(CalendarContract.Events.DESCRIPTION, feature.config.string("description").resolveVariables(ctx.variables))
        put(CalendarContract.Events.EVENT_LOCATION, feature.config.string("location").resolveVariables(ctx.variables))
        put(CalendarContract.Events.DTSTART, start)
        put(CalendarContract.Events.DTEND, end)
        put(CalendarContract.Events.ALL_DAY, if (feature.config.boolean("allDay")) 1 else 0)
        put(
            CalendarContract.Events.EVENT_TIMEZONE,
            feature.config.string("timeZone").trim().ifBlank { TimeZone.getDefault().id },
        )
    }

    private fun registerCalendarAttendees(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.calendar.attendees.query"),
                FeatureKind.ACTION,
                "Query calendar attendees",
                "Read attendees for an Android calendar event",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Number("eventId", "Event ID", true, min = 0.0),
                    FieldSchema.Variable("resultVariable", "Store attendee list", true),
                ),
                accessRequirements = setOf(AccessRequirement.CALENDAR),
                keywords = setOf("calendar", "attendees", "participants", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (!runtimePermissionGranted(context, "calendar", feature.typeId)) return@registerAction permissionMissing("calendar")
            val eventId = feature.config["eventId"].numberOrNull()?.toLong() ?: return@registerAction invalid("Event ID is required")
            val output = runCatching {
                val rows = ArrayList<ConfigValue>()
                resolver.query(
                    CalendarContract.Attendees.CONTENT_URI,
                    arrayOf(
                        CalendarContract.Attendees._ID,
                        CalendarContract.Attendees.ATTENDEE_NAME,
                        CalendarContract.Attendees.ATTENDEE_EMAIL,
                        CalendarContract.Attendees.ATTENDEE_RELATIONSHIP,
                        CalendarContract.Attendees.ATTENDEE_TYPE,
                        CalendarContract.Attendees.ATTENDEE_STATUS,
                    ),
                    CalendarContract.Attendees.EVENT_ID + "=?",
                    arrayOf(eventId.toString()),
                    CalendarContract.Attendees._ID + " ASC",
                )?.use { cursor ->
                    val idI = cursor.getColumnIndexOrThrow(CalendarContract.Attendees._ID)
                    val nameI = cursor.getColumnIndexOrThrow(CalendarContract.Attendees.ATTENDEE_NAME)
                    val emailI = cursor.getColumnIndexOrThrow(CalendarContract.Attendees.ATTENDEE_EMAIL)
                    val relationshipI = cursor.getColumnIndexOrThrow(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP)
                    val typeI = cursor.getColumnIndexOrThrow(CalendarContract.Attendees.ATTENDEE_TYPE)
                    val statusI = cursor.getColumnIndexOrThrow(CalendarContract.Attendees.ATTENDEE_STATUS)
                    while (cursor.moveToNext()) {
                        rows += ConfigValue.ObjectValue(
                            mapOf(
                                "id" to ConfigValue.NumberValue(cursor.getLong(idI).toDouble()),
                                "name" to ConfigValue.StringValue(cursor.getString(nameI).orEmpty()),
                                "email" to ConfigValue.StringValue(cursor.getString(emailI).orEmpty()),
                                "relationshipCode" to ConfigValue.NumberValue(cursor.getInt(relationshipI).toDouble()),
                                "typeCode" to ConfigValue.NumberValue(cursor.getInt(typeI).toDouble()),
                                "statusCode" to ConfigValue.NumberValue(cursor.getInt(statusI).toDouble()),
                            )
                        )
                    }
                }
                ConfigValue.ListValue(rows)
            }.getOrElse { return@registerAction failure(it) }
            store(feature.config.string("resultVariable"), output, ctx)
            ActionExecutionResult(true, output)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.calendar.attendee.set"),
                FeatureKind.ACTION,
                "Add or update calendar attendee",
                "Insert a calendar attendee or update an existing attendee row by ID",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Number("eventId", "Event ID", true, min = 0.0),
                    FieldSchema.Number("attendeeId", "Existing attendee ID (0 = add)", min = 0.0),
                    FieldSchema.Text("name", "Name"),
                    FieldSchema.Text("email", "Email", true),
                    FieldSchema.Number("relationshipCode", "Relationship code", min = 0.0),
                    FieldSchema.Number("typeCode", "Type code", min = 0.0),
                    FieldSchema.Number("statusCode", "Status code", min = 0.0),
                    FieldSchema.Variable("resultVariable", "Store attendee ID"),
                ),
                fieldBehaviors = mapOf(
                    "name" to FieldBehavior(supportsVariables = true),
                    "email" to FieldBehavior(supportsVariables = true),
                    "relationshipCode" to FieldBehavior(
                        picker = FieldPickerSource.Options(
                            listOf(
                                FieldPickerOption(CalendarContract.Attendees.RELATIONSHIP_NONE.toString(), "None"),
                                FieldPickerOption(CalendarContract.Attendees.RELATIONSHIP_ATTENDEE.toString(), "Attendee"),
                                FieldPickerOption(CalendarContract.Attendees.RELATIONSHIP_ORGANIZER.toString(), "Organizer"),
                                FieldPickerOption(CalendarContract.Attendees.RELATIONSHIP_PERFORMER.toString(), "Performer"),
                                FieldPickerOption(CalendarContract.Attendees.RELATIONSHIP_SPEAKER.toString(), "Speaker"),
                            )
                        )
                    ),
                    "typeCode" to FieldBehavior(
                        picker = FieldPickerSource.Options(
                            listOf(
                                FieldPickerOption(CalendarContract.Attendees.TYPE_NONE.toString(), "None"),
                                FieldPickerOption(CalendarContract.Attendees.TYPE_REQUIRED.toString(), "Required"),
                                FieldPickerOption(CalendarContract.Attendees.TYPE_OPTIONAL.toString(), "Optional"),
                                FieldPickerOption(CalendarContract.Attendees.TYPE_RESOURCE.toString(), "Resource"),
                            )
                        )
                    ),
                    "statusCode" to FieldBehavior(
                        picker = FieldPickerSource.Options(
                            listOf(
                                FieldPickerOption(CalendarContract.Attendees.ATTENDEE_STATUS_NONE.toString(), "None"),
                                FieldPickerOption(CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED.toString(), "Accepted"),
                                FieldPickerOption(CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED.toString(), "Declined"),
                                FieldPickerOption(CalendarContract.Attendees.ATTENDEE_STATUS_INVITED.toString(), "Invited"),
                                FieldPickerOption(CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE.toString(), "Tentative"),
                            )
                        )
                    ),
                ),
                accessRequirements = setOf(AccessRequirement.CALENDAR),
                keywords = setOf("calendar", "attendee", "participant", "edit", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (!runtimePermissionGranted(context, "calendar", feature.typeId)) return@registerAction permissionMissing("calendar")
            val eventId = feature.config["eventId"].numberOrNull()?.toLong() ?: return@registerAction invalid("Event ID is required")
            val attendeeId = feature.config["attendeeId"].numberOrNull()?.toLong() ?: 0L
            val values = ContentValues().apply {
                put(CalendarContract.Attendees.EVENT_ID, eventId)
                put(CalendarContract.Attendees.ATTENDEE_NAME, feature.config.string("name").resolveVariables(ctx.variables))
                put(CalendarContract.Attendees.ATTENDEE_EMAIL, feature.config.string("email").resolveVariables(ctx.variables))
                put(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, (feature.config["relationshipCode"].numberOrNull() ?: CalendarContract.Attendees.RELATIONSHIP_ATTENDEE.toDouble()).toInt())
                put(CalendarContract.Attendees.ATTENDEE_TYPE, (feature.config["typeCode"].numberOrNull() ?: CalendarContract.Attendees.TYPE_REQUIRED.toDouble()).toInt())
                put(CalendarContract.Attendees.ATTENDEE_STATUS, (feature.config["statusCode"].numberOrNull() ?: CalendarContract.Attendees.ATTENDEE_STATUS_NONE.toDouble()).toInt())
            }
            val savedId = runCatching {
                if (attendeeId > 0) {
                    val count = resolver.update(
                        CalendarContract.Attendees.CONTENT_URI,
                        values,
                        CalendarContract.Attendees._ID + "=?",
                        arrayOf(attendeeId.toString()),
                    )
                    if (count <= 0) error("Attendee not found")
                    attendeeId
                } else {
                    resolver.insert(CalendarContract.Attendees.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull()
                        ?: error("CalendarProvider did not return attendee ID")
                }
            }.getOrElse { return@registerAction failure(it) }
            val output = ConfigValue.NumberValue(savedId.toDouble())
            store(feature.config.string("resultVariable"), output, ctx)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerCalendarReminders(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.calendar.reminders.query"),
                FeatureKind.ACTION,
                "Query calendar reminders",
                "Read reminders for an Android calendar event",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Number("eventId", "Event ID", true, min = 0.0),
                    FieldSchema.Variable("resultVariable", "Store reminder list", true),
                ),
                accessRequirements = setOf(AccessRequirement.CALENDAR),
                keywords = setOf("calendar", "reminders", "minutes", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (!runtimePermissionGranted(context, "calendar", feature.typeId)) return@registerAction permissionMissing("calendar")
            val eventId = feature.config["eventId"].numberOrNull()?.toLong() ?: return@registerAction invalid("Event ID is required")
            val output = runCatching {
                val rows = ArrayList<ConfigValue>()
                resolver.query(
                    CalendarContract.Reminders.CONTENT_URI,
                    arrayOf(CalendarContract.Reminders._ID, CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD),
                    CalendarContract.Reminders.EVENT_ID + "=?",
                    arrayOf(eventId.toString()),
                    CalendarContract.Reminders.MINUTES + " ASC",
                )?.use { cursor ->
                    val idI = cursor.getColumnIndexOrThrow(CalendarContract.Reminders._ID)
                    val minutesI = cursor.getColumnIndexOrThrow(CalendarContract.Reminders.MINUTES)
                    val methodI = cursor.getColumnIndexOrThrow(CalendarContract.Reminders.METHOD)
                    while (cursor.moveToNext()) {
                        rows += ConfigValue.ObjectValue(
                            mapOf(
                                "id" to ConfigValue.NumberValue(cursor.getLong(idI).toDouble()),
                                "minutesPrior" to ConfigValue.NumberValue(cursor.getInt(minutesI).toDouble()),
                                "methodCode" to ConfigValue.NumberValue(cursor.getInt(methodI).toDouble()),
                            )
                        )
                    }
                }
                ConfigValue.ListValue(rows)
            }.getOrElse { return@registerAction failure(it) }
            store(feature.config.string("resultVariable"), output, ctx)
            ActionExecutionResult(true, output)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.calendar.reminder.set"),
                FeatureKind.ACTION,
                "Add or update calendar reminder",
                "Insert a calendar reminder or update an existing reminder row by ID",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Number("eventId", "Event ID", true, min = 0.0),
                    FieldSchema.Number("reminderId", "Existing reminder ID (0 = add)", min = 0.0),
                    FieldSchema.Number("minutesPrior", "Minutes before event", true, min = 0.0, max = 525600.0),
                    FieldSchema.Number("methodCode", "Reminder method code", min = 0.0),
                    FieldSchema.Variable("resultVariable", "Store reminder ID"),
                ),
                fieldBehaviors = mapOf(
                    "methodCode" to FieldBehavior(
                        picker = FieldPickerSource.Options(
                            listOf(
                                FieldPickerOption(CalendarContract.Reminders.METHOD_DEFAULT.toString(), "Default"),
                                FieldPickerOption(CalendarContract.Reminders.METHOD_ALERT.toString(), "Alert"),
                                FieldPickerOption(CalendarContract.Reminders.METHOD_EMAIL.toString(), "Email"),
                                FieldPickerOption(CalendarContract.Reminders.METHOD_SMS.toString(), "SMS"),
                                FieldPickerOption(CalendarContract.Reminders.METHOD_ALARM.toString(), "Alarm"),
                            )
                        )
                    )
                ),
                accessRequirements = setOf(AccessRequirement.CALENDAR),
                keywords = setOf("calendar", "reminder", "alarm", "edit", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (!runtimePermissionGranted(context, "calendar", feature.typeId)) return@registerAction permissionMissing("calendar")
            val eventId = feature.config["eventId"].numberOrNull()?.toLong() ?: return@registerAction invalid("Event ID is required")
            val reminderId = feature.config["reminderId"].numberOrNull()?.toLong() ?: 0L
            val minutes = feature.config["minutesPrior"].numberOrNull()?.toInt()?.coerceAtLeast(0)
                ?: return@registerAction invalid("Reminder minutes are required")
            val method = (feature.config["methodCode"].numberOrNull() ?: CalendarContract.Reminders.METHOD_ALERT.toDouble()).toInt()
            val values = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, minutes)
                put(CalendarContract.Reminders.METHOD, method)
            }
            val savedId = runCatching {
                if (reminderId > 0) {
                    val count = resolver.update(
                        CalendarContract.Reminders.CONTENT_URI,
                        values,
                        CalendarContract.Reminders._ID + "=?",
                        arrayOf(reminderId.toString()),
                    )
                    if (count <= 0) error("Reminder not found")
                    reminderId
                } else {
                    resolver.insert(CalendarContract.Reminders.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull()
                        ?: error("CalendarProvider did not return reminder ID")
                }
            }.getOrElse { return@registerAction failure(it) }
            val output = ConfigValue.NumberValue(savedId.toDouble())
            store(feature.config.string("resultVariable"), output, ctx)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerCalendarCondition(registry: FeatureRegistry) {
        val fields = listOf(
            FieldSchema.Text("titleContains", "Title contains"),
            FieldSchema.Number("withinMinutes", "Starts within minutes", min = 0.0, max = 525600.0),
            FieldSchema.Toggle("value", "Matching event exists"),
        )
        val evaluator = ConditionEvaluator { feature, ctx ->
            if (!runtimePermissionGranted(context, "calendar", feature.typeId)) return@ConditionEvaluator false
            val now = System.currentTimeMillis()
            val within = ((feature.config["withinMinutes"].numberOrNull() ?: 0.0) * 60_000.0).toLong()
            val end = now + within
            val title = feature.config.string("titleContains").resolveVariables(ctx.variables)
            val exists = runCatching {
                var found = false
                resolver.query(
                    CalendarContract.Events.CONTENT_URI,
                    arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND),
                    "\${CalendarContract.Events.DTSTART} <= ? AND (\${CalendarContract.Events.DTEND} >= ? OR \${CalendarContract.Events.DTEND} IS NULL)",
                    arrayOf(end.toString(), now.toString()),
                    "\${CalendarContract.Events.DTSTART} ASC",
                )?.use { cursor ->
                    val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
                    while (cursor.moveToNext()) {
                        val currentTitle = cursor.getString(titleIndex).orEmpty()
                        if (title.isBlank() || currentTitle.contains(title, ignoreCase = true)) {
                            found = true
                            break
                        }
                    }
                }
                found
            }.getOrDefault(false)
            exists == feature.config.boolean("value", true)
        }
        val state = FeatureDescriptor(
            FeatureId("android.state.calendar_event"), FeatureKind.STATE,
            "Calendar event match", "Check whether a matching calendar event is active or begins within a selected window",
            FeatureCategory.APP, fields = fields,
            accessRequirements = setOf(AccessRequirement.CALENDAR),
            keywords = setOf("calendar", "event", "constraint", "active", "upcoming"),
            ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(state.copy(id = FeatureId("android.condition.calendar_event"), kind = FeatureKind.CONDITION), evaluator)
    }

    private fun registerContactsQuery(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.contacts.query"), FeatureKind.ACTION,
                "Query contacts", "Search Android contacts and optionally include phone numbers",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Text("query", "Name contains"),
                    FieldSchema.Toggle("includePhones", "Include phone numbers"),
                    FieldSchema.Number("limit", "Maximum results", min = 1.0, max = 500.0),
                    FieldSchema.Variable("resultVariable", "Store contact list", true),
                ),
                accessRequirements = setOf(AccessRequirement.CONTACTS),
                keywords = setOf("contacts", "people", "phonebook", "query", "search"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (!runtimePermissionGranted(context, "contacts")) return@registerAction permissionMissing("contacts")
            val query = feature.config.string("query").resolveVariables(ctx.variables)
            val includePhones = feature.config.boolean("includePhones")
            val limit = (feature.config["limit"].numberOrNull() ?: 100.0).toInt().coerceIn(1, 500)
            val rows = runCatching {
                val output = ArrayList<ConfigValue>()
                resolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(
                        ContactsContract.Contacts._ID,
                        ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                        ContactsContract.Contacts.LOOKUP_KEY,
                        ContactsContract.Contacts.HAS_PHONE_NUMBER,
                    ),
                    null, null, "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC"
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                    val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                    val lookupIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)
                    val phoneIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)
                    while (cursor.moveToNext() && output.size < limit) {
                        val name = cursor.getString(nameIndex).orEmpty()
                        if (query.isNotBlank() && !name.contains(query, ignoreCase = true)) continue
                        val contactId = cursor.getLong(idIndex)
                        val phones = if (includePhones && cursor.getInt(phoneIndex) != 0) contactPhones(contactId) else emptyList()
                        output += ConfigValue.ObjectValue(
                            mapOf(
                                "id" to ConfigValue.NumberValue(contactId.toDouble()),
                                "name" to ConfigValue.StringValue(name),
                                "lookupKey" to ConfigValue.StringValue(cursor.getString(lookupIndex).orEmpty()),
                                "phones" to ConfigValue.ListValue(phones.map(ConfigValue::StringValue)),
                            )
                        )
                    }
                }
                ConfigValue.ListValue(output)
            }.getOrElse { return@registerAction failure(it) }
            store(feature.config.string("resultVariable"), rows, ctx)
            ActionExecutionResult(true, rows)
        }
    }

    private fun contactPhones(contactId: Long): List<String> {
        val result = ArrayList<String>()
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID}=?",
            arrayOf(contactId.toString()),
            null,
        )?.use { cursor ->
            val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) cursor.getString(numberIndex)?.takeIf(String::isNotBlank)?.let(result::add)
        }
        return result.distinct()
    }

    private fun registerCallLogQuery(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.call_log.query"), FeatureKind.ACTION,
                "Query call log", "Read recent Android call-history rows with optional number and call-type filters",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Text("numberContains", "Number contains"),
                    FieldSchema.Choice("type", "Call type", true, listOf("any", "incoming", "outgoing", "missed", "rejected", "blocked", "voicemail")),
                    FieldSchema.Number("limit", "Maximum results", min = 1.0, max = 500.0),
                    FieldSchema.Variable("resultVariable", "Store call list", true),
                ),
                accessRequirements = setOf(AccessRequirement.CALL_LOG),
                keywords = setOf("call log", "call history", "incoming", "outgoing", "missed"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (!runtimePermissionGranted(context, "call_log")) return@registerAction permissionMissing("call_log")
            val needle = feature.config.string("numberContains").resolveVariables(ctx.variables)
            val wantedType = feature.config.string("type", "any")
            val limit = (feature.config["limit"].numberOrNull() ?: 100.0).toInt().coerceIn(1, 500)
            val rows = runCatching {
                val output = ArrayList<ConfigValue>()
                resolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                    null, null, "${CallLog.Calls.DATE} DESC",
                )?.use { cursor ->
                    val idI = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
                    val numberI = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                    val nameI = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                    val typeI = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                    val dateI = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                    val durationI = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                    while (cursor.moveToNext() && output.size < limit) {
                        val number = cursor.getString(numberI).orEmpty()
                        val type = callTypeName(cursor.getInt(typeI))
                        if (needle.isNotBlank() && !number.contains(needle, ignoreCase = true)) continue
                        if (wantedType != "any" && wantedType != type) continue
                        output += ConfigValue.ObjectValue(
                            mapOf(
                                "id" to ConfigValue.NumberValue(cursor.getLong(idI).toDouble()),
                                "number" to ConfigValue.StringValue(number),
                                "name" to ConfigValue.StringValue(cursor.getString(nameI).orEmpty()),
                                "type" to ConfigValue.StringValue(type),
                                "dateEpochMs" to ConfigValue.NumberValue(cursor.getLong(dateI).toDouble()),
                                "durationSeconds" to ConfigValue.NumberValue(cursor.getLong(durationI).toDouble()),
                            )
                        )
                    }
                }
                ConfigValue.ListValue(output)
            }.getOrElse { return@registerAction failure(it) }
            store(feature.config.string("resultVariable"), rows, ctx)
            ActionExecutionResult(true, rows)
        }
    }

    private fun registerSmsQuery(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.sms.query"), FeatureKind.ACTION,
                "Query SMS messages", "Read SMS database rows with box, address and text filters",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Choice("box", "Message box", true, listOf("any", "inbox", "sent", "draft", "outbox", "failed", "queued")),
                    FieldSchema.Text("addressContains", "Address contains"),
                    FieldSchema.Text("textContains", "Message text contains"),
                    FieldSchema.Number("limit", "Maximum results", min = 1.0, max = 500.0),
                    FieldSchema.Variable("resultVariable", "Store message list", true),
                ),
                accessRequirements = setOf(AccessRequirement.SMS),
                keywords = setOf("sms", "messages", "inbox", "sent", "query"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (!runtimePermissionGranted(context, "sms", feature.typeId)) return@registerAction permissionMissing("sms")
            val wantedBox = feature.config.string("box", "any")
            val addressNeedle = feature.config.string("addressContains").resolveVariables(ctx.variables)
            val textNeedle = feature.config.string("textContains").resolveVariables(ctx.variables)
            val limit = (feature.config["limit"].numberOrNull() ?: 100.0).toInt().coerceIn(1, 500)
            val rows = runCatching {
                val output = ArrayList<ConfigValue>()
                resolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ, Telephony.Sms.SEEN, Telephony.Sms.STATUS),
                    null, null, "${Telephony.Sms.DATE} DESC",
                )?.use { cursor ->
                    val idI = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                    val addressI = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                    val bodyI = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                    val dateI = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                    val typeI = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                    val readI = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
                    val seenI = cursor.getColumnIndexOrThrow(Telephony.Sms.SEEN)
                    val statusI = cursor.getColumnIndexOrThrow(Telephony.Sms.STATUS)
                    while (cursor.moveToNext() && output.size < limit) {
                        val address = cursor.getString(addressI).orEmpty()
                        val body = cursor.getString(bodyI).orEmpty()
                        val box = smsBoxName(cursor.getInt(typeI))
                        if (wantedBox != "any" && wantedBox != box) continue
                        if (addressNeedle.isNotBlank() && !address.contains(addressNeedle, ignoreCase = true)) continue
                        if (textNeedle.isNotBlank() && !body.contains(textNeedle, ignoreCase = true)) continue
                        output += ConfigValue.ObjectValue(
                            mapOf(
                                "id" to ConfigValue.NumberValue(cursor.getLong(idI).toDouble()),
                                "address" to ConfigValue.StringValue(address),
                                "body" to ConfigValue.StringValue(body),
                                "dateEpochMs" to ConfigValue.NumberValue(cursor.getLong(dateI).toDouble()),
                                "box" to ConfigValue.StringValue(box),
                                "read" to ConfigValue.BooleanValue(cursor.getInt(readI) != 0),
                                "seen" to ConfigValue.BooleanValue(cursor.getInt(seenI) != 0),
                                "status" to ConfigValue.NumberValue(cursor.getInt(statusI).toDouble()),
                            )
                        )
                    }
                }
                ConfigValue.ListValue(output)
            }.getOrElse { return@registerAction failure(it) }
            store(feature.config.string("resultVariable"), rows, ctx)
            ActionExecutionResult(true, rows)
        }
    }

    private fun registerSmsSend(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.sms.send"), FeatureKind.ACTION,
                "Send SMS directly", "Send an SMS without opening the compose UI",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Text("number", "Phone number", true),
                    FieldSchema.Text("text", "Message", true, multiline = true),
                    FieldSchema.Number("subscriptionId", "Subscription ID", min = 0.0, max = Int.MAX_VALUE.toDouble()),
                ),
                fieldBehaviors = mapOf(
                    "number" to FieldBehavior(supportsVariables = true),
                    "text" to FieldBehavior(supportsVariables = true),
                ),
                accessRequirements = setOf(AccessRequirement.SMS),
                keywords = setOf("sms", "send", "message", "direct", "sim"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (!runtimePermissionGranted(context, "sms", feature.typeId)) return@registerAction permissionMissing("sms")
            val number = feature.config.string("number").resolveVariables(ctx.variables).trim()
            val text = feature.config.string("text").resolveVariables(ctx.variables)
            if (number.isBlank() || number.length > 40 || number.any { Character.isISOControl(it) } ||
                text.isBlank() || text.length > 1600
            ) return@registerAction invalid("Invalid SMS number or message")
            if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                return@registerAction permissionMissing("sms")
            }
            runCatching {
                @Suppress("DEPRECATION")
                val manager = feature.config["subscriptionId"].numberOrNull()?.toInt()?.let(SmsManager::getSmsManagerForSubscriptionId)
                    ?: SmsManager.getDefault()
                val parts = manager.divideMessage(text)
                if (parts.isEmpty() || parts.size > 24) return@runCatching invalid("SMS is too long")
                if (parts.size == 1) manager.sendTextMessage(number, null, text, null, null)
                else manager.sendMultipartTextMessage(number, null, parts, null, null)
                // Queued with Android telephony: this does not confirm carrier delivery.
                ActionExecutionResult(true)
            }.getOrElse { failure(it) }
        }
    }

    private fun registerCallLogEntryEvents(registry: FeatureRegistry) {
        fun descriptor(typeId: String, title: String, fixedType: String? = null) = FeatureDescriptor(
            FeatureId(typeId), FeatureKind.EVENT, title,
            "Run when a new Android call-log row matches call type and number filters",
            FeatureCategory.APP,
            fields = listOf(
                FieldSchema.Choice("type", "Call type", options = listOf("any", "incoming", "outgoing", "missed", "rejected", "blocked", "voicemail")),
                FieldSchema.Text("numberContains", "Number contains"),
            ),
            accessRequirements = setOf(AccessRequirement.CALL_LOG),
            keywords = setOf("call log", "outgoing call", "missed call", "phone"),
            ownerPackId = id,
        ) to fixedType

        listOf(
            descriptor("android.event.call_log_entry", "Call log entry added"),
            descriptor("android.event.outgoing_call", "Outgoing call", "outgoing"),
            descriptor("android.event.missed_call", "Missed call", "missed"),
        ).forEach { (definition, fixedType) ->
            registry.registerEvent(definition) { feature, ctx ->
                if (ctx.event.typeId != "android.event.call_log_entry") return@registerEvent false
                val wantedType = fixedType ?: feature.config.string("type", "any")
                val number = feature.config.string("numberContains")
                (wantedType == "any" || ctx.event.payload.string("type") == wantedType) &&
                    (number.isBlank() || ctx.event.payload.string("number").contains(number, ignoreCase = true))
            }
        }
    }

    private fun registerSmsEntryEvents(registry: FeatureRegistry) {
        fun descriptor(typeId: String, title: String, fixedBox: String? = null) = FeatureDescriptor(
            FeatureId(typeId), FeatureKind.EVENT, title,
            "Run when a new SMS database row matches message-box, address and text filters",
            FeatureCategory.APP,
            fields = listOf(
                FieldSchema.Choice("box", "Message box", options = listOf("any", "inbox", "sent", "draft", "outbox", "failed", "queued")),
                FieldSchema.Text("addressContains", "Address contains"),
                FieldSchema.Text("bodyContains", "Message contains"),
            ),
            accessRequirements = setOf(AccessRequirement.SMS),
            keywords = setOf("sms sent", "message", "sent", "database"),
            ownerPackId = id,
        ) to fixedBox

        listOf(
            descriptor("android.event.sms_database_entry", "SMS database entry added"),
            descriptor("android.event.sms_sent", "SMS sent", "sent"),
        ).forEach { (definition, fixedBox) ->
            registry.registerEvent(definition) { feature, ctx ->
                if (ctx.event.typeId != "android.event.sms_database_entry") return@registerEvent false
                val wantedBox = fixedBox ?: feature.config.string("box", "any")
                val address = feature.config.string("addressContains")
                val body = feature.config.string("bodyContains")
                (wantedBox == "any" || ctx.event.payload.string("box") == wantedBox) &&
                    (address.isBlank() || ctx.event.payload.string("address").contains(address, ignoreCase = true)) &&
                    (body.isBlank() || ctx.event.payload.string("body").contains(body, ignoreCase = true))
            }
        }
    }

    private fun registerChangedEvent(registry: FeatureRegistry, typeId: String, title: String, access: AccessRequirement) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId(typeId), FeatureKind.EVENT, title,
                "Run when the corresponding Android content provider reports a change",
                FeatureCategory.APP,
                fields = listOf(FieldSchema.Text("uriContains", "Changed URI contains")),
                accessRequirements = setOf(access),
                keywords = setOf("content", "changed", "database", "observer"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != typeId) return@registerEvent false
            val needle = feature.config.string("uriContains")
            needle.isBlank() || ctx.event.payload.string("uri").contains(needle, ignoreCase = true)
        }
    }

    private fun store(name: String, value: ConfigValue, ctx: FeatureExecutionContext) {
        name.trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, value) }
    }

    private fun permissionMissing(group: String) =
        ActionExecutionResult(false, message = userText("feature.runtime_permission_required", group))

    private fun invalid(message: String) =
        ActionExecutionResult(false, message = userText("feature.operation_failed", message))

    private fun failure(error: Throwable) =
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
