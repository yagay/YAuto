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
                keywords = setOf("audio", "sound", "music", "play", "uri", "media"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val source = feature.config.string("source").resolveVariables(ctx.variables).trim()
            if (source.isBlank()) return@registerAction ActionExecutionResult(false, message = userText("feature.playback_source_empty"))
            if (!isSupportedPlaybackSource(source)) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.playback_source_unsupported"))
            }
            val volume = (feature.config["volume"].numberOrNull() ?: 100.0).coerceIn(0.0, 100.0).toFloat() / 100f
            val loop = feature.config.boolean("loop")
            val wait = feature.config.boolean("waitForCompletion")
            if (loop && wait) return@registerAction ActionExecutionResult(false, message = userText("feature.playback_loop_wait_conflict"))
            controller.play(source, volume, loop, wait)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.audio.stop"), FeatureKind.ACTION,
                "Stop YAuto audio", "Stop audio started by YAuto without affecting other apps",
                FeatureCategory.AUDIO,
                keywords = setOf("audio", "sound", "music", "stop", "media"),
                ownerPackId = id,
            )
        ) { _, _ ->
            controller.stop()
            ActionExecutionResult(true)
        }
    }
}

private class PlaybackController(private val context: Context) {
    @Volatile private var current: MediaPlayer? = null
    @Volatile private var currentCompletion: CompletableDeferred<Boolean>? = null

    suspend fun play(source: String, volume: Float, loop: Boolean, wait: Boolean): ActionExecutionResult {
        stop()
        val player = preparePlayer(source, volume, loop)
            ?: return ActionExecutionResult(false, message = userText("feature.playback_failed", source))

        val completion = CompletableDeferred<Boolean>()
        val started = runCatching {
            withContext(Dispatchers.Main.immediate) {
                current = player
                currentCompletion = completion
                player.setOnCompletionListener { finished ->
                    if (current === finished) {
                        current = null
                        currentCompletion = null
                    }
                    completion.complete(true)
                    finished.release()
                }
                player.setOnErrorListener { failed, _, _ ->
                    if (current === failed) {
                        current = null
                        currentCompletion = null
                    }
                    completion.complete(false)
                    failed.release()
                    true
                }
                player.start()
            }
        }.isSuccess
        if (!started) {
            withContext(Dispatchers.Main.immediate) { runCatching { player.release() } }
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
                    setOnErrorListener { _, _, _ ->
                        prepared.complete(false)
                        true
                    }
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
        current = null
        currentCompletion = null
        if (active != null) {
            withContext(Dispatchers.Main.immediate) {
                runCatching { active.stop() }
                runCatching { active.release() }
            }
        }
        completion?.complete(false)
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
