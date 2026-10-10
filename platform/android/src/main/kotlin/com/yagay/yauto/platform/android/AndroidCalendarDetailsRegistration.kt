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


/** Calendar actions, attendee/reminder registration and conditions. */
internal fun AndroidPersonalDataFeaturePack.registerCalendarAttendees(registry: FeatureRegistry) {
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

internal fun AndroidPersonalDataFeaturePack.registerCalendarReminders(registry: FeatureRegistry) {
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

internal fun AndroidPersonalDataFeaturePack.registerCalendarCondition(registry: FeatureRegistry) {
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

