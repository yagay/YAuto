package com.yagay.yauto.platform.android

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.role.RoleManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.Context
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*


internal fun AndroidFinalParityFeaturePack.registerTaskerPluginBridge(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.plugin.locale.fire"), FeatureKind.ACTION,
                "Fire Locale/Tasker plug-in setting",
                "Send the standard Locale/Tasker FIRE_SETTING broadcast to a plug-in component with string bundle fields",
                FeatureCategory.ADVANCED,
                fields = listOf(
                    FieldSchema.Text("component", "Receiver component package/class", true),
                    FieldSchema.Text("extras", "Bundle fields, key=value per line", multiline = true),
                    FieldSchema.Text("blurb", "Blurb / display text"),
                ),
                keywords = setOf("tasker plugin", "locale plugin", "fire setting", "plugin action", "external provider"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val component = android.content.ComponentName.unflattenFromString(
                feature.config.string("component").resolveVariables(ctx.variables).trim()
            ) ?: return@registerAction ActionExecutionResult(false)
            val bundle = android.os.Bundle()
            parseExtras(feature.config.string("extras").resolveVariables(ctx.variables)).forEach { (key, value) ->
                bundle.putString(key, value)
            }
            val intent = android.content.Intent("com.twofortyfouram.locale.intent.action.FIRE_SETTING")
                .setComponent(component)
                .putExtra("com.twofortyfouram.locale.intent.extra.BUNDLE", bundle)
            runCatching {
                context.sendBroadcast(intent)
                ActionExecutionResult(true)
            }.getOrElse { failure(it) }
        }
    }


internal fun AndroidFinalParityFeaturePack.registerWidgetBridge(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.widget.configure"), FeatureKind.ACTION,
                "Configure YAuto widget",
                "Update the label and command used by all YAuto home-screen widgets",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(
                    FieldSchema.Text("label", "Widget label", true),
                    FieldSchema.Text("command", "Command value"),
                ),
                fieldBehaviors = mapOf(
                    "label" to FieldBehavior(supportsVariables = true),
                    "command" to FieldBehavior(supportsVariables = true),
                ),
                keywords = setOf("widget", "home screen", "button", "tasker", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val label = feature.config.string("label").resolveVariables(ctx.variables).trim()
            if (label.isBlank()) return@registerAction ActionExecutionResult(false)
            val command = feature.config.string("command").resolveVariables(ctx.variables)
            val intent = Intent("com.yagay.yauto.WIDGET_CONFIGURE")
                .setPackage(context.packageName)
                .putExtra("label", label)
                .putExtra("command", command)
            runCatching {
                context.sendBroadcast(intent)
                ActionExecutionResult(true)
            }.getOrElse { failure(it) }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.widget.refresh"), FeatureKind.ACTION,
                "Refresh YAuto widgets",
                "Force all YAuto home-screen widgets to refresh their current configuration",
                FeatureCategory.UI_AUTOMATION,
                keywords = setOf("widget", "refresh", "update"), ownerPackId = id,
            )
        ) { _, _ ->
            runCatching {
                context.sendBroadcast(Intent("com.yagay.yauto.WIDGET_REFRESH").setPackage(context.packageName))
                ActionExecutionResult(true)
            }.getOrElse { failure(it) }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.widget.pin"), FeatureKind.ACTION,
                "Pin YAuto widget",
                "Ask the current launcher to pin the YAuto widget to the home screen",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(
                    FieldSchema.Text("label", "Widget label"),
                    FieldSchema.Text("command", "Command value"),
                ),
                keywords = setOf("widget", "pin", "home screen", "launcher"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val manager = context.getSystemService(AppWidgetManager::class.java)
            if (!manager.isRequestPinAppWidgetSupported) return@registerAction ActionExecutionResult(false)
            val label = feature.config.string("label").resolveVariables(ctx.variables).trim()
            val command = feature.config.string("command").resolveVariables(ctx.variables)
            if (label.isNotBlank()) {
                context.sendBroadcast(
                    Intent("com.yagay.yauto.WIDGET_CONFIGURE")
                        .setPackage(context.packageName)
                        .putExtra("label", label)
                        .putExtra("command", command)
                )
            }
            val provider = ComponentName(context.packageName, "com.yagay.yauto.YAutoWidgetProvider")
            val accepted = manager.requestPinAppWidget(provider, null, null)
            ActionExecutionResult(accepted, ConfigValue.BooleanValue(accepted))
        }
    }


internal fun AndroidFinalParityFeaturePack.registerFinalUtilities(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.external.service.stop"), FeatureKind.ACTION,
                "Stop external service",
                "Stop an explicitly named Android service component",
                FeatureCategory.APP,
                fields = listOf(FieldSchema.Text("component", "Service component package/class", true)),
                keywords = setOf("stop service", "service", "shortx", "intent"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val component = android.content.ComponentName.unflattenFromString(
                feature.config.string("component").resolveVariables(ctx.variables).trim()
            ) ?: return@registerAction ActionExecutionResult(false)
            runCatching {
                val stopped = context.stopService(Intent().setComponent(component))
                ActionExecutionResult(stopped, ConfigValue.BooleanValue(stopped))
            }.getOrElse { failure(it) }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.ui.wait_for_idle"), FeatureKind.ACTION,
                "Wait for UI idle",
                "Wait until Android's main message queue becomes idle or the timeout expires",
                FeatureCategory.FLOW,
                fields = listOf(FieldSchema.Duration("timeoutMs", "Maximum wait")),
                keywords = setOf("wait idle", "ui idle", "shortx", "message queue"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            val timeoutMs = ((feature.config["timeoutMs"].numberOrNull() ?: 5_000.0).toLong()).coerceIn(100L, 60_000L)
            val idle = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { continuation ->
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.os.Looper.getMainLooper().queue.addIdleHandler {
                            if (continuation.isActive) continuation.resume(true) { _, _, _ -> }
                            false
                        }
                    }
                }
            } ?: false
            ActionExecutionResult(idle, ConfigValue.BooleanValue(idle))
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.text.pinyin"), FeatureKind.ACTION,
                "Convert Chinese text to Pinyin",
                "Transliterate Han characters to Latin/Pinyin using Android ICU",
                FeatureCategory.VARIABLE,
                fields = listOf(
                    FieldSchema.Text("text", "Text", true, multiline = true),
                    FieldSchema.Toggle("ascii", "Strip accents"),
                    FieldSchema.Variable("resultVariable", "Store Pinyin text", true),
                ),
                fieldBehaviors = mapOf("text" to FieldBehavior(supportsVariables = true)),
                keywords = setOf("pinyin", "Chinese", "transliterate", "shortx", "text"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val input = feature.config.string("text").resolveVariables(ctx.variables)
            val id = if (feature.config.boolean("ascii", false)) "Han-Latin; Latin-ASCII" else "Han-Latin"
            val outputText = runCatching { android.icu.text.Transliterator.getInstance(id).transliterate(input) }
                .getOrElse { return@registerAction failure(it) }
            val output = ConfigValue.StringValue(outputText)
            val name = feature.config.string("resultVariable").trim()
            if (name.isBlank()) return@registerAction ActionExecutionResult(false)
            ctx.variables.set(name, output)
            ActionExecutionResult(true, output)
        }
    }

