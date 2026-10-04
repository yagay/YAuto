package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

class AndroidMacroDroidEventParityFeaturePack : FeaturePack {
    override val id: String = "android.macrodroid.event_parity"

    override fun install(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.activity_recognition"),
                FeatureKind.EVENT,
                "Activity recognition",
                "Run when Google Activity Recognition reports an activity sample or transition",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Choice(
                        "activity", "Activity", options = listOf(
                            "any", "in_vehicle", "on_bicycle", "on_foot",
                            "running", "still", "tilting", "walking", "unknown",
                        )
                    ),
                    FieldSchema.Choice("transition", "Transition", options = listOf("any", "enter", "exit", "sample")),
                    FieldSchema.Number("minConfidence", "Minimum confidence %", min = 0.0, max = 100.0),
                ),
                accessRequirements = setOf(AccessRequirement.LOCATION),
                keywords = setOf("activity recognition", "walking", "running", "vehicle", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.activity_recognition") return@registerEvent false
            val activity = feature.config.string("activity", "any")
            val transition = feature.config.string("transition", "any")
            val confidence = ctx.event.payload["confidence"].numberOrNull() ?: 100.0
            (activity == "any" || ctx.event.payload.string("activity") == activity) &&
                (transition == "any" || ctx.event.payload.string("transition") == transition) &&
                confidence >= (feature.config["minConfidence"].numberOrNull() ?: 0.0)
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.sleep_transition"),
                FeatureKind.EVENT,
                "Sleep segment",
                "Run when Google Sleep API reports a sleep segment",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Number("minimumDurationMinutes", "Minimum duration (minutes)", min = 0.0),
                ),
                keywords = setOf("sleep", "sleep segment", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.sleep_transition") return@registerEvent false
            val duration = ctx.event.payload["durationMillis"].numberOrNull() ?: 0.0
            duration >= (feature.config["minimumDurationMinutes"].numberOrNull() ?: 0.0) * 60_000.0
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.calendar_changed"),
                FeatureKind.EVENT,
                "Calendar changed",
                "Run when Android CalendarProvider events change",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Text("titleContains", "Title contains"),
                    FieldSchema.Number("calendarId", "Calendar ID (-1 = any)", min = -1.0),
                ),
                accessRequirements = setOf(AccessRequirement.CALENDAR),
                keywords = setOf("calendar changed", "calendar event", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.calendar_changed") return@registerEvent false
            val title = feature.config.string("titleContains").trim()
            val calendarId = (feature.config["calendarId"].numberOrNull() ?: -1.0).toLong()
            (title.isBlank() || ctx.event.payload.string("title").contains(title, true)) &&
                (calendarId < 0 || (ctx.event.payload["calendarId"].numberOrNull() ?: -1.0).toLong() == calendarId)
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.sms_sent"),
                FeatureKind.EVENT,
                "SMS sent",
                "Run when a new row appears in Android's sent-SMS provider",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Text("addressContains", "Recipient contains"),
                    FieldSchema.Text("bodyContains", "Message contains"),
                ),
                accessRequirements = setOf(AccessRequirement.SMS),
                keywords = setOf("sms sent", "outgoing sms", "message sent", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.sms_sent") return@registerEvent false
            val address = feature.config.string("addressContains").trim()
            val body = feature.config.string("bodyContains").trim()
            (address.isBlank() || ctx.event.payload.string("address").contains(address, true)) &&
                (body.isBlank() || ctx.event.payload.string("body").contains(body, true))
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.assistant_activated"),
                FeatureKind.EVENT,
                "Voice assistant activated",
                "Run when system_server starts a voice-interaction assistant session",
                FeatureCategory.SYSTEM,
                fields = listOf(FieldSchema.AppPicker("package", "Assistant package")),
                accessRequirements = setOf(AccessRequirement.LSPOSED),
                keywords = setOf("assistant", "google assistant", "voice interaction", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ctx.event.typeId == "android.event.assistant_activated" &&
                (feature.config.string("package").isBlank() ||
                    ctx.event.payload.string("package") == feature.config.string("package"))
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.failed_unlock"),
                FeatureKind.EVENT,
                "Failed device unlock",
                "Run when Android DeviceAdmin reports a failed password/PIN/pattern unlock attempt",
                FeatureCategory.SYSTEM,
                keywords = setOf("failed login", "unlock failed", "password failed", "macrodroid"),
                ownerPackId = id,
            )
        ) { _, ctx -> ctx.event.typeId == "android.event.failed_unlock" }
    }
}
