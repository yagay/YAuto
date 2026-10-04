package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.yagay.yauto.core.model.userText

class AndroidFileFeaturePack : FeaturePack {
    override val id = "android.files"

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("file.read_text"), FeatureKind.ACTION, "Read text file",
                "Read a UTF-8 text file into a runtime variable. Android storage access rules still apply.",
                FeatureCategory.FILE,
                fields = listOf(
                    FieldSchema.Text("path", "File path", true),
                    FieldSchema.Variable("resultVariable", "Store text in variable", true),
                    FieldSchema.Number("maxBytes", "Maximum bytes", min = 1.0, max = 10_485_760.0),
                ),
                keywords = setOf("file", "read", "text"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val path = feature.config.string("path").resolveVariables(ctx.variables)
            val variable = feature.config.string("resultVariable").trim()
            val max = (feature.config["maxBytes"] as? ConfigValue.NumberValue)?.value?.toLong()?.coerceIn(1, 10_485_760) ?: 1_048_576
            withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(path)
                    require(file.isFile) { "File does not exist" }
                    require(file.length() <= max) { "File exceeds maxBytes ($max)" }
                    val value = ConfigValue.StringValue(file.readText(Charsets.UTF_8))
                    ctx.variables.set(variable, value)
                    ActionExecutionResult(true, value)
                }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
            }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("file.write_text"), FeatureKind.ACTION, "Write text file",
                "Write or append UTF-8 text; parent directories can be created automatically",
                FeatureCategory.FILE,
                fields = listOf(
                    FieldSchema.Text("path", "File path", true),
                    FieldSchema.Text("text", "Text", true, multiline = true),
                    FieldSchema.Toggle("append", "Append"),
                    FieldSchema.Toggle("createParents", "Create parent directories"),
                ),
                keywords = setOf("file", "write", "append", "save"), ownerPackId = id,
            )
        ) { feature, ctx -> withContext(Dispatchers.IO) {
            runCatching {
                val file = File(feature.config.string("path").resolveVariables(ctx.variables))
                if (feature.config.boolean("createParents", true)) file.parentFile?.mkdirs()
                val text = feature.config.string("text").resolveVariables(ctx.variables)
                if (feature.config.boolean("append")) file.appendText(text, Charsets.UTF_8) else file.writeText(text, Charsets.UTF_8)
                ActionExecutionResult(true, ConfigValue.NumberValue(file.length().toDouble()))
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        } }

        binaryFileAction(registry, "file.copy", "Copy file / directory", "Copy a file or directory tree", false)
        binaryFileAction(registry, "file.move", "Move file / directory", "Move or rename a file or directory", true)

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("file.delete"), FeatureKind.ACTION, "Delete file / directory",
                "Delete a file, or recursively delete a directory when enabled", FeatureCategory.FILE,
                fields = listOf(FieldSchema.Text("path", "Path", true), FieldSchema.Toggle("recursive", "Recursive directory delete")),
                keywords = setOf("file", "delete", "folder"), ownerPackId = id,
            )
        ) { feature, ctx -> withContext(Dispatchers.IO) {
            runCatching {
                val file = File(feature.config.string("path").resolveVariables(ctx.variables))
                if (!file.exists()) return@runCatching ActionExecutionResult(true)
                val ok = if (file.isDirectory && feature.config.boolean("recursive")) file.deleteRecursively() else file.delete()
                ActionExecutionResult(ok, message = if (ok) null else "Delete failed")
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        } }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("file.mkdir"), FeatureKind.ACTION, "Create directory",
                "Create a directory and any missing parent directories", FeatureCategory.FILE,
                fields = listOf(FieldSchema.Text("path", "Directory path", true)),
                keywords = setOf("folder", "directory", "mkdir"), ownerPackId = id,
            )
        ) { feature, ctx -> withContext(Dispatchers.IO) {
            runCatching {
                val file = File(feature.config.string("path").resolveVariables(ctx.variables))
                val ok = file.isDirectory || file.mkdirs()
                ActionExecutionResult(ok, message = if (ok) null else "Could not create directory")
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        } }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("file.list"), FeatureKind.ACTION, "List directory",
                "Store child absolute paths from a directory in a list variable", FeatureCategory.FILE,
                fields = listOf(
                    FieldSchema.Text("path", "Directory path", true),
                    FieldSchema.Variable("resultVariable", "Store list in variable", true),
                    FieldSchema.Toggle("includeHidden", "Include hidden files"),
                ),
                keywords = setOf("folder", "list", "files"), ownerPackId = id,
            )
        ) { feature, ctx -> withContext(Dispatchers.IO) {
            runCatching {
                val directory = File(feature.config.string("path").resolveVariables(ctx.variables))
                require(directory.isDirectory) { "Directory does not exist" }
                val includeHidden = feature.config.boolean("includeHidden")
                val values = directory.listFiles().orEmpty().filter { includeHidden || !it.isHidden }.sortedBy { it.name.lowercase() }
                    .map { ConfigValue.StringValue(it.absolutePath) }
                val output = ConfigValue.ListValue(values)
                ctx.variables.set(feature.config.string("resultVariable"), output)
                ActionExecutionResult(true, output)
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        } }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("file.properties"),
                FeatureKind.ACTION,
                "Get file properties",
                "Read filesystem metadata for an accessible file or directory",
                FeatureCategory.FILE,
                fields = listOf(
                    FieldSchema.Text("path", "Path", true),
                    FieldSchema.Variable("resultVariable", "Store properties object", true),
                ),
                keywords = setOf("file", "properties", "metadata", "size", "modified", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx -> withContext(Dispatchers.IO) {
            runCatching {
                val file = File(feature.config.string("path").resolveVariables(ctx.variables))
                val output = filePropertiesValue(file)
                ctx.variables.set(feature.config.string("resultVariable"), output)
                ActionExecutionResult(true, output)
            }.getOrElse {
                ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName))
            }
        } }

        registerExists(registry, FeatureKind.STATE, "file.state.exists")
        registerExists(registry, FeatureKind.CONDITION, "file.condition.exists")
    }

    private fun binaryFileAction(registry: FeatureRegistry, typeId: String, title: String, description: String, move: Boolean) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId(typeId), FeatureKind.ACTION, title, description, FeatureCategory.FILE,
                fields = listOf(
                    FieldSchema.Text("source", "Source path", true),
                    FieldSchema.Text("destination", "Destination path", true),
                    FieldSchema.Toggle("overwrite", "Overwrite destination"),
                ), keywords = setOf("file", if (move) "move" else "copy", "folder"), ownerPackId = id,
            )
        ) { feature, ctx -> withContext(Dispatchers.IO) {
            runCatching {
                val source = File(feature.config.string("source").resolveVariables(ctx.variables))
                val destination = File(feature.config.string("destination").resolveVariables(ctx.variables))
                require(source.exists()) { "Source does not exist" }
                destination.parentFile?.mkdirs()
                if (move) source.copyRecursively(destination, overwrite = feature.config.boolean("overwrite"))
                    .also { if (it) source.deleteRecursively() }
                else source.copyRecursively(destination, overwrite = feature.config.boolean("overwrite"))
                ActionExecutionResult(true)
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        } }
    }

    private fun registerExists(registry: FeatureRegistry, kind: FeatureKind, typeId: String) {
        val descriptor = FeatureDescriptor(
            FeatureId(typeId), kind, "File / directory exists", "Check an accessible filesystem path", FeatureCategory.FILE,
            fields = listOf(
                FieldSchema.Text("path", "Path", true),
                FieldSchema.Choice("type", "Type", true, listOf("any", "file", "directory")),
            ), keywords = setOf("file", "exists", "directory"), ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, ctx ->
            val file = File(feature.config.string("path").resolveVariables(ctx.variables))
            when (feature.config.string("type", "any")) { "file" -> file.isFile; "directory" -> file.isDirectory; else -> file.exists() }
        }
        if (kind == FeatureKind.STATE) registry.registerState(descriptor, evaluator) else registry.registerCondition(descriptor, evaluator)
    }
}


