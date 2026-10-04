package com.yagay.yauto.platform.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/** OCR, QR and lightweight image-analysis primitives for visual automation chains. */
class AndroidVisionFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.vision"
    private val context = context.applicationContext

    override fun install(registry: FeatureRegistry) {
        registerQrGenerate(registry)
        registerQrDecode(registry)
        registerOcr(registry)
        registerCrop(registry)
        registerPixel(registry)
        registerColorFind(registry)
        registerImageMatch(registry)
    }

    private fun registerQrGenerate(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.qr.generate"), FeatureKind.ACTION,
                "Generate QR code", "Generate a QR code PNG from text",
                FeatureCategory.ADVANCED,
                fields = listOf(
                    FieldSchema.Text("text", "QR content", true, multiline = true),
                    FieldSchema.Number("size", "Image size pixels", min = 128.0, max = 2048.0),
                    FieldSchema.Text("fileName", "PNG file name"),
                    FieldSchema.Variable("resultVariable", "Store PNG path"),
                ),
                fieldBehaviors = mapOf("text" to FieldBehavior(supportsVariables = true)),
                keywords = setOf("qr", "barcode", "generate", "png"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val text = feature.config.string("text").resolveVariables(ctx.variables)
            if (text.isBlank()) return@registerAction invalid("QR content is empty")
            val size = (feature.config["size"].numberOrNull() ?: 512.0).toInt().coerceIn(128, 2048)
            val path = outputPath(feature.config.string("fileName"), "qr-${System.currentTimeMillis()}", ".png")
                ?: return@registerAction invalid("Invalid output file name")
            val output = runCatching {
                withContext(Dispatchers.Default) {
                    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
                    val pixels = IntArray(size * size)
                    for (y in 0 until size) for (x in 0 until size) {
                        pixels[y * size + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                    }
                    val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
                    File(path).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                    ConfigValue.StringValue(path)
                }
            }.getOrElse { return@registerAction failure(it) }
            store(feature.config.string("resultVariable"), output, ctx)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerQrDecode(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.qr.decode"), FeatureKind.ACTION,
                "Decode QR or barcode", "Decode a QR code or supported barcode from an image",
                FeatureCategory.ADVANCED,
                fields = listOf(
                    FieldSchema.Text("image", "Image path or content URI", true),
                    FieldSchema.Variable("resultVariable", "Store decoded object", true),
                ),
                fieldBehaviors = mapOf("image" to FieldBehavior(supportsVariables = true)),
                keywords = setOf("qr", "barcode", "decode", "scan", "image"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val bitmap = loadBitmap(feature.config.string("image").resolveVariables(ctx.variables))
                ?: return@registerAction invalid("Unable to read image")
            val output = runCatching {
                withContext(Dispatchers.Default) {
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val result = MultiFormatReader().decode(
                        BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))
                    )
                    ConfigValue.ObjectValue(
                        mapOf(
                            "text" to ConfigValue.StringValue(result.text.orEmpty()),
                            "format" to ConfigValue.StringValue(result.barcodeFormat.name),
                            "points" to ConfigValue.ListValue(result.resultPoints.orEmpty().map { point ->
                                ConfigValue.ObjectValue(
                                    mapOf(
                                        "x" to ConfigValue.NumberValue(point.x.toDouble()),
                                        "y" to ConfigValue.NumberValue(point.y.toDouble()),
                                    )
                                )
                            }),
                        )
                    )
                }
            }.getOrElse { bitmap.recycle(); return@registerAction failure(it) }
            bitmap.recycle()
            store(feature.config.string("resultVariable"), output, ctx)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerOcr(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.ocr.text"), FeatureKind.ACTION,
                "Recognize text in image", "Run on-device OCR over an image and return text blocks with bounding boxes",
                FeatureCategory.ADVANCED,
                fields = listOf(
                    FieldSchema.Text("image", "Image path or content URI", true),
                    FieldSchema.Variable("resultVariable", "Store OCR object", true),
                ),
                fieldBehaviors = mapOf("image" to FieldBehavior(supportsVariables = true)),
                keywords = setOf("ocr", "text recognition", "screen", "image", "vision"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val bitmap = loadBitmap(feature.config.string("image").resolveVariables(ctx.variables))
                ?: return@registerAction invalid("Unable to read image")
            val result = runCatching { recognizeText(bitmap) }.getOrElse {
                bitmap.recycle()
                return@registerAction failure(it)
            }
            bitmap.recycle()
            val output = ConfigValue.ObjectValue(
                mapOf(
                    "text" to ConfigValue.StringValue(result.text),
                    "blocks" to ConfigValue.ListValue(result.textBlocks.map { block ->
                        val box = block.boundingBox
                        ConfigValue.ObjectValue(
                            mapOf(
                                "text" to ConfigValue.StringValue(block.text),
                                "left" to ConfigValue.NumberValue((box?.left ?: -1).toDouble()),
                                "top" to ConfigValue.NumberValue((box?.top ?: -1).toDouble()),
                                "right" to ConfigValue.NumberValue((box?.right ?: -1).toDouble()),
                                "bottom" to ConfigValue.NumberValue((box?.bottom ?: -1).toDouble()),
                            )
                        )
                    }),
                )
            )
            store(feature.config.string("resultVariable"), output, ctx)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerCrop(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.image.crop"), FeatureKind.ACTION,
                "Crop image", "Crop a rectangular image region to a new PNG file",
                FeatureCategory.ADVANCED,
                fields = listOf(
                    FieldSchema.Text("image", "Image path or content URI", true),
                    FieldSchema.Number("x", "Left X", true, min = 0.0),
                    FieldSchema.Number("y", "Top Y", true, min = 0.0),
                    FieldSchema.Number("width", "Width", true, min = 1.0),
                    FieldSchema.Number("height", "Height", true, min = 1.0),
                    FieldSchema.Text("fileName", "Output PNG file name"),
                    FieldSchema.Variable("resultVariable", "Store output path"),
                ),
                keywords = setOf("crop", "region", "screenshot", "image", "vision"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val bitmap = loadBitmap(feature.config.string("image").resolveVariables(ctx.variables))
                ?: return@registerAction invalid("Unable to read image")
            val x = feature.config["x"].numberOrNull()?.toInt() ?: 0
            val y = feature.config["y"].numberOrNull()?.toInt() ?: 0
            val width = feature.config["width"].numberOrNull()?.toInt() ?: 0
            val height = feature.config["height"].numberOrNull()?.toInt() ?: 0
            if (x < 0 || y < 0 || width <= 0 || height <= 0 || x + width > bitmap.width || y + height > bitmap.height) {
                bitmap.recycle()
                return@registerAction invalid("Crop rectangle is outside the image")
            }
            val path = outputPath(feature.config.string("fileName"), "crop-${System.currentTimeMillis()}", ".png")
                ?: run { bitmap.recycle(); return@registerAction invalid("Invalid output file name") }
            val output = runCatching {
                val cropped = Bitmap.createBitmap(bitmap, x, y, width, height)
                File(path).outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }
                cropped.recycle()
                ConfigValue.StringValue(path)
            }.getOrElse { bitmap.recycle(); return@registerAction failure(it) }
            bitmap.recycle()
            store(feature.config.string("resultVariable"), output, ctx)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerPixel(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.image.pixel.get"), FeatureKind.ACTION,
                "Get pixel color", "Read one pixel and return ARGB/RGB channel values",
                FeatureCategory.ADVANCED,
                fields = listOf(
                    FieldSchema.Text("image", "Image path or content URI", true),
                    FieldSchema.Number("x", "X", true, min = 0.0),
                    FieldSchema.Number("y", "Y", true, min = 0.0),
                    FieldSchema.Variable("resultVariable", "Store color object", true),
                ),
                keywords = setOf("pixel", "color", "rgb", "screenshot", "vision"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val bitmap = loadBitmap(feature.config.string("image").resolveVariables(ctx.variables))
                ?: return@registerAction invalid("Unable to read image")
            val x = feature.config["x"].numberOrNull()?.toInt() ?: -1
            val y = feature.config["y"].numberOrNull()?.toInt() ?: -1
            if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) {
                bitmap.recycle()
                return@registerAction invalid("Pixel coordinates are outside the image")
            }
            val color = bitmap.getPixel(x, y)
            bitmap.recycle()
            val output = colorValue(x, y, color, 0.0)
            store(feature.config.string("resultVariable"), output, ctx)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerColorFind(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.image.color.find"), FeatureKind.ACTION,
                "Find color in image", "Find the first sampled pixel close to a target RGB color",
                FeatureCategory.ADVANCED,
                fields = listOf(
                    FieldSchema.Text("image", "Image path or content URI", true),
                    FieldSchema.Text("color", "Target color (#RRGGBB)", true),
                    FieldSchema.Number("tolerance", "Per-channel tolerance", min = 0.0, max = 255.0),
                    FieldSchema.Number("step", "Search step pixels", min = 1.0, max = 32.0),
                    FieldSchema.Variable("resultVariable", "Store match object", true),
                ),
                keywords = setOf("color", "pixel", "find", "screen", "image", "vision"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val bitmap = loadBitmap(feature.config.string("image").resolveVariables(ctx.variables))
                ?: return@registerAction invalid("Unable to read image")
            val target = parseColor(feature.config.string("color"))
                ?: run { bitmap.recycle(); return@registerAction invalid("Invalid target color") }
            val tolerance = (feature.config["tolerance"].numberOrNull() ?: 16.0).toInt().coerceIn(0, 255)
            val step = (feature.config["step"].numberOrNull() ?: 1.0).toInt().coerceIn(1, 32)
            val output = withContext(Dispatchers.Default) {
                var bestX = -1
                var bestY = -1
                var bestDistance = Double.MAX_VALUE
                outer@ for (y in 0 until bitmap.height step step) {
                    for (x in 0 until bitmap.width step step) {
                        val color = bitmap.getPixel(x, y)
                        val distance = colorDistance(color, target)
                        if (distance < bestDistance) {
                            bestDistance = distance
                            bestX = x
                            bestY = y
                        }
                        if (channelMatch(color, target, tolerance)) break@outer
                    }
                }
                val found = bestX >= 0 && channelMatch(bitmap.getPixel(bestX, bestY), target, tolerance)
                ConfigValue.ObjectValue(
                    mapOf(
                        "found" to ConfigValue.BooleanValue(found),
                        "x" to ConfigValue.NumberValue(bestX.toDouble()),
                        "y" to ConfigValue.NumberValue(bestY.toDouble()),
                        "distance" to ConfigValue.NumberValue(bestDistance),
                    )
                )
            }
            bitmap.recycle()
            store(feature.config.string("resultVariable"), output, ctx)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerImageMatch(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.image.match"), FeatureKind.ACTION,
                "Find image template", "Search a source image for a template using sampled RGB distance",
                FeatureCategory.ADVANCED,
                fields = listOf(
                    FieldSchema.Text("image", "Source image path or content URI", true),
                    FieldSchema.Text("template", "Template image path or content URI", true),
                    FieldSchema.Number("searchStep", "Search step pixels", min = 1.0, max = 32.0),
                    FieldSchema.Number("sampleStep", "Template sample step", min = 1.0, max = 16.0),
                    FieldSchema.Number("maxAverageDistance", "Maximum average RGB distance", min = 0.0, max = 255.0),
                    FieldSchema.Variable("resultVariable", "Store match object", true),
                ),
                keywords = setOf("template", "image matching", "screen recognition", "find image", "vision"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val source = loadBitmap(feature.config.string("image").resolveVariables(ctx.variables))
                ?: return@registerAction invalid("Unable to read source image")
            val template = loadBitmap(feature.config.string("template").resolveVariables(ctx.variables))
                ?: run { source.recycle(); return@registerAction invalid("Unable to read template image") }
            if (template.width > source.width || template.height > source.height || source.width * source.height > 20_000_000) {
                source.recycle(); template.recycle()
                return@registerAction invalid("Image dimensions are not supported")
            }
            val searchStep = (feature.config["searchStep"].numberOrNull() ?: 2.0).toInt().coerceIn(1, 32)
            val sampleStep = (feature.config["sampleStep"].numberOrNull() ?: 2.0).toInt().coerceIn(1, 16)
            val threshold = (feature.config["maxAverageDistance"].numberOrNull() ?: 24.0).coerceIn(0.0, 255.0)
            val output = withContext(Dispatchers.Default) {
                val match = bestTemplateMatch(source, template, searchStep, sampleStep)
                ConfigValue.ObjectValue(
                    mapOf(
                        "matched" to ConfigValue.BooleanValue(match.distance <= threshold),
                        "x" to ConfigValue.NumberValue(match.x.toDouble()),
                        "y" to ConfigValue.NumberValue(match.y.toDouble()),
                        "averageDistance" to ConfigValue.NumberValue(match.distance),
                        "templateWidth" to ConfigValue.NumberValue(template.width.toDouble()),
                        "templateHeight" to ConfigValue.NumberValue(template.height.toDouble()),
                    )
                )
            }
            source.recycle(); template.recycle()
            store(feature.config.string("resultVariable"), output, ctx)
            ActionExecutionResult(true, output)
        }
    }

    private suspend fun recognizeText(bitmap: Bitmap): com.google.mlkit.vision.text.Text =
        suspendCancellableCoroutine { continuation ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    recognizer.close()
                    if (continuation.isActive) continuation.resume(result)
                }
                .addOnFailureListener { error ->
                    recognizer.close()
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
        }

    private fun loadBitmap(raw: String): Bitmap? = runCatching {
        openImage(raw.trim())?.use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    private fun openImage(raw: String): InputStream? {
        if (raw.startsWith("content://")) return context.contentResolver.openInputStream(Uri.parse(raw))
        if (raw.startsWith("file://")) return FileInputStream(Uri.parse(raw).path ?: return null)
        return FileInputStream(File(raw))
    }

    private fun outputPath(rawName: String, fallbackBase: String, extension: String): String? {
        val name = normalizedFileName(rawName, fallbackBase, extension) ?: return null
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "Vision").apply { mkdirs() }
        return File(dir, name).absolutePath
    }

    private fun store(name: String, value: ConfigValue, ctx: FeatureExecutionContext) {
        name.trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, value) }
    }

    private fun invalid(message: String) = ActionExecutionResult(false, message = userText("feature.operation_failed", message))
    private fun failure(error: Throwable) = ActionExecutionResult(false, message = userText("feature.operation_failed", error.message ?: error.javaClass.simpleName))
}

private data class TemplateMatch(val x: Int, val y: Int, val distance: Double)

private fun bestTemplateMatch(source: Bitmap, template: Bitmap, searchStep: Int, sampleStep: Int): TemplateMatch {
    var best = TemplateMatch(0, 0, Double.MAX_VALUE)
    val maxX = source.width - template.width
    val maxY = source.height - template.height
    for (top in 0..maxY step searchStep) {
        for (left in 0..maxX step searchStep) {
            var total = 0.0
            var count = 0
            var y = 0
            while (y < template.height) {
                var x = 0
                while (x < template.width) {
                    total += colorDistance(source.getPixel(left + x, top + y), template.getPixel(x, y))
                    count++
                    x += sampleStep
                }
                y += sampleStep
            }
            val average = if (count == 0) 255.0 else total / count
            if (average < best.distance) best = TemplateMatch(left, top, average)
            if (average == 0.0) return best
        }
    }
    return best
}

private fun colorDistance(first: Int, second: Int): Double =
    (abs(Color.red(first) - Color.red(second)) + abs(Color.green(first) - Color.green(second)) + abs(Color.blue(first) - Color.blue(second))) / 3.0

private fun channelMatch(first: Int, second: Int, tolerance: Int): Boolean =
    abs(Color.red(first) - Color.red(second)) <= tolerance &&
        abs(Color.green(first) - Color.green(second)) <= tolerance &&
        abs(Color.blue(first) - Color.blue(second)) <= tolerance

private fun parseColor(raw: String): Int? = runCatching {
    val value = raw.trim()
    when {
        Regex("#[0-9A-Fa-f]{6}").matches(value) -> Color.parseColor(value)
        Regex("#[0-9A-Fa-f]{8}").matches(value) -> Color.parseColor(value)
        else -> null
    }
}.getOrNull()

private fun colorValue(x: Int, y: Int, color: Int, distance: Double): ConfigValue.ObjectValue =
    ConfigValue.ObjectValue(
        mapOf(
            "x" to ConfigValue.NumberValue(x.toDouble()),
            "y" to ConfigValue.NumberValue(y.toDouble()),
            "argb" to ConfigValue.StringValue(String.format("#%08X", color)),
            "alpha" to ConfigValue.NumberValue(Color.alpha(color).toDouble()),
            "red" to ConfigValue.NumberValue(Color.red(color).toDouble()),
            "green" to ConfigValue.NumberValue(Color.green(color).toDouble()),
            "blue" to ConfigValue.NumberValue(Color.blue(color).toDouble()),
            "distance" to ConfigValue.NumberValue(distance),
        )
    )
