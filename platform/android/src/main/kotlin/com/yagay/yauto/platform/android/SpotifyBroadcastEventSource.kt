package com.yagay.yauto.platform.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Spotify's own device-status broadcasts (not generic media-session events).
 * This source is workspace-gated. Spotify's "Device Broadcast Status" setting is required
 * and some Spotify versions do not emit these broadcasts anymore.
 * On Android versions without authenticated broadcast sender data, other apps could spoof them.
 */
class SpotifyBroadcastEventSource(context: Context) : AndroidEventSource {
    override val id: String = "android.spotify.broadcast"
    private val context = context.applicationContext
    private val started = AtomicBoolean(false)
    private var emitter: RuntimeEventEmitter? = null
    private var lastPlaying: Boolean? = null
    private var lastTrackSignature: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val incoming = intent ?: return
            val action = incoming.action ?: return
            val playing = incoming.getBooleanExtra("playing", false)
            val mode = spotifyBroadcastMode(action, if (incoming.hasExtra("playing")) playing else null)
                ?: return
            if (mode == "playback_started" || mode == "playback_stopped") {
                if (lastPlaying == playing) return
                lastPlaying = playing
            }
            val track = incoming.getStringExtra("track").orEmpty()
            val artist = incoming.getStringExtra("artist").orEmpty()
            val album = incoming.getStringExtra("album").orEmpty()
            val trackId = incoming.getStringExtra("id").orEmpty()
            if (mode == "song_changed") {
                if (trackId.isBlank() && track.isBlank()) return
                val signature = listOf(trackId, track, artist, album).joinToString("|")
                if (signature == lastTrackSignature) return
                lastTrackSignature = signature
            }
            val lengthMs = runCatching {
                (incoming.extras?.get("length") as? Number)?.toDouble() ?: -1.0
            }.getOrDefault(-1.0)
            emitter?.emit(
                RuntimeEvent(
                    "android.event.spotify",
                    mapOf(
                        "mode" to ConfigValue.StringValue(mode),
                        "track" to ConfigValue.StringValue(track),
                        "artist" to ConfigValue.StringValue(artist),
                        "album" to ConfigValue.StringValue(album),
                        "trackId" to ConfigValue.StringValue(trackId),
                        "lengthMs" to ConfigValue.NumberValue(lengthMs),
                        "playing" to ConfigValue.BooleanValue(playing),
                        "package" to ConfigValue.StringValue("com.spotify.music"),
                    ),
                    source = id,
                )
            )
        }
    }

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        lastPlaying = null
        lastTrackSignature = null
        try {
            val filter = IntentFilter().apply {
                addAction(SPOTIFY_PLAYBACK_BROADCAST)
                addAction(SPOTIFY_METADATA_BROADCAST)
            }
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
        } catch (error: Throwable) {
            started.set(false)
            this.emitter = null
            throw error
        }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { context.unregisterReceiver(receiver) }
        emitter = null
        lastPlaying = null
        lastTrackSignature = null
    }
}

internal const val SPOTIFY_PLAYBACK_BROADCAST = "com.spotify.music.playbackstatechanged"
internal const val SPOTIFY_METADATA_BROADCAST = "com.spotify.music.metadatachanged"

internal fun spotifyBroadcastMode(action: String, playing: Boolean?): String? = when (action) {
    SPOTIFY_PLAYBACK_BROADCAST -> when (playing) {
        true -> "playback_started"
        false -> "playback_stopped"
        null -> null
    }
    SPOTIFY_METADATA_BROADCAST -> "song_changed"
    else -> null
}
