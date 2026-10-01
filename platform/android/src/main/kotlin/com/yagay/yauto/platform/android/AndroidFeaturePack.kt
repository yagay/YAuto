package com.yagay.yauto.platform.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

class AndroidFeaturePack(
    private val context: Context,
) : FeaturePack {
    override val id: String = "android"

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.toast.show"), FeatureKind.ACTION, "Show toast", "Show an Android toast",
                FeatureCategory.NOTIFICATION,
                fields = listOf(FieldSchema.Text("text", "Text", true, true)),
                ownerPackId = id,
            )
        ) { feature, _ ->
            Toast.makeText(context, feature.config.string("text"), Toast.LENGTH_SHORT).show()
            ActionExecutionResult(true)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.app.launch"), FeatureKind.ACTION, "Launch app", "Launch an installed application",
                FeatureCategory.APP,
                fields = listOf(FieldSchema.AppPicker("package", "App", true)),
                ownerPackId = id,
            )
        ) { feature, _ ->
            val pkg = feature.config.string("package")
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent == null) ActionExecutionResult(false, message = "No launch intent for $pkg")
            else {
                context.startActivity(intent)
                ActionExecutionResult(true)
            }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.uri.open"), FeatureKind.ACTION, "Open URL / URI", "Open a web link or Android URI",
                FeatureCategory.APP,
                fields = listOf(FieldSchema.Text("uri", "URI", true)),
                ownerPackId = id,
            )
        ) { feature, _ ->
            val uri = feature.config.string("uri")
            if (uri.isBlank()) return@registerAction ActionExecutionResult(false, message = "URI is empty")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
                .fold({ ActionExecutionResult(true) }, { ActionExecutionResult(false, message = it.message) })
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.clipboard.set"), FeatureKind.ACTION, "Set clipboard", "Put text on the Android clipboard",
                FeatureCategory.DEVICE,
                fields = listOf(FieldSchema.Text("text", "Text", true, true)),
                ownerPackId = id,
            )
        ) { feature, _ ->
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("YAuto", feature.config.string("text")))
            ActionExecutionResult(true)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.share.text"), FeatureKind.ACTION, "Share text", "Open Android's share sheet with text",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Text("text", "Text", true, true),
                    FieldSchema.Text("title", "Chooser title"),
                ),
                ownerPackId = id,
            )
        ) { feature, _ ->
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, feature.config.string("text"))
            }
            val chooser = Intent.createChooser(share, feature.config.string("title").ifBlank { null })
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(chooser) }
                .fold({ ActionExecutionResult(true) }, { ActionExecutionResult(false, message = it.message) })
        }
    }
}
