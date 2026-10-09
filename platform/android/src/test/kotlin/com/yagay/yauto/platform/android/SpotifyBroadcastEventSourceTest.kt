package com.yagay.yauto.platform.android

import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyBroadcastEventSourceTest {
    @Test fun `Spotify playback broadcasts are distinct from metadata broadcasts`() {
        assertEquals("playback_started", spotifyBroadcastMode(SPOTIFY_PLAYBACK_BROADCAST, true))
        assertEquals("playback_stopped", spotifyBroadcastMode(SPOTIFY_PLAYBACK_BROADCAST, false))
        assertEquals(null, spotifyBroadcastMode(SPOTIFY_PLAYBACK_BROADCAST, null))
        assertEquals("song_changed", spotifyBroadcastMode(SPOTIFY_METADATA_BROADCAST, null))
        assertEquals(null, spotifyBroadcastMode("com.other.music.metadatachanged", true))
    }
}
