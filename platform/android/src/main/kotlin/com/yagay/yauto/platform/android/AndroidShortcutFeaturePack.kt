package com.yagay.yauto.platform.android

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*
import com.yagay.yauto.core.model.userText

class AndroidShortcutFeaturePack(context: Context) : FeaturePack {
    override val id = "android.shortcuts"
    private val context = context.applicationContext
    private val manager = context.applicationContext.getSystemService(ShortcutManager::class.java)

    override fun install(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.shortcut"), FeatureKind.EVENT,
                "YAuto shortcut", "Run when a YAuto launcher shortcut is opened",
                FeatureCategory.APP,
                fields = listOf(FieldSchema.Text("id", "Shortcut ID"), FieldSchema.Text("command", "Command")),
                keywords = setOf("shortcut", "launcher", "home screen"), ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.shortcut") return@registerEvent false
            val id = feature.config.string("id")
            val command = feature.config.string("command")
            (id.isBlank() || ctx.event.payload.string("id") == id) &&
                (command.isBlank() || ctx.event.payload.string("command") == command)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.shortcut.create"), FeatureKind.ACTION,
                "Create launcher shortcut", "Create/update a dynamic shortcut and optionally request a pinned home-screen shortcut",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Text("id", "Shortcut ID", true),
                    FieldSchema.Text("label", "Label", true),
                    FieldSchema.Text("command", "Command"),
                    FieldSchema.Toggle("requestPinned", "Request pinned home-screen shortcut"),
                ),
                keywords = setOf("shortcut", "launcher", "home screen"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val id = feature.config.string("id").resolveVariables(ctx.variables).trim()
            val label = feature.config.string("label").resolveVariables(ctx.variables).trim()
            val command = feature.config.string("command").resolveVariables(ctx.variables)
            if (id.isBlank() || label.isBlank()) return@registerAction ActionExecutionResult(false, message = userText("feature.shortcut_id_label_required", "Shortcut ID and label are required"))
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW).setClassName(context.packageName, "${context.packageName}.ShortcutDispatchActivity")
                    .putExtra("shortcutId", id).putExtra("command", command)
                val shortcut = ShortcutInfo.Builder(context, id)
                    .setShortLabel(label.take(40))
                    .setLongLabel(label.take(80))
                    .setIcon(Icon.createWithResource(context, android.R.drawable.ic_menu_send))
                    .setIntent(intent)
                    .build()
                manager.addDynamicShortcuts(listOf(shortcut))
                if (feature.config.boolean("requestPinned") && manager.isRequestPinShortcutSupported) manager.requestPinShortcut(shortcut, null)
                ActionExecutionResult(true)
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", "Operation failed: %s", it.message ?: it.javaClass.simpleName)) }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.shortcut.remove"), FeatureKind.ACTION,
                "Remove dynamic shortcut", "Remove a YAuto dynamic shortcut by ID (launchers control pinned shortcut removal)",
                FeatureCategory.APP,
                fields = listOf(FieldSchema.Text("id", "Shortcut ID", true)), ownerPackId = id,
            )
        ) { feature, ctx ->
            val id = feature.config.string("id").resolveVariables(ctx.variables).trim()
            runCatching { manager.removeDynamicShortcuts(listOf(id)); ActionExecutionResult(true) }
                .getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", "Operation failed: %s", it.message ?: it.javaClass.simpleName)) }
        }
    }
}
