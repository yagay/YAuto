package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.AccessRequirement
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.FieldSchema

/** Media-session triggers backed by the notification-listener service's active session access. */
class AndroidMediaSessionEventFeaturePack : FeaturePack {
    override val id: String = "android.media_session.events"

    override fun install(registry: FeatureRegistry) {
        registerTrackChanged(registry)
        registerSpotify(registry)
        registerPlaybackStateChanged(registry)
    }

    private fun registerTrackChanged(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.media_track_changed"),
                FeatureKind.EVENT,
                "Media track changed",
                "Trigger when an active Android media session publishes new track metadata",
                FeatureCategory.AUDIO,
                fields = listOf(
                    FieldSchema.AppPicker("package", "App / package"),
                    FieldSchema.Text("titleContains", "Title contains"),
                    FieldSchema.Text("artistContains", "Artist contains"),
                    FieldSchema.Text("albumContains", "Album contains"),
                    FieldSchema.Text("mediaIdContains", "Media ID contains"),
                    FieldSchema.Toggle("ignoreCase", "Ignore case"),
                ),
                accessRequirements = setOf(AccessRequirement.NOTIFICATION_LISTENER),
                keywords = setOf("media track", "song changed", "metadata", "artist", "album", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, context ->
            if (context.event.typeId != "android.event.media_track_changed") return@registerEvent false
            val payload = context.event.payload
            val pkg = feature.config.string("package")
            if (pkg.isNotBlank() && payload.string("package") != pkg) return@registerEvent false
            val ignoreCase = feature.config.boolean("ignoreCase", true)
            mediaContains(payload.string("title"), feature.config.string("titleContains"), ignoreCase) &&
                mediaContains(payload.string("artist"), feature.config.string("artistContains"), ignoreCase) &&
                mediaContains(payload.string("album"), feature.config.string("albumContains"), ignoreCase) &&
                mediaContains(payload.string("mediaId"), feature.config.string("mediaIdContains"), ignoreCase)
        }
    }

    /** Spotify's own broadcast events are not interchangeable with MediaSession events. */
    private fun registerSpotify(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.spotify"),
                FeatureKind.EVENT,
                "Spotify playback event",
                "Listen for Spotify device-status broadcasts: playback start, stop or song change",
                FeatureCategory.AUDIO,
                fields = listOf(
                    FieldSchema.Choice("mode", "Spotify event", true, listOf(
                        "any", "playback_started", "playback_stopped", "song_changed",
                    )),
                    FieldSchema.Text("trackContains", "Track contains"),
                    FieldSchema.Text("artistContains", "Artist contains"),
                    FieldSchema.Text("albumContains", "Album contains"),
                    FieldSchema.Toggle("ignoreCase", "Ignore case"),
                ),
                keywords = setOf("spotify", "song changed", "playback start", "playback stop", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, context ->
            if (context.event.typeId != "android.event.spotify") return@registerEvent false
            val payload = context.event.payload
            val mode = feature.config.string("mode", "any")
            if (mode != "any" && mode != payload.string("mode")) return@registerEvent false
            val ignoreCase = feature.config.boolean("ignoreCase", true)
            mediaContains(payload.string("track"), feature.config.string("trackContains"), ignoreCase) &&
                mediaContains(payload.string("artist"), feature.config.string("artistContains"), ignoreCase) &&
                mediaContains(payload.string("album"), feature.config.string("albumContains"), ignoreCase)
        }
    }

    private fun registerPlaybackStateChanged(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.media_playback_state_changed"),
                FeatureKind.EVENT,
                "Media playback state changed",
                "Trigger when an active media session changes playback state",
                FeatureCategory.AUDIO,
                fields = listOf(
                    FieldSchema.AppPicker("package", "App / package"),
                    FieldSchema.Choice(
                        "state",
                        "Playback state",
                        true,
                        listOf(
                            "any", "none", "stopped", "paused", "playing", "fast_forwarding", "rewinding",
                            "buffering", "error", "connecting", "skipping_previous", "skipping_next",
                            "skipping_queue_item", "unknown",
                        ),
                    ),
                ),
                accessRequirements = setOf(AccessRequirement.NOTIFICATION_LISTENER),
                keywords = setOf("media", "playback", "playing", "paused", "session"),
                ownerPackId = id,
            )
        ) { feature, context ->
            if (context.event.typeId != "android.event.media_playback_state_changed") return@registerEvent false
            val pkg = feature.config.string("package")
            if (pkg.isNotBlank() && context.event.payload.string("package") != pkg) return@registerEvent false
            val expected = feature.config.string("state", "any")
            expected == "any" || context.event.payload.string("state") == expected
        }
    }
}

internal fun mediaContains(actual: String, expected: String, ignoreCase: Boolean): Boolean =
    expected.isBlank() || actual.contains(expected, ignoreCase)
