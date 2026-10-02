package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class AndroidArchiveFeaturePack : FeaturePack {
    override val id: String = "android.archive"

    override fun install(registry: FeatureRegistry) {
        registerZip(registry)
        registerUnzip(registry)
        registerHash(registry)
        registerMetadata(registry)
        registerSizeRange(registry, FeatureKind.STATE, "file.state.size_range")
        registerSizeRange(registry, FeatureKind.CONDITION, "file.condition.size_range")
    }

    private fun registerZip(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("file.archive.zip"), FeatureKind.ACTION,
                "Create ZIP archive", "Compress an accessible file or directory tree into a ZIP archive",
                FeatureCategory.FILE,
                fields = listOf(
                    FieldSchema.Text("source", "Source path", true),
                    FieldSchema.Text("destination", "Destination ZIP path", true),
                    FieldSchema.Toggle("includeRoot", "Include source directory name"),
                    FieldSchema.Toggle("overwrite", "Overwrite destination"),
                ),
                keywords = setOf("zip", "archive", "compress", "backup"), ownerPackId = id,
            )
        ) { feature, ctx -> withContext(Dispatchers.IO) {
            val source = File(feature.config.string("source").resolveVariables(ctx.variables))
            val destination = File(feature.config.string("destination").resolveVariables(ctx.variables))
            runCatching {
                require(source.exists()) { "Source does not exist" }
                require(destination.extension.equals("zip", ignoreCase = true)) { "Destination must be a ZIP file" }
                if (destination.exists() && !feature.config.boolean("overwrite")) error("Destination already exists")
                destination.parentFile?.mkdirs()
                val count = createZipArchive(source, destination, feature.config.boolean("includeRoot", true))
                ActionExecutionResult(true, ConfigValue.NumberValue(count.toDouble()))
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        } }
    }

    private fun registerUnzip(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("file.archive.unzip"), FeatureKind.ACTION,
                "Extract ZIP archive", "Extract a ZIP archive with path-traversal and extracted-size protection",
                FeatureCategory.FILE,
                fields = listOf(
                    FieldSchema.Text("source", "ZIP file path", true),
                    FieldSchema.Text("destination", "Destination directory", true),
                    FieldSchema.Toggle("overwrite", "Overwrite existing files"),
                    FieldSchema.Number("maxExtractedMb", "Maximum extracted MB", min = 1.0, max = 10240.0),
                ),
                keywords = setOf("zip", "extract", "unzip", "archive", "backup"), ownerPackId = id,
            )
        ) { feature, ctx -> withContext(Dispatchers.IO) {
            val source = File(feature.config.string("source").resolveVariables(ctx.variables))
            val destination = File(feature.config.string("destination").resolveVariables(ctx.variables))
            val maxMb = feature.config["maxExtractedMb"].numberOrNull()?.coerceIn(1.0, 10240.0) ?: 1024.0
            runCatching {
                require(source.isFile) { "ZIP file does not exist" }
                val result = extractZipArchive(
                    source,
                    destination,
                    overwrite = feature.config.boolean("overwrite"),
                    maxExtractedBytes = (maxMb * 1024.0 * 1024.0).toLong(),
                )
                ActionExecutionResult(true, ConfigValue.ObjectValue(mapOf(
                    "entries" to ConfigValue.NumberValue(result.entries.toDouble()),
                    "bytes" to ConfigValue.NumberValue(result.bytes.toDouble()),
                )))
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        } }
    }

    private fun registerHash(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("file.hash"), FeatureKind.ACTION,
                "Hash file", "Calculate MD5, SHA-1 or SHA-256 for an accessible file",
                FeatureCategory.FILE,
                fields = listOf(
                    FieldSchema.Text("path", "File path", true),
                    FieldSchema.Choice("algorithm", "Algorithm", true, listOf("MD5", "SHA-1", "SHA-256")),
                    FieldSchema.Variable("resultVariable", "Store hash in variable", true),
                ),
                keywords = setOf("hash", "md5", "sha", "checksum", "file"), ownerPackId = id,
            )
        ) { feature, ctx -> withContext(Dispatchers.IO) {
            runCatching {
                val file = File(feature.config.string("path").resolveVariables(ctx.variables))
                require(file.isFile) { "File does not exist" }
                val algorithm = feature.config.string("algorithm", "SHA-256")
                require(algorithm in setOf("MD5", "SHA-1", "SHA-256")) { "Unsupported hash algorithm" }
                val hash = hashFile(file, algorithm)
                val output = ConfigValue.StringValue(hash)
                ctx.variables.set(feature.config.string("resultVariable"), output)
                ActionExecutionResult(true, output)
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        } }
    }

    private fun registerMetadata(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("file.metadata"), FeatureKind.ACTION,
                "Get file metadata", "Read common metadata for an accessible file or directory into an object variable",
                FeatureCategory.FILE,
                fields = listOf(
                    FieldSchema.Text("path", "Path", true),
                    FieldSchema.Variable("resultVariable", "Store object in variable", true),
                ),
                keywords = setOf("file", "metadata", "size", "modified", "directory"), ownerPackId = id,
            )
        ) { feature, ctx -> withContext(Dispatchers.IO) {
            runCatching {
                val file = File(feature.config.string("path").resolveVariables(ctx.variables))
                require(file.exists()) { "Path does not exist" }
                val output = fileMetadata(file)
                ctx.variables.set(feature.config.string("resultVariable"), output)
                ActionExecutionResult(true, output)
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        } }
    }

    private fun registerSizeRange(registry: FeatureRegistry, kind: FeatureKind, typeId: String) {
        val descriptor = FeatureDescriptor(
            FeatureId(typeId), kind,
            "File or directory size", "Check whether an accessible file or directory tree is within a byte-size range",
            FeatureCategory.FILE,
            fields = listOf(
                FieldSchema.Text("path", "Path", true),
                FieldSchema.Number("minBytes", "Minimum bytes", min = 0.0),
                FieldSchema.Number("maxBytes", "Maximum bytes", min = 0.0),
            ),
            keywords = setOf("file", "directory", "size", "bytes"), ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, ctx -> withContext(Dispatchers.IO) {
            val file = File(feature.config.string("path").resolveVariables(ctx.variables))
            if (!file.exists()) return@withContext false
            val min = feature.config["minBytes"].numberOrNull()?.coerceAtLeast(0.0) ?: 0.0
            val max = feature.config["maxBytes"].numberOrNull()?.coerceAtLeast(0.0) ?: Double.MAX_VALUE
            if (max < min) return@withContext false
            pathSize(file).toDouble() in min..max
        } }
        if (kind == FeatureKind.STATE) registry.registerState(descriptor, evaluator) else registry.registerCondition(descriptor, evaluator)
    }
}

