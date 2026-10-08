package com.yagay.yauto.platform.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.media.CamcorderProfile
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("DEPRECATION")
class AndroidCameraCaptureFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.camera_capture"
    private val context = context.applicationContext

    override fun install(registry: FeatureRegistry) {
        registerPhoto(registry)
        registerVideoStart(registry)
        registerVideoStop(registry)
        registerVideoRecord(registry)
        registerVideoState(registry)
    }

    private fun registerPhoto(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.camera.photo.capture"), FeatureKind.ACTION,
                "Take photo", "Capture a JPEG directly with the selected Android camera",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Choice("camera", "Camera", true, listOf("back", "front")),
                    FieldSchema.Choice("flash", "Flash", true, listOf("off", "on", "auto")),
                    FieldSchema.Toggle("autoFocus", "Auto focus"),
                    FieldSchema.Text("fileName", "JPEG file name"),
                    FieldSchema.Variable("resultVariable", "Store photo path"),
                ),
                accessRequirements = setOf(AccessRequirement.CAMERA),
                keywords = setOf("camera", "take picture", "photo", "jpeg", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (!hasCameraPermission()) return@registerAction ActionExecutionResult(false, message = userText("feature.camera_permission_denied"))
            val path = photoPath(feature.config.string("fileName").resolveVariables(ctx.variables))
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.camera_filename_invalid"))
            val ok = capturePhoto(
                front = feature.config.string("camera", "back") == "front",
                flash = feature.config.string("flash", "off"),
                autoFocus = feature.config.boolean("autoFocus", true),
                path = path,
            )
            if (!ok) return@registerAction ActionExecutionResult(false, message = userText("feature.camera_capture_failed"))
            val output = ConfigValue.StringValue(path)
            feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, output) }
            ActionExecutionResult(true, output)
        }
    }

    private fun registerVideoStart(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.camera.video.start"), FeatureKind.ACTION,
                "Start camera video recording", "Start direct camera video recording until Stop camera video is run",
                FeatureCategory.DEVICE,
                fields = videoFields(includeDuration = false),
                accessRequirements = setOf(AccessRequirement.CAMERA, AccessRequirement.RECORD_AUDIO),
                keywords = setOf("camera", "video", "record", "start", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (!hasCameraPermission() || !hasAudioPermission()) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.camera_microphone_permission_required"))
            }
            val path = videoPath(feature.config.string("fileName").resolveVariables(ctx.variables))
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.camera_filename_invalid"))
            val ok = CameraVideoRuntime.start(
                front = feature.config.string("camera", "back") == "front",
                quality = feature.config.string("quality", "HD"),
                rotation = feature.config.string("rotation", "0").toIntOrNull() ?: 0,
                path = path,
            )
            if (!ok) return@registerAction ActionExecutionResult(false, message = userText("feature.camera_video_start_failed"))
            val output = ConfigValue.StringValue(path)
            feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, output) }
            ActionExecutionResult(true, output)
        }
    }

    private fun registerVideoStop(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.camera.video.stop"), FeatureKind.ACTION,
                "Stop camera video recording", "Stop the active YAuto direct-camera video recording",
                FeatureCategory.DEVICE,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store saved video path")),
                keywords = setOf("camera", "video", "record", "stop"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val path = CameraVideoRuntime.stop()
            val output = ConfigValue.StringValue(path)
            feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, output) }
            ActionExecutionResult(path.isNotBlank(), output, if (path.isNotBlank()) null else userText("feature.camera_video_not_recording"))
        }
    }

    private fun registerVideoRecord(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.camera.video.record"), FeatureKind.ACTION,
                "Record camera video", "Record direct camera video for a bounded duration and return the saved MP4 path",
                FeatureCategory.DEVICE,
                fields = videoFields(includeDuration = true),
                accessRequirements = setOf(AccessRequirement.CAMERA, AccessRequirement.RECORD_AUDIO),
                keywords = setOf("camera", "record video", "duration", "mp4", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (!hasCameraPermission() || !hasAudioPermission()) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.camera_microphone_permission_required"))
            }
            val path = videoPath(feature.config.string("fileName").resolveVariables(ctx.variables))
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.camera_filename_invalid"))
            val started = CameraVideoRuntime.start(
                front = feature.config.string("camera", "back") == "front",
                quality = feature.config.string("quality", "HD"),
                rotation = feature.config.string("rotation", "0").toIntOrNull() ?: 0,
                path = path,
            )
            if (!started) return@registerAction ActionExecutionResult(false, message = userText("feature.camera_video_start_failed"))
            val duration = (feature.config["durationSeconds"].numberOrNull() ?: 30.0).toLong().coerceIn(1L, 1_800L)
            try {
                delay(duration * 1_000L)
            } finally {
                CameraVideoRuntime.stop()
            }
            val output = ConfigValue.StringValue(path)
            feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, output) }
            ActionExecutionResult(File(path).exists() && File(path).length() > 0, output)
        }
    }

    private fun registerVideoState(registry: FeatureRegistry) {
        val evaluator = ConditionEvaluator { feature, _ ->
            CameraVideoRuntime.recording.get() == feature.config.boolean("value", true)
        }
        val state = FeatureDescriptor(
            FeatureId("android.state.camera_video_recording"), FeatureKind.STATE,
            "Camera video recording", "Check whether YAuto camera video recording is active",
            FeatureCategory.DEVICE,
            fields = listOf(FieldSchema.Toggle("value", "Recording")),
            ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(state.copy(id = FeatureId("android.condition.camera_video_recording"), kind = FeatureKind.CONDITION), evaluator)
    }

    private fun videoFields(includeDuration: Boolean): List<FieldSchema> = buildList {
        add(FieldSchema.Choice("camera", "Camera", true, listOf("back", "front")))
        add(FieldSchema.Choice("quality", "Quality", true, listOf("UHD", "FHD", "HD", "SD")))
        add(FieldSchema.Choice("rotation", "Rotation", options = listOf("0", "90", "180", "270")))
        if (includeDuration) add(FieldSchema.Number("durationSeconds", "Duration seconds", min = 1.0, max = 1800.0))
        add(FieldSchema.Text("fileName", "MP4 file name"))
        add(FieldSchema.Variable("resultVariable", "Store video path"))
    }

    private suspend fun capturePhoto(front: Boolean, flash: String, autoFocus: Boolean, path: String): Boolean {
        val result = CompletableDeferred<ByteArray?>()
        var camera: Camera? = null
        var texture: SurfaceTexture? = null
        val opened = withContext(Dispatchers.Main.immediate) {
            runCatching {
                val id = cameraId(front) ?: return@runCatching false
                camera = Camera.open(id)
                texture = SurfaceTexture(0)
                camera!!.setPreviewTexture(texture)
                val parameters = camera!!.parameters
                val requestedFlash = when (flash) {
                    "on" -> Camera.Parameters.FLASH_MODE_ON
                    "auto" -> Camera.Parameters.FLASH_MODE_AUTO
                    else -> Camera.Parameters.FLASH_MODE_OFF
                }
                if (requestedFlash in parameters.supportedFlashModes.orEmpty()) parameters.flashMode = requestedFlash
                if (autoFocus && Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE in parameters.supportedFocusModes.orEmpty()) {
                    parameters.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                }
                camera!!.parameters = parameters
                camera!!.startPreview()
                val shoot = {
                    runCatching {
                        camera!!.takePicture(null, null) { data, _ -> result.complete(data) }
                    }.onFailure { result.complete(null) }
                    Unit
                }
                if (autoFocus && Camera.Parameters.FOCUS_MODE_AUTO in parameters.supportedFocusModes.orEmpty()) {
                    camera!!.autoFocus { _, _ -> shoot() }
                } else {
                    shoot()
                }
                true
            }.getOrDefault(false)
        }
        if (!opened) {
            withContext(Dispatchers.Main.immediate) { releaseCamera(camera, texture) }
            return false
        }
        val bytes = withTimeoutOrNull(30_000L) { result.await() }
        val saved = if (bytes != null) withContext(Dispatchers.IO) {
            runCatching {
                File(path).apply { parentFile?.mkdirs() }.writeBytes(bytes)
                true
            }.getOrDefault(false)
        } else false
        withContext(Dispatchers.Main.immediate) { releaseCamera(camera, texture) }
        return saved
    }

    private fun releaseCamera(camera: Camera?, texture: SurfaceTexture?) {
        runCatching { camera?.stopPreview() }
        runCatching { camera?.release() }
        runCatching { texture?.release() }
    }

    private fun cameraId(front: Boolean): Int? {
        val target = if (front) Camera.CameraInfo.CAMERA_FACING_FRONT else Camera.CameraInfo.CAMERA_FACING_BACK
        val info = Camera.CameraInfo()
        return (0 until Camera.getNumberOfCameras()).firstOrNull { id ->
            Camera.getCameraInfo(id, info)
            info.facing == target
        }
    }

    private fun photoPath(raw: String): String? {
        val name = normalizedFileName(raw, "photo-${System.currentTimeMillis()}", ".jpg") ?: return null
        return File(context.getExternalFilesDir(null) ?: context.filesDir, "Camera/$name").absolutePath
    }

    private fun videoPath(raw: String): String? {
        val name = normalizedFileName(raw, "video-${System.currentTimeMillis()}", ".mp4") ?: return null
        return File(context.getExternalFilesDir(null) ?: context.filesDir, "Camera/$name").absolutePath
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

@Suppress("DEPRECATION")
private object CameraVideoRuntime {
    val recording = AtomicBoolean(false)
    private var camera: Camera? = null
    private var recorder: MediaRecorder? = null
    private var texture: SurfaceTexture? = null
    @Volatile private var path: String = ""

    suspend fun start(front: Boolean, quality: String, rotation: Int, path: String): Boolean =
        withContext(Dispatchers.Main.immediate) {
            if (!recording.compareAndSet(false, true)) return@withContext false
            runCatching {
                val cameraId = findCameraId(front) ?: error("camera unavailable")
                val nextCamera = Camera.open(cameraId)
                val nextTexture = SurfaceTexture(0)
                nextCamera.setPreviewTexture(nextTexture)
                nextCamera.startPreview()
                nextCamera.unlock()

                val profile = bestProfile(cameraId, quality)
                val nextRecorder = MediaRecorder().apply {
                    setCamera(nextCamera)
                    setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                    setVideoSource(MediaRecorder.VideoSource.CAMERA)
                    setProfile(profile)
                    setOrientationHint(rotation.takeIf { it in setOf(0, 90, 180, 270) } ?: 0)
                    File(path).parentFile?.mkdirs()
                    setOutputFile(path)
                    prepare()
                    start()
                }
                camera = nextCamera
                texture = nextTexture
                recorder = nextRecorder
                this@CameraVideoRuntime.path = path
                true
            }.getOrElse {
                cleanup(deleteBroken = true)
                false
            }
        }

    suspend fun stop(): String = withContext(Dispatchers.Main.immediate) {
        if (!recording.get()) return@withContext ""
        val saved = path
        val stopped = runCatching { recorder?.stop(); true }.getOrDefault(false)
        cleanup(deleteBroken = !stopped)
        if (stopped) saved else ""
    }

    private fun cleanup(deleteBroken: Boolean) {
        val brokenPath = path
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { camera?.reconnect() }
        runCatching { camera?.stopPreview() }
        runCatching { camera?.release() }
        camera = null
        runCatching { texture?.release() }
        texture = null
        path = ""
        recording.set(false)
        if (deleteBroken && brokenPath.isNotBlank()) runCatching { File(brokenPath).delete() }
    }

    private fun findCameraId(front: Boolean): Int? {
        val target = if (front) Camera.CameraInfo.CAMERA_FACING_FRONT else Camera.CameraInfo.CAMERA_FACING_BACK
        val info = Camera.CameraInfo()
        return (0 until Camera.getNumberOfCameras()).firstOrNull { id ->
            Camera.getCameraInfo(id, info)
            info.facing == target
        }
    }

    private fun bestProfile(cameraId: Int, quality: String): CamcorderProfile {
        val preferred = when (quality) {
            "UHD" -> CamcorderProfile.QUALITY_2160P
            "FHD" -> CamcorderProfile.QUALITY_1080P
            "SD" -> CamcorderProfile.QUALITY_480P
            else -> CamcorderProfile.QUALITY_720P
        }
        val candidates = listOf(
            preferred,
            CamcorderProfile.QUALITY_1080P,
            CamcorderProfile.QUALITY_720P,
            CamcorderProfile.QUALITY_480P,
            CamcorderProfile.QUALITY_HIGH,
        ).distinct()
        val selected = candidates.firstOrNull { CamcorderProfile.hasProfile(cameraId, it) }
            ?: CamcorderProfile.QUALITY_LOW
        return CamcorderProfile.get(cameraId, selected)
    }
}
