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


internal fun AndroidPersonalDataFeaturePack.registerCallLogQuery(registry: FeatureRegistry) {
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

internal fun AndroidPersonalDataFeaturePack.registerSmsQuery(registry: FeatureRegistry) {
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

internal fun AndroidPersonalDataFeaturePack.registerSmsSend(registry: FeatureRegistry) {
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

internal fun AndroidPersonalDataFeaturePack.registerCallLogEntryEvents(registry: FeatureRegistry) {
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

internal fun AndroidPersonalDataFeaturePack.registerSmsEntryEvents(registry: FeatureRegistry) {
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

internal fun AndroidPersonalDataFeaturePack.registerChangedEvent(registry: FeatureRegistry, typeId: String, title: String, access: AccessRequirement) {
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

