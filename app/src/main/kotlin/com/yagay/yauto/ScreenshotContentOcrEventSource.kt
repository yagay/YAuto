package com.yagay.yauto

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.storage.WorkspaceRepository
import com.yagay.yauto.platform.accessibility.AccessibilityRuntimeBridge
import com.yagay.yauto.platform.android.AndroidEventSource
import com.yagay.yauto.platform.android.RuntimeEventEmitter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Screenshot-pixel OCR rather than Accessibility node text. This is only started by a
 * workspace-gated source when at least one enabled screenshot-content trigger exists.
 *
 * Per-query rising edges prevent unrelated changes (e.g. the system clock) from
 * causing every screenshot-content rule to run repeatedly.
 */
class ScreenshotContentOcrEventSource(
    private val workspace: WorkspaceRepository,
) : AndroidEventSource {
    override val id: String = "android.screenshot.content.ocr"
    private val running = AtomicBoolean(false)
    private var scope: CoroutineScope? = null

    override fun start(emitter: RuntimeEventEmitter) {
        if (!running.compareAndSet(false, true)) return
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = newScope
        newScope.launch {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            var previouslyVisible = emptySet<ScreenshotContentWatch>()
            try {
                while (isActive) {
                    try {
                        val watches = screenshotWatches(workspace)
                        if (watches.isEmpty()) {
                            previouslyVisible = emptySet()
                        } else {
                            val bitmap = withTimeoutOrNull(7_000L) {
                                AccessibilityRuntimeBridge.captureScreenshot()
                            }
                            if (bitmap != null) {
                                val recognized = try {
                                    recognizeScreenshotText(recognizer, bitmap)
                                } finally {
                                    bitmap.recycle()
                                }
                                if (recognized != null) {
                                    val visible = watches.filterTo(mutableSetOf()) { watch ->
                                        recognized.contains(watch.text, ignoreCase = watch.ignoreCase)
                                    }
                                    (visible - previouslyVisible).forEach { watch ->
                                        emitter.emit(
                                            RuntimeEvent(
                                                typeId = "android.event.screenshot_content",
                                                payload = mapOf(
                                                    "match" to ConfigValue.StringValue(watch.text),
                                                    "ignoreCase" to ConfigValue.BooleanValue(watch.ignoreCase),
                                                ),
                                                source = id,
                                            )
                                        )
                                    }
                                    previouslyVisible = visible
                                }
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // A screenshot can be unavailable on the lockscreen or protected windows.
                        // Leave previous matches untouched to prevent spurious rising-edge triggers.
                    }
                    delay(10_000L)
                }
            } finally {
                recognizer.close()
            }
        }
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        scope?.cancel()
        scope = null
    }
}

private data class ScreenshotContentWatch(
    val text: String,
    val ignoreCase: Boolean,
)

private suspend fun screenshotWatches(workspace: WorkspaceRepository): Set<ScreenshotContentWatch> =
    workspace.load().automations.asSequence()
        .filter { it.enabled }
        .flatMap { it.activation.events.asSequence() }
        .filter { it.typeId == "android.event.screenshot_content" }
        .mapNotNull { feature ->
            val query = feature.config.string("textContains").trim()
            if (query.isBlank()) null else ScreenshotContentWatch(query, feature.config.boolean("ignoreCase", true))
        }
        .toSet()

private suspend fun recognizeScreenshotText(
    recognizer: TextRecognizer,
    bitmap: Bitmap,
): String? = suspendCancellableCoroutine { continuation ->
    recognizer.process(InputImage.fromBitmap(bitmap, 0))
        .addOnCompleteListener { task ->
            if (continuation.isActive) {
                continuation.resume(if (task.isSuccessful) task.result.text else null)
            }
        }
}
