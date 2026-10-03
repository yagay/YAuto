package com.yagay.yauto.platform.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidHttpFeaturePackTest {
    @Test fun `HTTP URL validation only accepts http and https`() {
        assertTrue(isHttpUrl("https://example.com/file.zip"))
        assertTrue(isHttpUrl("http://127.0.0.1/test"))
        assertFalse(isHttpUrl("ftp://example.com/file.zip"))
        assertFalse(isHttpUrl("file:///sdcard/test.zip"))
        assertFalse(isHttpUrl("example.com/file.zip"))
    }

    @Test fun `download byte limit has safe defaults and hard bounds`() {
        assertEquals(104_857_600L, httpDownloadLimit(null))
        assertEquals(1L, httpDownloadLimit(-20.0))
        assertEquals(42L, httpDownloadLimit(42.9))
        assertEquals(1_073_741_824L, httpDownloadLimit(Double.MAX_VALUE))
        assertEquals(104_857_600L, httpDownloadLimit(Double.NaN))
    }
}