internal data class ZipExtractionResult(val entries: Int, val bytes: Long)

internal fun createZipArchive(source: File, destination: File, includeRoot: Boolean): Int {
    val destinationCanonical = destination.canonicalFile
    val entries = collectArchiveFiles(source, destinationCanonical)
    require(entries.size <= MAX_ARCHIVE_ENTRIES) { "Too many archive entries" }
    ZipOutputStream(BufferedOutputStream(FileOutputStream(destination))).use { zip ->
        for (file in entries) {
            val relative = archiveRelativePath(source, file, includeRoot)
            if (relative.isBlank()) continue
            if (file.isDirectory) {
                zip.putNextEntry(ZipEntry(relative.trimEnd('/') + "/"))
                zip.closeEntry()
            } else {
                zip.putNextEntry(ZipEntry(relative.replace(File.separatorChar, '/')))
                BufferedInputStream(FileInputStream(file)).use { input -> input.copyTo(zip, DEFAULT_BUFFER_SIZE) }
                zip.closeEntry()
            }
        }
    }
    return entries.size
}

internal fun extractZipArchive(source: File, destination: File, overwrite: Boolean, maxExtractedBytes: Long): ZipExtractionResult {
    require(maxExtractedBytes > 0) { "Invalid extraction limit" }
    destination.mkdirs()
    val root = destination.canonicalFile
    var entries = 0
    var bytes = 0L
    ZipInputStream(BufferedInputStream(FileInputStream(source))).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            entries++
            require(entries <= MAX_ARCHIVE_ENTRIES) { "Too many archive entries" }
            val target = safeZipTarget(root, entry.name)
            if (entry.isDirectory) {
                target.mkdirs()
            } else {
                if (target.exists() && !overwrite) error("Destination file already exists")
                target.parentFile?.mkdirs()
                BufferedOutputStream(FileOutputStream(target, false)).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        bytes += read
                        require(bytes <= maxExtractedBytes) { "Extracted data exceeds limit" }
                        output.write(buffer, 0, read)
                    }
                }
            }
            zip.closeEntry()
        }
    }
    return ZipExtractionResult(entries, bytes)
}

