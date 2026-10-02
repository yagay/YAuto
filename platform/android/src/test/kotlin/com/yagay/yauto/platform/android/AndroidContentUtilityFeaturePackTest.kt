package com.yagay.yauto.platform.android

import android.app.WallpaperManager
import org.junit.Assert.*
import org.junit.Test

class AndroidContentUtilityFeaturePackTest {
    @Test fun `mime type inference has safe fallback`() {
        assertEquals("text/plain", guessMimeType("notes.txt"))
        assertEquals("application/json", guessMimeType("data.json"))
        assertEquals("application/octet-stream", guessMimeType("no_extension"))
    }

    @Test fun `wallpaper targets map to platform flags`() {
        assertEquals(WallpaperManager.FLAG_SYSTEM, wallpaperFlags("home"))
        assertEquals(WallpaperManager.FLAG_LOCK, wallpaperFlags("lock"))
        assertEquals(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK, wallpaperFlags("both"))
        assertNull(wallpaperFlags("unknown"))
    }
}
