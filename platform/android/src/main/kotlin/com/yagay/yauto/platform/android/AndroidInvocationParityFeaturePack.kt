package com.yagay.yauto.platform.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

class AndroidInvocationParityFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.invocation_parity"
    private val context = context.applicationContext

    override fun install(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.deep_link"), FeatureKind.EVENT,
                "YAuto deep link",
                "Run when YAuto receives a yauto:// deep link",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Text("host", "Host"),
                    FieldSchema.Text("pathContains", "Path contains"),
                ),
                keywords = setOf("deep link", "uri", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.deep_link") return@registerEvent false
            val host = feature.config.string("host").trim()
            val path = feature.config.string("pathContains").trim()
            (host.isBlank() || ctx.event.payload.string("host").equals(host, ignoreCase = true)) &&
                (path.isBlank() || ctx.event.payload.string("path").contains(path, ignoreCase = true))
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.browser_intercept"), FeatureKind.EVENT,
                "Browser URL intercepted",
                "Run when the optional YAuto browser interceptor receives an HTTP/HTTPS URL",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Text("urlContains", "URL text / regex"),
                    FieldSchema.Toggle("regex", "Use regex"),
                    FieldSchema.Toggle("openUrlAfterTrigger", "Forward URL after trigger"),
                    FieldSchema.Text("browserPackage", "Forward to browser package"),
                ),
                keywords = setOf("browser intercept", "url", "http", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.browser_intercept") return@registerEvent false
            val expected = feature.config.string("urlContains").trim()
            if (expected.isBlank()) true
            else if (feature.config.boolean("regex", false)) {
                runCatching { Regex(expected).containsMatchIn(ctx.event.payload.string("url")) }.getOrDefault(false)
            } else ctx.event.payload.string("url").contains(expected, ignoreCase = true)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.browser_intercept.set"), FeatureKind.ACTION,
                "Enable browser interception",
                "Enable or disable YAuto's HTTP/HTTPS browser-handler component",
                FeatureCategory.APP,
                fields = listOf(FieldSchema.Toggle("enabled", "Enabled")),
                keywords = setOf("browser intercept", "browser handler", "shortx"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            val component = ComponentName(context.packageName, "com.yagay.yauto.BrowserInterceptActivity")
            val state = if (feature.config.boolean("enabled", true)) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            runCatching {
                context.packageManager.setComponentEnabledSetting(
                    component,
                    state,
                    PackageManager.DONT_KILL_APP,
                )
                ActionExecutionResult(true, ConfigValue.BooleanValue(state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED))
            }.getOrElse { ActionExecutionResult(false) }
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.menu_action"), FeatureKind.EVENT,
                "YAuto menu action",
                "Run when a named YAuto menu/action entry is invoked",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(FieldSchema.Text("name", "Action name")),
                keywords = setOf("menu action", "command", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.menu_action") return@registerEvent false
            val expected = feature.config.string("name").trim()
            expected.isBlank() || ctx.event.payload.string("name").equals(expected, ignoreCase = true)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.menu_action.fire"), FeatureKind.ACTION,
                "Fire YAuto menu action",
                "Emit a named YAuto menu-action runtime event",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(
                    FieldSchema.Text("name", "Action name", true),
                    FieldSchema.Text("payload", "Payload"),
                ),
                fieldBehaviors = mapOf(
                    "name" to FieldBehavior(supportsVariables = true),
                    "payload" to FieldBehavior(supportsVariables = true),
                ),
                keywords = setOf("menu action", "command", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val name = feature.config.string("name").resolveVariables(ctx.variables).trim()
            if (name.isBlank()) return@registerAction ActionExecutionResult(false)
            context.sendBroadcast(
                Intent("com.yagay.yauto.MENU_ACTION")
                    .setPackage(context.packageName)
                    .putExtra("name", name)
                    .putExtra("payload", feature.config.string("payload").resolveVariables(ctx.variables))
            )
            ActionExecutionResult(true)
        }
    }
}
