package com.yagay.yauto.platform.android

import android.content.Context
import android.content.Intent
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
        intentAction(registry, "android.settings.open", "Open Android settings", "Open a common Android settings page", FeatureCategory.SYSTEM,
            listOf(FieldSchema.Choice("page", "Settings page", true, listOf("main", "wifi", "bluetooth", "display", "sound", "location", "apps", "accessibility", "notification_listener"))), setOf("settings", "wifi settings")) { feature, _ ->
            val action = when (feature.config.string("page", "main")) {
                "wifi" -> android.provider.Settings.ACTION_WIFI_SETTINGS
                "bluetooth" -> android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
                "display" -> android.provider.Settings.ACTION_DISPLAY_SETTINGS
                "sound" -> android.provider.Settings.ACTION_SOUND_SETTINGS
                "location" -> android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS
                "apps" -> android.provider.Settings.ACTION_APPLICATION_SETTINGS
                "accessibility" -> android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
                "notification_listener" -> android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
                else -> android.provider.Settings.ACTION_SETTINGS
            }
            Intent(action)
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
                if (intent.resolveActivity(context.packageManager) == null) return@runCatching ActionExecutionResult(false, message = userText("feature.no_compatible_app", "No compatible application found"))
                context.startActivity(intent); ActionExecutionResult(true)
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", "Operation failed: %s", it.message ?: it.javaClass.simpleName)) }
        }
    }
}
