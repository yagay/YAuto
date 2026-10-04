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
                accessRequirements = setOf(AccessRequirement.ACTIVITY_RECOGNITION),
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
                FeatureId("android.event.sleep_classification"),
                FeatureKind.EVENT,
                "Sleep classification",
                "Run when Google Sleep API reports current sleep confidence, light and motion values",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Number("minConfidence", "Minimum sleep confidence %", min = 0.0, max = 100.0),
                    FieldSchema.Number("maxMotion", "Maximum motion score", min = 0.0, max = 6.0),
                    FieldSchema.Number("maxLight", "Maximum light score", min = 0.0, max = 6.0),
                ),
                accessRequirements = setOf(AccessRequirement.ACTIVITY_RECOGNITION),
                keywords = setOf("sleep", "sleeping", "confidence", "motion", "light", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.sleep_classification") return@registerEvent false
            val confidence = ctx.event.payload["confidence"].numberOrNull() ?: return@registerEvent false
            val motion = ctx.event.payload["motion"].numberOrNull() ?: return@registerEvent false
            val light = ctx.event.payload["light"].numberOrNull() ?: return@registerEvent false
            confidence >= (feature.config["minConfidence"].numberOrNull() ?: 50.0) &&
                motion <= (feature.config["maxMotion"].numberOrNull() ?: 6.0) &&
                light <= (feature.config["maxLight"].numberOrNull() ?: 6.0)
        }

        val sleepingEvaluator = ConditionEvaluator { feature, _ ->
            val snapshot = ActivityRecognitionRuntimeBridge.sleepClassification() ?: return@ConditionEvaluator false
            val maxAgeMs = (feature.config["maxAgeMs"].numberOrNull() ?: 900_000.0).coerceAtLeast(1_000.0)
            val fresh = System.currentTimeMillis() - snapshot.receivedEpochMs <= maxAgeMs
            val sleeping = fresh &&
                snapshot.confidence >= (feature.config["minConfidence"].numberOrNull() ?: 50.0) &&
                snapshot.motion <= (feature.config["maxMotion"].numberOrNull() ?: 6.0) &&
                snapshot.light <= (feature.config["maxLight"].numberOrNull() ?: 6.0)
            sleeping == ((feature.config["value"] as? com.yagay.yauto.core.model.ConfigValue.BooleanValue)?.value ?: true)
        }
        val sleepingFields = listOf(
            FieldSchema.Number("minConfidence", "Minimum sleep confidence %", min = 0.0, max = 100.0),
            FieldSchema.Number("maxMotion", "Maximum motion score", min = 0.0, max = 6.0),
            FieldSchema.Number("maxLight", "Maximum light score", min = 0.0, max = 6.0),
            FieldSchema.Duration("maxAgeMs", "Maximum classification age"),
            FieldSchema.Toggle("value", "Sleeping"),
        )
        val sleepingState = FeatureDescriptor(
            FeatureId("android.state.sleeping"),
            FeatureKind.STATE,
            "Sleeping",
            "Check the latest Google Sleep API classification using confidence, motion and light thresholds",
            FeatureCategory.DEVICE,
            fields = sleepingFields,
            accessRequirements = setOf(AccessRequirement.ACTIVITY_RECOGNITION),
            keywords = setOf("sleeping", "sleep", "confidence", "tasker"),
            ownerPackId = id,
        )
        registry.registerState(sleepingState, sleepingEvaluator)
        registry.registerCondition(
            sleepingState.copy(id = FeatureId("android.condition.sleeping"), kind = FeatureKind.CONDITION),
            sleepingEvaluator,
        )

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
                FeatureId("android.event.accessibility_state_changed"),
                FeatureKind.EVENT,
                "Accessibility service state changed",
                "Run when Android's global accessibility enabled state changes",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(FieldSchema.Choice("state", "State", options = listOf("any", "enabled", "disabled"))),
                keywords = setOf("accessibility", "service state", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.accessibility_state_changed") return@registerEvent false
            val enabled = (ctx.event.payload["enabled"] as? com.yagay.yauto.core.model.ConfigValue.BooleanValue)?.value
            when (feature.config.string("state", "any")) {
                "enabled" -> enabled == true
                "disabled" -> enabled == false
                else -> true
            }
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.cellular_service_changed"),
                FeatureKind.EVENT,
                "Cellular signal/service changed",
                "Run when Android telephony service availability changes",
                FeatureCategory.NETWORK,
                fields = listOf(FieldSchema.Choice("state", "State", options = listOf("any", "available", "unavailable"))),
                keywords = setOf("signal", "service", "cellular", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.cellular_service_changed") return@registerEvent false
            val available = (ctx.event.payload["available"] as? com.yagay.yauto.core.model.ConfigValue.BooleanValue)?.value
            when (feature.config.string("state", "any")) {
                "available" -> available == true
                "unavailable" -> available == false
                else -> true
            }
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.shizuku_stopped"),
                FeatureKind.EVENT,
                "Shizuku stopped",
                "Run when the Shizuku binder dies",
                FeatureCategory.SYSTEM,
                keywords = setOf("shizuku", "stopped", "binder", "macrodroid"),
                ownerPackId = id,
            )
        ) { _, ctx -> ctx.event.typeId == "android.event.shizuku_stopped" }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.email_received"),
                FeatureKind.EVENT,
                "Email notification received",
                "Run when Android posts an email-category notification matching optional filters",
                FeatureCategory.NOTIFICATION,
                fields = listOf(
                    FieldSchema.AppPicker("package", "Mail app package"),
                    FieldSchema.Text("subjectContains", "Subject/title contains"),
                    FieldSchema.Text("bodyContains", "Body contains"),
                ),
                keywords = setOf("email", "mail", "notification", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.notification_posted") return@registerEvent false
            val category = ctx.event.payload.string("category")
            if (category != android.app.Notification.CATEGORY_EMAIL && !category.equals("email", true)) {
                return@registerEvent false
            }
            val pkg = feature.config.string("package").trim()
            val subject = feature.config.string("subjectContains").trim()
            val body = feature.config.string("bodyContains").trim()
            (pkg.isBlank() || ctx.event.payload.string("package") == pkg) &&
                (subject.isBlank() || ctx.event.payload.string("title").contains(subject, true)) &&
                (body.isBlank() || ctx.event.payload.string("text").contains(body, true))
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