internal fun filePropertiesValue(file: File): ConfigValue.ObjectValue {
    val exists = file.exists()
    return ConfigValue.ObjectValue(
        mapOf(
            "path" to ConfigValue.StringValue(file.path),
            "absolutePath" to ConfigValue.StringValue(file.absolutePath),
            "canonicalPath" to ConfigValue.StringValue(runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)),
            "name" to ConfigValue.StringValue(file.name),
            "extension" to ConfigValue.StringValue(file.extension),
            "exists" to ConfigValue.BooleanValue(exists),
            "isFile" to ConfigValue.BooleanValue(file.isFile),
            "isDirectory" to ConfigValue.BooleanValue(file.isDirectory),
            "isHidden" to ConfigValue.BooleanValue(file.isHidden),
            "sizeBytes" to ConfigValue.NumberValue(if (exists && file.isFile) file.length().toDouble() else 0.0),
            "lastModifiedEpochMs" to ConfigValue.NumberValue(if (exists) file.lastModified().toDouble() else 0.0),
            "canRead" to ConfigValue.BooleanValue(exists && file.canRead()),
            "canWrite" to ConfigValue.BooleanValue(exists && file.canWrite()),
            "canExecute" to ConfigValue.BooleanValue(exists && file.canExecute()),
        )
    )
}
