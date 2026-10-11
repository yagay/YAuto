package com.yagay.yauto.platform.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayWebUrlPolicyTest {
    @Test fun `HTTP and HTTPS URLs are allowed`() {
        assertTrue(OverlayWebUrlPolicy.accepts("https://example.com/page?q=1"))
        assertTrue(OverlayWebUrlPolicy.accepts("http://127.0.0.1:8080/"))
    }

    @Test fun `unsafe URLs are rejected`() {
        for (url in listOf("", "https://", "file:///sdcard/private.txt",
            "content://provider/data", "javascript:alert(1)", "intent://site",
            "https://user:pass@example.com", "ftp://example.com")) {
            assertFalse(url, OverlayWebUrlPolicy.accepts(url))
        }
    }
}
