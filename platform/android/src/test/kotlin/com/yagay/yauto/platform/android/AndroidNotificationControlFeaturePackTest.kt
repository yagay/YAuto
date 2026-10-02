package com.yagay.yauto.platform.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidNotificationControlFeaturePackTest {
    @Test fun `notification filters match extended metadata`() {
        val match = AndroidNotificationControlFeaturePack.NotificationMatch(
            pkg = "com.example.player",
            title = "Now",
            text = "Track",
            channelId = "media",
            category = "transport",
            groupKey = "playback",
            ongoing = "only",
            hasActions = "yes",
        )
        val item = ActiveNotificationSnapshot(
            key = "key",
            packageName = "com.example.player",
            title = "Now playing",
            text = "Track one",
            actionCount = 3,
            ongoing = true,
            actionTitles = listOf("Previous", "Pause", "Next"),
            channelId = "media",
            category = "transport",
            groupKey = "playback",
        )
        assertTrue(match.matches(item))
        assertFalse(match.matches(item.copy(channelId = "messages")))
        assertFalse(match.matches(item.copy(actionCount = 0, actionTitles = emptyList())))
    }

    @Test fun `notification count comparison supports all operators`() {
        assertTrue(compareCount(3, 3, "=="))
        assertTrue(compareCount(3, 2, "!="))
        assertTrue(compareCount(3, 2, ">"))
        assertTrue(compareCount(3, 3, ">="))
        assertTrue(compareCount(2, 3, "<"))
        assertTrue(compareCount(3, 3, "<="))
        assertFalse(compareCount(3, 3, "unknown"))
    }
}
