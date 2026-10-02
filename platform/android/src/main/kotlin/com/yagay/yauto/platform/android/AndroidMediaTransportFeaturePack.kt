package com.yagay.yauto.platform.android

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

/** Global media transport keys delivered through Android's AudioManager media-key routing. */
class AndroidMediaTransportFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.media.transport"
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.media.transport"),
                FeatureKind.ACTION,
                "Media transport control",
                "Send play, pause, next, previous, stop, fast-forward or rewind to the active media session",
                FeatureCategory.AUDIO,
                fields = listOf(
                    FieldSchema.Choice(
                        "command",
                        "Media command",
                        true,
                        listOf("play_pause", "play", "pause", "next", "previous", "stop", "fast_forward", "rewind"),
                    )
                ),
                keywords = setOf("media", "music", "play", "pause", "next", "previous", "transport"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            val command = feature.config.string("command", "play_pause")
            val keyCode = mediaKeyCode(command)
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.media_transport_command_invalid"))
            runCatching {
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                ActionExecutionResult(true)
            }.getOrElse {
                ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName))
            }
        }
    }
}

internal fun mediaKeyCode(command: String): Int? = when (command) {
    "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
    "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
    "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
    "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
    "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
    "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
    "fast_forward" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
    "rewind" -> KeyEvent.KEYCODE_MEDIA_REWIND
    else -> null
}
