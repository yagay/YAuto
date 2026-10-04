package com.yagay.yauto.platform.android

import android.content.Context
import android.content.Intent
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.resolveVariables
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.FieldSchema

/** Share-sheet actions and incoming Share Text / Share File automation triggers. */
class AndroidShareFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.share"
    private val context = context.applicationContext

    override fun install(registry: FeatureRegistry) {
        registerShareTextAction(registry)
        registerShareTextEvent(registry)
        registerShareFileEvent(registry)
    }

    private fun registerShareTextAction(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.share.text"),
                FeatureKind.ACTION,
                "Share text",
                "Open the Android share sheet with text, subject and MIME type",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Text("text", "Text", true, multiline = true),
                    FieldSchema.Text("subject", "Subject"),
                    FieldSchema.Text("mimeType", "MIME type"),
                    FieldSchema.Toggle("chooser", "Always show chooser"),
                ),
                keywords = setOf("share", "send text", "intent", "shortx", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val text = feature.config.string("text").resolveVariables(ctx.variables)
            val subject = feature.config.string("subject").resolveVariables(ctx.variables)
            val mimeType = feature.config.string("mimeType", "text/plain").resolveVariables(ctx.variables).ifBlank { "text/plain" }
            if (text.isBlank() && subject.isBlank()) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.share_text_empty"))
            }
            runCatching {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_TEXT, text)
                    if (subject.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val target = if (feature.config.boolean("chooser", true)) {
                    Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                } else {
                    send
                }
                context.startActivity(target)
                ActionExecutionResult(true)
            }.getOrElse {
                ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName))
            }
        }
    }

    private fun registerShareTextEvent(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.share_text_received"),
                FeatureKind.EVENT,
                "Text shared to YAuto",
                "Trigger when another app shares text to YAuto from the Android share sheet",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Choice("mode", "Text match", options = listOf("any", "contains", "exact", "regex")),
                    FieldSchema.Text("text", "Text / pattern"),
                    FieldSchema.Text("subjectContains", "Subject contains"),
                    FieldSchema.Text("mimeContains", "MIME type contains"),
                    FieldSchema.Toggle("ignoreCase", "Ignore case"),
                ),
                keywords = setOf("share text", "received text", "intent send", "shortx", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.share_text_received") return@registerEvent false
            val ignoreCase = feature.config.boolean("ignoreCase", true)
            if (!shareTextMatches(
                    ctx.event.payload.string("text"),
                    feature.config.string("text"),
                    feature.config.string("mode", "any"),
                    ignoreCase,
                )) return@registerEvent false
            val subject = feature.config.string("subjectContains")
            if (subject.isNotBlank() && !ctx.event.payload.string("subject").contains(subject, ignoreCase)) return@registerEvent false
            val mime = feature.config.string("mimeContains")
            mime.isBlank() || ctx.event.payload.string("mimeType").contains(mime, ignoreCase)
        }
    }

    private fun registerShareFileEvent(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.share_file_received"),
                FeatureKind.EVENT,
                "File shared to YAuto",
                "Trigger when another app shares one or more files to YAuto",
                FeatureCategory.FILE,
                fields = listOf(
                    FieldSchema.Text("mimeContains", "MIME type contains"),
                    FieldSchema.Text("uriContains", "URI contains"),
                    FieldSchema.Number("minCount", "Minimum file count", min = 1.0, max = 1000.0),
                    FieldSchema.Number("maxCount", "Maximum file count", min = 1.0, max = 1000.0),
                    FieldSchema.Toggle("ignoreCase", "Ignore case"),
                ),
                keywords = setOf("share file", "received file", "content uri", "shortx", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.share_file_received") return@registerEvent false
            val ignoreCase = feature.config.boolean("ignoreCase", true)
            val mime = feature.config.string("mimeContains")
            if (mime.isNotBlank() && !ctx.event.payload.string("mimeType").contains(mime, ignoreCase)) return@registerEvent false
            val count = ctx.event.payload["count"].numberOrNull() ?: 0.0
            val min = feature.config["minCount"].numberOrNull() ?: 1.0
            val max = feature.config["maxCount"].numberOrNull() ?: 1000.0
            if (!min.isFinite() || !max.isFinite() || min < 1.0 || max < min || count !in min..max) return@registerEvent false
            val contains = feature.config.string("uriContains")
            if (contains.isBlank()) return@registerEvent true
            val uris = (ctx.event.payload["uris"] as? ConfigValue.ListValue)?.value.orEmpty()
            uris.any { value ->
                (value as? ConfigValue.StringValue)?.value?.contains(contains, ignoreCase) == true
            }
        }
    }
}

internal fun shareTextMatches(actual: String, expected: String, mode: String, ignoreCase: Boolean): Boolean = when (mode) {
    "exact" -> actual.equals(expected, ignoreCase)
    "contains" -> actual.contains(expected, ignoreCase)
    "regex" -> if (expected.isBlank()) false else runCatching {
        Regex(expected, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()).containsMatchIn(actual)
    }.getOrDefault(false)
    else -> true
}
