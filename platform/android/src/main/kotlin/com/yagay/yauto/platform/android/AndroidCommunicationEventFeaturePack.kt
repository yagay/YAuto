package com.yagay.yauto.platform.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.ConditionEvaluator
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.FieldSchema

class AndroidCommunicationEventFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.communication.events"
    private val context = context.applicationContext
    private val telephony = context.applicationContext.getSystemService(TelephonyManager::class.java)

    override fun install(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.sms_received"), FeatureKind.EVENT,
                "SMS received", "Run when Android delivers an incoming SMS and optionally match sender or message text",
                FeatureCategory.NOTIFICATION,
                fields = listOf(
                    FieldSchema.Text("addressContains", "Sender contains"),
                    FieldSchema.Text("bodyContains", "Message contains"),
                ),
                keywords = setOf("sms", "message", "incoming", "sender", "text"), ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.sms_received") return@registerEvent false
            val address = ctx.event.payload.string("address")
            val body = ctx.event.payload.string("body")
            val addressFilter = feature.config.string("addressContains")
            val bodyFilter = feature.config.string("bodyContains")
            (addressFilter.isBlank() || address.contains(addressFilter, ignoreCase = true)) &&
                (bodyFilter.isBlank() || body.contains(bodyFilter, ignoreCase = true))
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.phone_state_changed"), FeatureKind.EVENT,
                "Phone call state changed", "Run when Android reports ringing, off-hook or idle phone-call state",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Choice("state", "Call state", true, listOf("any", "ringing", "offhook", "idle")),
                    FieldSchema.Text("numberContains", "Phone number contains"),
                ),
                keywords = setOf("phone", "call", "ringing", "incoming", "offhook", "idle"), ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.phone_state_changed") return@registerEvent false
            val expected = feature.config.string("state", "any")
            val actual = ctx.event.payload.string("state")
            val numberFilter = feature.config.string("numberContains")
            (expected == "any" || actual == expected) &&
                (numberFilter.isBlank() || ctx.event.payload.string("number").contains(numberFilter, ignoreCase = true))
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.call_screened"),
                FeatureKind.EVENT,
                "Call screened",
                "Run when YAuto receives a call through Android's Call Screening role",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Choice("direction", "Direction", options = listOf("any", "incoming", "outgoing")),
                    FieldSchema.Text("numberContains", "Phone number contains"),
                    FieldSchema.Text("nameContains", "Contact name contains"),
                ),
                keywords = setOf("call screened", "call screening", "incoming", "outgoing", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.call_screened") return@registerEvent false
            val direction = feature.config.string("direction", "any")
            val number = feature.config.string("numberContains")
            val name = feature.config.string("nameContains")
            (direction == "any" || ctx.event.payload.string("direction") == direction) &&
                (number.isBlank() || ctx.event.payload.string("number").contains(number, ignoreCase = true)) &&
                (name.isBlank() || ctx.event.payload.string("name").contains(name, ignoreCase = true))
        }

        registerCallState(registry, FeatureKind.STATE, "android.state.phone_call_state")
        registerCallState(registry, FeatureKind.CONDITION, "android.condition.phone_call_state")
    }

    private fun registerCallState(registry: FeatureRegistry, kind: FeatureKind, typeId: String) {
        val descriptor = FeatureDescriptor(
            FeatureId(typeId), kind,
            "Phone call state", "Match the current Android telephony call state when phone-state permission is available",
            FeatureCategory.DEVICE,
            fields = listOf(FieldSchema.Choice("state", "Call state", true, listOf("idle", "ringing", "offhook"))),
            keywords = setOf("phone", "call", "ringing", "offhook", "idle"), ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, _ ->
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                return@ConditionEvaluator false
            }
            val actual = runCatching {
                @Suppress("DEPRECATION")
                when (telephony.callState) {
                    TelephonyManager.CALL_STATE_RINGING -> "ringing"
                    TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"
                    else -> "idle"
                }
            }.getOrDefault("idle")
            actual == feature.config.string("state", "idle")
        }
        if (kind == FeatureKind.STATE) registry.registerState(descriptor, evaluator) else registry.registerCondition(descriptor, evaluator)
    }
}
