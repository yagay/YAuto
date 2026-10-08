package com.yagay.yauto.platform.android

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidReferenceControlExpansionFeaturePackTest {
    @Test
    fun `display density command uses stable density percentage`() {
        assertEquals("wm density 420", densityCommand(100, 420))
        assertEquals("wm density 315", densityCommand(75, 420))
        assertEquals("wm density 630", densityCommand(150, 420))
        assertNull(densityCommand(49, 420))
        assertNull(densityCommand(100, 0))
    }

    @Test
    fun `immersive modes map to policy control commands and back`() {
        assertEquals("settings delete global policy_control", immersiveModeCommand("off"))
        assertEquals("settings put global policy_control immersive.navigation=*", immersiveModeCommand("navigation"))
        assertEquals("settings put global policy_control immersive.status=*", immersiveModeCommand("status"))
        assertEquals("settings put global policy_control immersive.full=*", immersiveModeCommand("full"))
        assertNull(immersiveModeCommand("unknown"))

        assertEquals("off", immersiveModeFromPolicy(null))
        assertEquals("navigation", immersiveModeFromPolicy("immersive.navigation=*"))
        assertEquals("status", immersiveModeFromPolicy("immersive.status=*"))
        assertEquals("full", immersiveModeFromPolicy("immersive.full=*"))
    }

    @Test
    fun `animation scale commands cover individual and all targets`() {
        assertEquals("settings put global window_animation_scale 0.5", animationScaleCommand("window", 0.5))
        assertEquals("settings put global transition_animation_scale 1.0", animationScaleCommand("transition", 1.0))
        assertEquals("settings put global animator_duration_scale 2.0", animationScaleCommand("animator", 2.0))
        assertEquals(
            "settings put global window_animation_scale 0.0; settings put global transition_animation_scale 0.0; settings put global animator_duration_scale 0.0",
            animationScaleCommand("all", 0.0),
        )
        assertNull(animationScaleCommand("unknown", 1.0))
        assertNull(animationScaleCommand("all", 11.0))
    }

    @Test
    fun `audio stream names map to Android stream constants`() {
        assertEquals(AudioManager.STREAM_ALARM, audioStream("alarm"))
        assertEquals(AudioManager.STREAM_MUSIC, audioStream("music"))
        assertEquals(AudioManager.STREAM_NOTIFICATION, audioStream("notification"))
        assertEquals(AudioManager.STREAM_RING, audioStream("ringer"))
        assertEquals(AudioManager.STREAM_SYSTEM, audioStream("system"))
        assertEquals(AudioManager.STREAM_VOICE_CALL, audioStream("voice_call"))
        assertEquals(6, audioStream("bluetooth_voice"))
        assertNull(audioStream("unknown"))
    }
}
