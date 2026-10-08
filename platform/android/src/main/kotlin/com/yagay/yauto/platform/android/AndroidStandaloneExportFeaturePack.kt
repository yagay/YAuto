package com.yagay.yauto.platform.android

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import com.yagay.yauto.core.storage.StandaloneAutomationCodec
import com.yagay.yauto.core.storage.WorkspaceRepository
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class AndroidStandaloneExportFeaturePack(
    context: Context,
    private val workspace: WorkspaceRepository,
) : FeaturePack {
    override val id: String = "android.standalone_export"
    private val context = context.applicationContext

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.standalone.export"),
                FeatureKind.ACTION,
                "Export standalone automation",
                "Export one automation plus all referenced flows as a self-contained YAuto Runner bundle",
                FeatureCategory.FILE,
                fields = listOf(
                    FieldSchema.Text("target", "Automation ID or name", true),
                    FieldSchema.Toggle("includeVariables", "Include persistent variables"),
                    FieldSchema.Text("fileName", "File name"),
                    FieldSchema.Variable("resultVariable", "Store exported URI"),
                ),
                fieldBehaviors = mapOf(
                    "target" to FieldBehavior(supportsVariables = true),
                    "fileName" to FieldBehavior(supportsVariables = true),
                ),
                keywords = setOf("app factory", "standalone", "export app", "runner", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val target = feature.config.string("target").resolveVariables(ctx.variables).trim()
            if (target.isBlank()) return@registerAction ActionExecutionResult(false)
            val source = workspace.load()
            val bundle = runCatching {
                StandaloneAutomationCodec.encode(
                    workspace = source,
                    automationIdOrName = target,
                    includePersistentVariables = feature.config.boolean("includeVariables", true),
                )
            }.getOrElse {
                return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName))
            }

            val requested = feature.config.string("fileName").resolveVariables(ctx.variables).trim()
            val fileName = sanitizeFileName(
                requested.ifBlank {
                    "YAuto-Standalone-" +
                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) +
                        ".yauto-runner.json"
                }
            )
            val resolver = context.contentResolver
            val uri = resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/YAuto/Standalone")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            ) ?: return@registerAction ActionExecutionResult(false)

            val ok = runCatching {
                resolver.openOutputStream(uri, "w")?.bufferedWriter()?.use { it.write(bundle) }
                    ?: error("Unable to open export output")
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
                true
            }.getOrElse {
                runCatching { resolver.delete(uri, null, null) }
                false
            }
            if (!ok) return@registerAction ActionExecutionResult(false)

            val output = ConfigValue.StringValue(uri.toString())
            feature.config.string("resultVariable").trim().takeIf(String::isNotBlank)?.let {
                ctx.variables.set(it, output)
            }
            ActionExecutionResult(true, output)
        }
    }

    private fun sanitizeFileName(raw: String): String {
        val clean = raw
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            .trim()
            .take(140)
            .ifBlank { "YAuto-Standalone.yauto-runner.json" }
        return if (clean.endsWith(".json", true)) clean else clean + ".yauto-runner.json"
    }
}
