package com.yagay.yauto.platform.android

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Playback owned by YAuto only; it never sends stop commands to unrelated media sessions. */
class AndroidPlaybackFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.playback"
    private val controller = PlaybackController(context.applicationContext)

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.audio.play"), FeatureKind.ACTION,
                "Play audio", "Play an audio or media URI owned by this YAuto playback session",
                FeatureCategory.AUDIO,
                fields = listOf(
                    FieldSchema.Text("source", "File path or URI", true),
                    FieldSchema.Number("volume", "Volume percent", min = 0.0, max = 100.0),
                    FieldSchema.Toggle("loop", "Loop playback"),
                    FieldSchema.Toggle("waitForCompletion", "Wait for playback to finish"),
                ),
                keywords = setOf("audio", "sound", "music", "play", "uri", "media"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val source = feature.config.string("source").resolveVariables(ctx.variables).trim()
            if (source.isBlank()) return@registerAction ActionExecutionResult(false, message = userText("feature.playback_source_empty"))
            if (!isSupportedPlaybackSource(source)) return@registerAction ActionExecutionResult(false, message = userText("feature.playback_source_unsupported"))
            val volume = (feature.config["volume"].numberOrNull() ?: 100.0).coerceIn(0.0, 100.0).toFloat() / 100f
            val loop = feature.config.boolean("loop")
            val wait = feature.config.boolean("waitForCompletion")
            if (loop && wait) return@registerAction ActionExecutionResult(false, message = userText("feature.playback_loop_wait_conflict"))
            controller.play(source, volume, loop, wait)
        }

        simpleControl(registry, "android.audio.pause", "Pause YAuto audio", "Pause audio currently played by YAuto") { controller.pause() }
        simpleControl(registry, "android.audio.resume", "Resume YAuto audio", "Resume paused audio currently owned by YAuto") { controller.resume() }
        simpleControl(registry, "android.audio.stop", "Stop YAuto audio", "Stop audio started by YAuto without affecting other apps") { controller.stop(); true }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.audio.seek"), FeatureKind.ACTION,
                "Seek YAuto audio", "Move the current YAuto playback position to an absolute time in milliseconds",
                FeatureCategory.AUDIO,
                fields = listOf(FieldSchema.Duration("positionMs", "Playback position")),
                keywords = setOf("audio", "seek", "position", "media"), ownerPackId = id,
            )
        ) { feature, _ ->
            val position = feature.config["positionMs"].numberOrNull()?.toLong() ?: 0L
            val ok = controller.seek(position)
            ActionExecutionResult(ok, message = if (ok) null else userText("feature.playback_not_active"))
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.audio.playback_volume.set"), FeatureKind.ACTION,
                "Set YAuto playback volume", "Change volume for the current YAuto playback session without changing system media volume",
                FeatureCategory.AUDIO,
                fields = listOf(FieldSchema.Number("volume", "Volume percent", true, min = 0.0, max = 100.0)),
                keywords = setOf("audio", "volume", "media", "playback"), ownerPackId = id,
            )
        ) { feature, _ ->
            val percent = (feature.config["volume"].numberOrNull() ?: 100.0).coerceIn(0.0, 100.0)
            val ok = controller.setVolume((percent / 100.0).toFloat())
            ActionExecutionResult(ok, ConfigValue.NumberValue(percent), if (ok) null else userText("feature.playback_not_active"))
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.audio.playback_info"), FeatureKind.ACTION,
                "Get YAuto playback information", "Store source, playing state, position, duration, looping and volume for the current YAuto playback session",
                FeatureCategory.AUDIO,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store playback object", true)),
                keywords = setOf("audio", "playback", "state", "position", "duration"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val output = controller.info()
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
    }

    private fun simpleControl(
        registry: FeatureRegistry,
        featureId: String,
        title: String,
        description: String,
        operation: suspend () -> Boolean,
    ) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId(featureId), FeatureKind.ACTION, title, description, FeatureCategory.AUDIO,
                keywords = setOf("audio", "sound", "music", "media", "playback"), ownerPackId = id,
            )
        ) { _, _ ->
            val ok = operation()
            ActionExecutionResult(ok, message = if (ok) null else userText("feature.playback_not_active"))
        }
    }
}

private class PlaybackController(private val context: Context) {
    @Volatile private var current: MediaPlayer? = null
    @Volatile private var currentCompletion: CompletableDeferred<Boolean>? = null
    @Volatile private var currentSource: String = ""
    @Volatile private var currentVolume: Float = 1f
    @Volatile private var currentLooping: Boolean = false

