package com.yagay.yauto.platform.android

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.URLConnection
import java.util.UUID

class AndroidContentUtilityFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.content_utility"
    private val context = context.applicationContext
    private val wallpaperManager = WallpaperManager.getInstance(context.applicationContext)

    override fun install(registry: FeatureRegistry) {
        registerOpenFile(registry)
        registerFilePicker(registry)
        registerPhotoPicker(registry)
        registerShareFile(registry)
        registerSetWallpaper(registry)
        registerClearWallpaper(registry)
    }

    private fun registerOpenFile(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.file.open"), FeatureKind.ACTION,
                "Open file", "Open an accessible file in another Android app through a secure content URI",
                FeatureCategory.FILE,
                fields = listOf(
                    FieldSchema.Text("path", "File path", true),
                    FieldSchema.Text("mimeType", "MIME type (blank = automatic)"),
                ),
                keywords = setOf("open file", "viewer", "mime", "content uri"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val file = resolveReadableFile(feature.config.string("path").resolveVariables(ctx.variables))
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.file_not_found"))
            val mime = feature.config.string("mimeType").trim().ifBlank { guessMimeType(file.name) }
            runCatching {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
                ActionExecutionResult(true, ConfigValue.StringValue(uri.toString()))
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }
    }

    private fun registerFilePicker(registry: FeatureRegistry) {
        registerPicker(
            registry = registry,
            featureId = "file.pick",
            title = "Pick file",
            description = "Open Android's document picker and wait for one or more selected content URIs",
            mode = "file",
            includeMime = true,
            keywords = setOf("pick file", "browse files", "document picker", "tasker"),
        )
    }

    private fun registerPhotoPicker(registry: FeatureRegistry) {
        registerPicker(
            registry = registry,
            featureId = "android.photo.pick",
            title = "Pick photos",
            description = "Open Android's image picker and wait for one or more selected image URIs",
            mode = "photo",
            includeMime = false,
            keywords = setOf("pick photos", "photo picker", "images", "tasker"),
        )
    }

    private fun registerPicker(
        registry: FeatureRegistry,
        featureId: String,
        title: String,
        description: String,
        mode: String,
        includeMime: Boolean,
        keywords: Set<String>,
    ) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId(featureId),
                FeatureKind.ACTION,
                title,
                description,
                FeatureCategory.FILE,
                fields = buildList {
                    if (includeMime) add(FieldSchema.Text("mimeType", "MIME type"))
                    add(FieldSchema.Toggle("multiple", "Allow multiple selections"))
                    add(FieldSchema.Duration("timeoutMs", "Picker timeout"))
                    add(FieldSchema.Variable("resultVariable", "Store picker result object"))
                },
                keywords = keywords,
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val token = UUID.randomUUID().toString()
            val pending = PickerRuntimeBridge.register(token)
            val launched = runCatching {
                context.startActivity(
                    Intent()
                        .setClassName(context.packageName, "com.yagay.yauto.RuntimePickerActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("token", token)
                        .putExtra("mode", mode)
                        .putExtra("multiple", feature.config.boolean("multiple"))
                        .putExtra("mime", feature.config.string("mimeType").resolveVariables(ctx.variables))
                )
                true
            }.getOrDefault(false)
            if (!launched) {
                PickerRuntimeBridge.cancel(token)
                return@registerAction ActionExecutionResult(false, message = userText("feature.picker_open_failed"))
            }
            val timeout = (feature.config["timeoutMs"].numberOrNull() ?: 120_000.0).toLong().coerceIn(1_000L, 600_000L)
            val uris = withTimeoutOrNull(timeout) { pending.await() }
            PickerRuntimeBridge.cancel(token)
            if (uris.isNullOrEmpty()) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.picker_no_selection"))
            }
            val output = ConfigValue.ObjectValue(
                mapOf(
                    "first" to ConfigValue.StringValue(uris.first()),
                    "uris" to ConfigValue.ListValue(uris.map(ConfigValue::StringValue)),
                    "count" to ConfigValue.NumberValue(uris.size.toDouble()),
                )
            )
            feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, output) }
            ActionExecutionResult(true, output)
        }
    }

    private fun registerShareFile(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.file.share"), FeatureKind.ACTION,
                "Share file", "Open Android's share sheet with an accessible file and optional accompanying text",
                FeatureCategory.FILE,
                fields = listOf(
                    FieldSchema.Text("path", "File path", true),
                    FieldSchema.Text("mimeType", "MIME type (blank = automatic)"),
                    FieldSchema.Text("text", "Optional text", multiline = true),
                ),
                keywords = setOf("share file", "send file", "attachment", "mime"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val file = resolveReadableFile(feature.config.string("path").resolveVariables(ctx.variables))
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.file_not_found"))
            val mime = feature.config.string("mimeType").trim().ifBlank { guessMimeType(file.name) }
            val text = feature.config.string("text").resolveVariables(ctx.variables)
            runCatching {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    if (text.isNotBlank()) putExtra(Intent.EXTRA_TEXT, text)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionExecutionResult(true, ConfigValue.StringValue(uri.toString()))
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }
    }

    private fun registerSetWallpaper(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.wallpaper.set"), FeatureKind.ACTION,
                "Set wallpaper from file", "Set the home screen, lock screen, or both wallpapers from a local image file",
                FeatureCategory.DISPLAY,
                fields = listOf(
                    FieldSchema.Text("path", "Image file path", true),
                    FieldSchema.Choice("target", "Wallpaper target", true, listOf("home", "lock", "both")),
                ),
                keywords = setOf("wallpaper", "home screen", "lock screen", "image"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val file = resolveReadableFile(feature.config.string("path").resolveVariables(ctx.variables))
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.file_not_found"))
            val flags = wallpaperFlags(feature.config.string("target", "home"))
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.wallpaper_target_invalid"))
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight.toLong() > MAX_WALLPAPER_PIXELS) return@runCatching false
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@runCatching false
                    try { wallpaperManager.setBitmap(bitmap, null, true, flags) } finally { bitmap.recycle() }
                    true
                }.getOrDefault(false)
            }
            ActionExecutionResult(success, ConfigValue.BooleanValue(success), if (success) null else userText("feature.wallpaper_set_failed"))
        }
    }

    private fun registerClearWallpaper(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.wallpaper.clear"), FeatureKind.ACTION,
                "Clear wallpaper", "Clear the home screen, lock screen, or both wallpapers and return to the system default",
                FeatureCategory.DISPLAY,
                fields = listOf(FieldSchema.Choice("target", "Wallpaper target", true, listOf("home", "lock", "both"))),
                keywords = setOf("wallpaper", "clear", "reset", "home screen", "lock screen"), ownerPackId = id,
            )
        ) { feature, _ ->
            val target = feature.config.string("target", "home")
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    when (target) {
                        "home" -> wallpaperManager.clear(WallpaperManager.FLAG_SYSTEM)
                        "lock" -> wallpaperManager.clear(WallpaperManager.FLAG_LOCK)
                        "both" -> {
                            wallpaperManager.clear(WallpaperManager.FLAG_SYSTEM)
                            wallpaperManager.clear(WallpaperManager.FLAG_LOCK)
                        }
                        else -> return@runCatching false
                    }
                    true
                }.getOrDefault(false)
            }
            ActionExecutionResult(success, ConfigValue.BooleanValue(success), if (success) null else userText("feature.wallpaper_clear_failed"))
        }
    }

    private fun resolveReadableFile(raw: String): File? = runCatching {
        File(raw.trim()).canonicalFile.takeIf { it.isFile && it.canRead() }
    }.getOrNull()

    private companion object {
        const val MAX_WALLPAPER_PIXELS = 50_000_000L
    }
}

internal fun guessMimeType(fileName: String): String =
    URLConnection.guessContentTypeFromName(fileName) ?: "application/octet-stream"

internal fun wallpaperFlags(target: String): Int? = when (target) {
    "home" -> WallpaperManager.FLAG_SYSTEM
    "lock" -> WallpaperManager.FLAG_LOCK
    "both" -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
    else -> null
}
