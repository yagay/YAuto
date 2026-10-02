package com.yagay.yauto.platform.android

import android.content.Context
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*
import java.util.UUID
import com.yagay.yauto.core.model.userText

class ExternalCommandTokenStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("yauto_external_commands", Context.MODE_PRIVATE)

    fun current(): String = prefs.getString("token", null).orEmpty()
    fun generate(): String = UUID.randomUUID().toString().also { prefs.edit().putString("token", it).apply() }
    fun set(value: String) { prefs.edit().putString("token", value).apply() }
    fun disable() { prefs.edit().remove("token").apply() }
    fun validate(value: String): Boolean = current().let { it.isNotBlank() && it == value }
}

class AndroidExternalCommandFeaturePack(context: Context) : FeaturePack {
    override val id = "android.external_command"
    private val tokens = ExternalCommandTokenStore(context.applicationContext)

    override fun install(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.external_command"), FeatureKind.EVENT,
                "External command", "Run when an authenticated YAuto command broadcast is received",
                FeatureCategory.SYSTEM,
                fields = listOf(
                    FieldSchema.Text("name", "Command name"),
                    FieldSchema.Text("payloadContains", "Payload contains"),
                ),
                keywords = setOf("command", "external", "adb", "tasker", "broadcast"), ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.external_command") return@registerEvent false
            val name = feature.config.string("name")
            val payload = feature.config.string("payloadContains")
            (name.isBlank() || ctx.event.payload.string("name") == name) &&
                (payload.isBlank() || ctx.event.payload.string("payload").contains(payload, true))
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.external_command.token_generate"), FeatureKind.ACTION,
                "Generate external command token", "Replace the external-command authentication token and store it in a variable",
                FeatureCategory.SYSTEM,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store token in variable", true)),
                keywords = setOf("command token", "api token", "external"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val value = ConfigValue.StringValue(tokens.generate())
            ctx.variables.set(feature.config.string("resultVariable"), value)
            ActionExecutionResult(true, value)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.external_command.token_set"), FeatureKind.ACTION,
                "Set external command token", "Set a custom authentication token; use a long random value",
                FeatureCategory.SYSTEM,
                fields = listOf(FieldSchema.Text("token", "Token", true)), ownerPackId = id,
            )
        ) { feature, ctx ->
            val token = feature.config.string("token").resolveVariables(ctx.variables)
            if (token.length < 16) ActionExecutionResult(false, message = userText("feature.token_too_short", "Token must be at least 16 characters"))
            else { tokens.set(token); ActionExecutionResult(true) }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.external_command.disable"), FeatureKind.ACTION,
                "Disable external commands", "Remove the authentication token so external command broadcasts are ignored",
                FeatureCategory.SYSTEM, ownerPackId = id,
            )
        ) { _, _ -> tokens.disable(); ActionExecutionResult(true) }
    }
}