internal fun safeZipTarget(root: File, entryName: String): File {
    require(entryName.isNotBlank() && !entryName.startsWith('/') && !entryName.startsWith('\\')) { "Invalid ZIP entry path" }
    val target = File(root, entryName).canonicalFile
    val rootPath = root.canonicalPath
    require(target.canonicalPath == rootPath || target.canonicalPath.startsWith(rootPath + File.separator)) { "ZIP entry escapes destination" }
    return target
}

internal fun hashFile(file: File, algorithm: String): String {
    val digest = MessageDigest.getInstance(algorithm)
    BufferedInputStream(FileInputStream(file)).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun fileMetadata(file: File): ConfigValue.ObjectValue = ConfigValue.ObjectValue(
    mapOf(
        "name" to ConfigValue.StringValue(file.name),
        "path" to ConfigValue.StringValue(file.absolutePath),
        "parent" to ConfigValue.StringValue(file.parent.orEmpty()),
        "extension" to ConfigValue.StringValue(file.extension),
        "sizeBytes" to ConfigValue.NumberValue(if (file.isFile) file.length().toDouble() else pathSize(file).toDouble()),
        "lastModified" to ConfigValue.NumberValue(file.lastModified().toDouble()),
        "file" to ConfigValue.BooleanValue(file.isFile),
        "directory" to ConfigValue.BooleanValue(file.isDirectory),
        "hidden" to ConfigValue.BooleanValue(file.isHidden),
        "readable" to ConfigValue.BooleanValue(file.canRead()),
        "writable" to ConfigValue.BooleanValue(file.canWrite()),
    )
)

internal fun pathSize(file: File): Long {
    if (file.isFile) return file.length()
    if (!file.isDirectory || runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)) return 0L
    var total = 0L
    file.listFiles().orEmpty().forEach { child ->
        total = safeAdd(total, pathSize(child))
    }
    return total
}

private fun collectArchiveFiles(source: File, destination: File): List<File> {
    val result = ArrayList<File>()
    fun visit(file: File) {
        if (file.canonicalFile == destination) return
        if (runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)) return
        result += file
        require(result.size <= MAX_ARCHIVE_ENTRIES) { "Too many archive entries" }
        if (file.isDirectory) file.listFiles().orEmpty().sortedBy { it.name }.forEach(::visit)
    }
    visit(source)
    return result
}

private fun archiveRelativePath(source: File, file: File, includeRoot: Boolean): String {
    if (source.isFile) return source.name
    val relative = file.relativeTo(source).path
    return if (includeRoot) {
        if (relative.isBlank()) source.name else source.name + File.separator + relative
    } else relative
}

private fun safeAdd(a: Long, b: Long): Long = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
private const val MAX_ARCHIVE_ENTRIES = 10_000
