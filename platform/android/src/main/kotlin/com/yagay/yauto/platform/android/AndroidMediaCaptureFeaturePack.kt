package com.yagay.yauto.platform.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class AudioRecordingService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.audio_record_channel_name), NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(applicationInfo.loadLabel(packageManager))
            .setContentText(getString(R.string.audio_record_notification_text))
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
                if (!AudioRecordingRuntime.start(this, path)) stopSelf()
            }
            ACTION_STOP -> {
                AudioRecordingRuntime.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (AudioRecordingRuntime.recording.get()) AudioRecordingRuntime.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.yagay.yauto.action.AUDIO_RECORD_START"
        const val ACTION_STOP = "com.yagay.yauto.action.AUDIO_RECORD_STOP"
        const val EXTRA_PATH = "path"
        private const val CHANNEL = "yauto_audio_record"
        private const val NOTIFICATION_ID = 7302
    }
}

private object AudioRecordingRuntime {
    val recording = AtomicBoolean(false)
    @Volatile var currentPath: String = ""
        private set
    @Volatile var lastPath: String = ""
        private set
    private var recorder: MediaRecorder? = null

    @Synchronized
    fun start(context: Context, path: String): Boolean {
        if (recording.get() || path.isBlank()) return false
        return runCatching {
            val file = File(path)
            file.parentFile?.mkdirs()
            val next = MediaRecorder(context).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = next
            currentPath = file.absolutePath
            lastPath = file.absolutePath
            recording.set(true)
            true
        }.getOrElse {
            runCatching { recorder?.release() }
            recorder = null
            currentPath = ""
            recording.set(false)
            false
        }
    }

    @Synchronized
    fun stop(): String {
        if (!recording.get()) return lastPath
        runCatching { recorder?.stop() }
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        recording.set(false)
        val path = currentPath
        currentPath = ""
        if (path.isNotBlank()) lastPath = path
        return lastPath
    }
}

class AndroidMediaCaptureFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.media_capture"
    private val context = context.applicationContext

    override fun install(registry: FeatureRegistry) {
        registerAudioStart(registry)
        registerAudioStop(registry)
        registerScreenRecord(registry)
        registerAudioState(registry)
    }

    private fun registerAudioStart(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.audio.record.start"), FeatureKind.ACTION,
                "Start microphone recording", "Start an AAC/M4A microphone recording in a dedicated microphone foreground service",
                FeatureCategory.AUDIO,
                fields = listOf(
                    FieldSchema.Text("fileName", "File name (blank = timestamp)"),
                    FieldSchema.Variable("resultVariable", "Store recording path"),
                ),
                accessRequirements = setOf(AccessRequirement.RECORD_AUDIO),
                keywords = setOf("record", "microphone", "audio", "voice", "m4a"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (!runtimePermissionGranted(context, "microphone")) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.microphone_permission_required"))
            }
            if (AudioRecordingRuntime.recording.get()) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.audio_recording_already_active"))
            }
            val path = audioOutputPath(feature.config.string("fileName").resolveVariables(ctx.variables))
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.audio_filename_invalid"))
            val started = runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AudioRecordingService::class.java)
                        .setAction(AudioRecordingService.ACTION_START)
                        .putExtra(AudioRecordingService.EXTRA_PATH, path)
                )
                true
            }.getOrDefault(false)
            if (!started) return@registerAction ActionExecutionResult(false, message = userText("feature.audio_recorder_start_failed"))
            val output = ConfigValue.StringValue(path)
            feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, output) }
            ActionExecutionResult(true, output)
        }
    }

    private fun registerAudioStop(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.audio.record.stop"), FeatureKind.ACTION,
                "Stop microphone recording", "Stop YAuto's active microphone recording and return its saved path",
                FeatureCategory.AUDIO,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store saved recording path")),
                accessRequirements = setOf(AccessRequirement.RECORD_AUDIO),
                keywords = setOf("record", "stop", "microphone", "audio"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val path = AudioRecordingRuntime.stop()
            runCatching { context.stopService(Intent(context, AudioRecordingService::class.java)) }
            val output = ConfigValue.StringValue(path)
            feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, output) }
            ActionExecutionResult(path.isNotBlank(), output, if (path.isNotBlank()) null else userText("feature.audio_recording_none"))
        }
    }

    private fun registerScreenRecord(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.screen.record"), FeatureKind.ACTION,
                "Record screen", "Record the display to MP4 for a bounded duration using Android screenrecord through Root or Shizuku",
                FeatureCategory.DISPLAY,
                fields = listOf(
                    FieldSchema.Text("fileName", "MP4 file name (blank = timestamp)"),
                    FieldSchema.Number("durationSeconds", "Duration seconds", min = 1.0, max = 180.0),
                    FieldSchema.Number("bitrateMbps", "Bitrate Mbps", min = 1.0, max = 100.0),
                    FieldSchema.Variable("resultVariable", "Store saved path"),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                keywords = setOf("screenrecord", "screen recording", "video", "mp4", "capture"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val duration = (feature.config["durationSeconds"].numberOrNull() ?: 30.0).toInt()
            val bitrate = (feature.config["bitrateMbps"].numberOrNull() ?: 8.0).toInt()
            if (duration !in 1..180 || bitrate !in 1..100) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.screen_record_settings_invalid"))
            }
            val path = screenRecordOutputPath(feature.config.string("fileName").resolveVariables(ctx.variables))
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.screen_record_filename_invalid"))
            val command = "mkdir -p ${shellQuote(File(path).parent.orEmpty())} && screenrecord --time-limit $duration --bit-rate ${bitrate * 1_000_000} ${shellQuote(path)}"
            val result = ctx.capabilities.execute(
                CapabilityRequest(
                    capability = CapabilityIds.PRIVILEGED_SHELL,
                    operationId = feature.typeId,
                    payload = mapOf("command" to ConfigValue.StringValue(command)),
                )
            )
            if (!result.success) return@registerAction ActionExecutionResult(false, result.value, result.message)
            val output = ConfigValue.StringValue(path)
            feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, output) }
            ActionExecutionResult(true, output)
        }
    }

    private fun registerAudioState(registry: FeatureRegistry) {
        val evaluator = ConditionEvaluator { feature, _ ->
            AudioRecordingRuntime.recording.get() == feature.config.boolean("value", true)
        }
        val state = FeatureDescriptor(
            FeatureId("android.state.audio_recording"), FeatureKind.STATE,
            "YAuto audio recording", "Check whether YAuto is actively recording from the microphone",
            FeatureCategory.AUDIO,
            fields = listOf(FieldSchema.Toggle("value", "Recording")),
            ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(state.copy(id = FeatureId("android.condition.audio_recording"), kind = FeatureKind.CONDITION), evaluator)
    }

    private fun audioOutputPath(rawName: String): String? {
        val safeName = normalizedFileName(rawName, "audio-${System.currentTimeMillis()}", ".m4a") ?: return null
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "Recordings").apply { mkdirs() }
        return File(dir, safeName).absolutePath
    }

    private fun screenRecordOutputPath(rawName: String): String? {
        val safeName = normalizedFileName(rawName, "screen-${System.currentTimeMillis()}", ".mp4") ?: return null
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "ScreenRecords").apply { mkdirs() }
        return File(dir, safeName).absolutePath
    }
}

internal fun normalizedFileName(raw: String, fallbackBase: String, extension: String): String? {
    var name = raw.trim().ifBlank { "$fallbackBase$extension" }
    if (!name.lowercase().endsWith(extension)) name += extension
    if (name.contains('/') || name.contains('\\') || name.contains(' ') || name == "." || name == "..") return null
    return name
}
