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
internal fun AndroidPersonalDataFeaturePack.registerCalendarQuery(registry: FeatureRegistry) {
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

internal fun AndroidPersonalDataFeaturePack.registerCalendarWriteActions(registry: FeatureRegistry) {
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

internal fun AndroidPersonalDataFeaturePack.calendarEventValues(
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

