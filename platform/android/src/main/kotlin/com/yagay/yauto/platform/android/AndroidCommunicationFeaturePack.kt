package com.yagay.yauto.platform.android

import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.telephony.SmsManager
import com.yagay.yauto.core.model.ConfigValue
import android.net.Uri
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*
import com.yagay.yauto.core.model.userText

class AndroidCommunicationFeaturePack(context: Context) : FeaturePack {
    override val id = "android.communication"
    private val context = context.applicationContext

    override fun install(registry: FeatureRegistry) {
        intentAction(registry, "android.phone.dial", "Dial phone number", "Open the system dialer with a number", FeatureCategory.APP,
            listOf(FieldSchema.Text("number", "Phone number", true)), setOf("dial", "phone", "call")) { feature, ctx ->
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(feature.config.string("number").resolveVariables(ctx.variables))}"))
        }
        intentAction(registry, "android.sms.compose", "Compose SMS", "Open the default SMS app with recipient and message prefilled", FeatureCategory.APP,
            listOf(FieldSchema.Text("number", "Phone number"), FieldSchema.Text("message", "Message", multiline = true)), setOf("sms", "message")) { feature, ctx ->
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(feature.config.string("number").resolveVariables(ctx.variables))}"))
                .putExtra("sms_body", feature.config.string("message").resolveVariables(ctx.variables))
        }
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.sms.send"),
                FeatureKind.ACTION,
                "Send SMS",
                "Submit an SMS directly through Android telephony after SEND_SMS permission is granted. Submission is not delivery confirmation.",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Text("number", "Phone number", true),
                    FieldSchema.Text("message", "Message", true, multiline = true),
                    FieldSchema.Number("subscriptionId", "SIM subscription ID (-1 = default)", min = -1.0),
                ),
                accessRequirements = setOf(AccessRequirement.SMS),
                keywords = setOf("send sms", "text message", "direct send", "shortx", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val number = feature.config.string("number").resolveVariables(ctx.variables).trim()
            val message = feature.config.string("message").resolveVariables(ctx.variables)
            if (number.isEmpty() || number.length > 40 || number.any { Character.isISOControl(it) } ||
                message.isBlank() || message.length > 1600
            ) {
                return@registerAction ActionExecutionResult(
                    false, message = userText("feature.operation_failed", "Invalid SMS number or message"),
                )
            }
            if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                return@registerAction ActionExecutionResult(
                    false, message = userText("feature.operation_failed", "SEND_SMS permission required"),
                )
            }
            runCatching {
                val base = context.getSystemService(SmsManager::class.java)
                    ?: return@runCatching ActionExecutionResult(
                        false, message = userText("feature.operation_failed", "SMS service unavailable"),
                    )
                val subId = (feature.config["subscriptionId"] as? ConfigValue.NumberValue)?.value?.toInt() ?: -1
                val sender = if (subId >= 0) base.createForSubscriptionId(subId) else base
                val parts = sender.divideMessage(message)
                if (parts.isEmpty() || parts.size > 24) {
                    return@runCatching ActionExecutionResult(
                        false, message = userText("feature.operation_failed", "SMS is too long"),
                    )
                }
                if (parts.size == 1) sender.sendTextMessage(number, null, message, null, null)
                else sender.sendMultipartTextMessage(number, null, ArrayList(parts), null, null)
                ActionExecutionResult(true, ConfigValue.BooleanValue(true))
            }.getOrElse {
                ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: "SMS send failed"))
            }
        }
        intentAction(registry, "android.email.compose", "Compose email", "Open an email application with recipient, subject and body", FeatureCategory.APP,
            listOf(FieldSchema.Text("to", "Recipient"), FieldSchema.Text("subject", "Subject"), FieldSchema.Text("body", "Body", multiline = true)), setOf("email", "mail")) { feature, ctx ->
            val recipient = feature.config.string("to").resolveVariables(ctx.variables)
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(recipient)}")).apply {
                putExtra(Intent.EXTRA_SUBJECT, feature.config.string("subject").resolveVariables(ctx.variables))
                putExtra(Intent.EXTRA_TEXT, feature.config.string("body").resolveVariables(ctx.variables))
            }
        }
        intentAction(registry, "android.maps.open", "Open map location", "Open a geo coordinate or search query in a maps application", FeatureCategory.APP,
            listOf(FieldSchema.Text("query", "Place / query"), FieldSchema.Number("latitude", "Latitude", min = -90.0, max = 90.0), FieldSchema.Number("longitude", "Longitude", min = -180.0, max = 180.0)), setOf("maps", "location", "geo")) { feature, ctx ->
            val query = feature.config.string("query").resolveVariables(ctx.variables)
            val lat = (feature.config["latitude"] as? com.yagay.yauto.core.model.ConfigValue.NumberValue)?.value
            val lon = (feature.config["longitude"] as? com.yagay.yauto.core.model.ConfigValue.NumberValue)?.value
            val uri = when {
                lat != null && lon != null && query.isNotBlank() -> "geo:$lat,$lon?q=${Uri.encode(query)}"
                lat != null && lon != null -> "geo:$lat,$lon"
                else -> "geo:0,0?q=${Uri.encode(query)}"
            }
            Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        }
    }

    private fun intentAction(
        registry: FeatureRegistry,
        typeId: String,
        title: String,
        description: String,
        category: FeatureCategory,
        fields: List<FieldSchema>,
        keywords: Set<String>,
        build: (com.yagay.yauto.core.model.FeatureRef, FeatureExecutionContext) -> Intent,
    ) {
        registry.registerAction(
            FeatureDescriptor(FeatureId(typeId), FeatureKind.ACTION, title, description, category, fields = fields, keywords = keywords, ownerPackId = id)
        ) { feature, ctx ->
            runCatching {
                val intent = build(feature, ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(context.packageManager) == null) return@runCatching ActionExecutionResult(false, message = userText("feature.no_compatible_app"))
                context.startActivity(intent); ActionExecutionResult(true)
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }
    }
}