    suspend fun play(source: String, volume: Float, loop: Boolean, wait: Boolean): ActionExecutionResult {
        stop()
        val player = preparePlayer(source, volume, loop)
            ?: return ActionExecutionResult(false, message = userText("feature.playback_failed", source))

        val completion = CompletableDeferred<Boolean>()
        val started = runCatching {
            withContext(Dispatchers.Main.immediate) {
                current = player
                currentCompletion = completion
                currentSource = source
                currentVolume = volume
                currentLooping = loop
                player.setOnCompletionListener { finished ->
                    if (current === finished) clearCurrent()
                    completion.complete(true)
                    finished.release()
                }
                player.setOnErrorListener { failed, _, _ ->
                    if (current === failed) clearCurrent()
                    completion.complete(false)
                    failed.release()
                    true
                }
                player.start()
            }
        }.isSuccess
        if (!started) {
            withContext(Dispatchers.Main.immediate) { runCatching { player.release() } }
            clearCurrent()
            return ActionExecutionResult(false, message = userText("feature.playback_failed", source))
        }
        if (!wait) return ActionExecutionResult(true, ConfigValue.StringValue(source))

        return when (withTimeoutOrNull(MAX_WAIT_MS) { completion.await() }) {
            true -> ActionExecutionResult(true, ConfigValue.StringValue(source))
            false -> ActionExecutionResult(false, message = userText("feature.playback_failed", source))
            null -> {
                stop()
                ActionExecutionResult(false, message = userText("feature.playback_timeout"))
            }
        }
    }

    suspend fun pause(): Boolean = withContext(Dispatchers.Main.immediate) {
        val player = current ?: return@withContext false
        runCatching { if (player.isPlaying) player.pause(); true }.getOrDefault(false)
    }

    suspend fun resume(): Boolean = withContext(Dispatchers.Main.immediate) {
        val player = current ?: return@withContext false
        runCatching { if (!player.isPlaying) player.start(); true }.getOrDefault(false)
    }

    suspend fun seek(positionMs: Long): Boolean = withContext(Dispatchers.Main.immediate) {
        val player = current ?: return@withContext false
        runCatching {
            val target = positionMs.coerceIn(0L, player.duration.toLong().coerceAtLeast(0L))
            player.seekTo(target, MediaPlayer.SEEK_CLOSEST)
            true
        }.getOrDefault(false)
    }

    suspend fun setVolume(volume: Float): Boolean = withContext(Dispatchers.Main.immediate) {
        val player = current ?: return@withContext false
        runCatching {
            val safe = volume.coerceIn(0f, 1f)
            player.setVolume(safe, safe)
            currentVolume = safe
            true
        }.getOrDefault(false)
    }

    suspend fun info(): ConfigValue.ObjectValue = withContext(Dispatchers.Main.immediate) {
        val player = current
        val active = player != null
        val playing = if (player == null) false else runCatching { player.isPlaying }.getOrDefault(false)
        val position = if (player == null) 0 else runCatching { player.currentPosition }.getOrDefault(0)
        val duration = if (player == null) 0 else runCatching { player.duration }.getOrDefault(0)
        ConfigValue.ObjectValue(
            mapOf(
                "active" to ConfigValue.BooleanValue(active),
                "playing" to ConfigValue.BooleanValue(playing),
                "source" to ConfigValue.StringValue(if (active) currentSource else ""),
                "positionMs" to ConfigValue.NumberValue(position.toDouble()),
                "durationMs" to ConfigValue.NumberValue(duration.toDouble()),
                "looping" to ConfigValue.BooleanValue(active && currentLooping),
                "volumePercent" to ConfigValue.NumberValue(if (active) currentVolume * 100.0 else 0.0),
            )
        )
    }

    private suspend fun preparePlayer(source: String, volume: Float, loop: Boolean): MediaPlayer? {
        val prepared = CompletableDeferred<Boolean>()
        val player = runCatching {
            withContext(Dispatchers.Main.immediate) {
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    setVolume(volume, volume)
                    isLooping = loop
                    setSource(source)
                    setOnPreparedListener { prepared.complete(true) }
                    setOnErrorListener { _, _, _ -> prepared.complete(false); true }
                    prepareAsync()
                }
            }
        }.getOrNull() ?: return null

        val ready = withTimeoutOrNull(PREPARE_TIMEOUT_MS) { prepared.await() } == true
        if (!ready) {
            withContext(Dispatchers.Main.immediate) { runCatching { player.release() } }
            return null
        }
        return player
    }

    suspend fun stop() {
        val active = current
        val completion = currentCompletion
        clearCurrent()
        if (active != null) {
            withContext(Dispatchers.Main.immediate) {
                runCatching { active.stop() }
                runCatching { active.release() }
            }
        }
        completion?.complete(false)
    }

    private fun clearCurrent() {
        current = null
        currentCompletion = null
        currentSource = ""
        currentVolume = 1f
        currentLooping = false
    }

    private fun MediaPlayer.setSource(source: String) {
        val uri = Uri.parse(source)
        when (uri.scheme?.lowercase()) {
            "content", "android.resource", "file" -> setDataSource(context, uri)
            "http", "https" -> setDataSource(source)
            null -> setDataSource(source)
            else -> error("unreachable")
        }
    }

    private companion object {
        const val PREPARE_TIMEOUT_MS = 30_000L
        const val MAX_WAIT_MS = 60 * 60_000L
    }
}

internal fun isSupportedPlaybackSource(source: String): Boolean {
    if (source.startsWith('/')) return true
    return when (Uri.parse(source).scheme?.lowercase()) {
        "content", "android.resource", "file", "http", "https" -> true
        else -> false
    }
}
