package com.yagay.yauto.platform.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.capability.CapabilityResult
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*
import com.yagay.yauto.core.model.userText

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
        ) { feature, ctx ->
            Toast.makeText(context, feature.config.string("text").resolveVariables(ctx.variables), Toast.LENGTH_SHORT).show()
            ActionExecutionResult(true)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.app.launch"), FeatureKind.ACTION, "Launch app",
                "Launch using the public Android API or the ShortX Root/Shizuku shell method",
                FeatureCategory.APP,
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = listOf(
                    FeatureImplementationOption("android"),
                    FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
                    FeatureImplementationOption("shizuku", setOf(AccessRequirement.SHIZUKU)),
                ),
                fields = listOf(FieldSchema.AppPicker("package", "App", true)),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val pkg = feature.config.string("package").resolveVariables(ctx.variables)
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent == null) return@registerAction ActionExecutionResult(
                false, message = userText("feature.no_launch_intent", pkg),
            )
            val result = routeFeatureMethod(
                method = feature.preferredMethod(),
                macroSupported = true,
                macrodroid = {
                    runCatching {
                        context.startActivity(intent)
                        CapabilityResult(true)
                    }.getOrElse { error ->
                        CapabilityResult(false, message = userText(
                            "feature.operation_failed", error.message ?: error.javaClass.simpleName,
                        ))
                    }
                },
                shortx = {
                    val component = intent.component?.flattenToString().orEmpty()
                    if (component.isBlank()) {
                        CapabilityResult(false, message = userText("feature.no_launch_intent", pkg))
                    } else {
                        val quotedComponent = "'" + component.replace("'", "'\\''") + "'"
                        val command = "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $quotedComponent"
                        val backend = feature.preferredBackendId()
                        ctx.executeCapability(feature.typeId, CapabilityRequest(
                            capability = CapabilityIds.PRIVILEGED_SHELL,
                            operationId = feature.typeId,
                            payload = mapOf("command" to ConfigValue.StringValue(command)),
                            preferredBackendId = backend,
                            allowFallback = backend == null,
                        ))
                    }
                },
                succeeded = { it.success },
            )
            ActionExecutionResult(result?.success == true, result?.value ?: ConfigValue.NullValue,
                result?.message)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.uri.open"), FeatureKind.ACTION, "Open URL / URI", "Open a web link or Android URI",
                FeatureCategory.APP,
                fields = listOf(FieldSchema.Text("uri", "URI", true)),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val uri = feature.config.string("uri").resolveVariables(ctx.variables)
            if (uri.isBlank()) return@registerAction ActionExecutionResult(false, message = userText("feature.uri_empty"))
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
                .fold({ ActionExecutionResult(true) }, { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) })
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.clipboard.set"), FeatureKind.ACTION, "Set clipboard", "Put text on the Android clipboard",
                FeatureCategory.DEVICE,
                fields = listOf(FieldSchema.Text("text", "Text", true, true)),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val text = feature.config.string("text").resolveVariables(ctx.variables)
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("YAuto", text))
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
        ) { feature, ctx ->
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, feature.config.string("text").resolveVariables(ctx.variables))
            }
            val title = feature.config.string("title").resolveVariables(ctx.variables)
            val chooser = Intent.createChooser(share, title.ifBlank { null }).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(chooser) }
                .fold({ ActionExecutionResult(true) }, { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) })
        }
    }
}
