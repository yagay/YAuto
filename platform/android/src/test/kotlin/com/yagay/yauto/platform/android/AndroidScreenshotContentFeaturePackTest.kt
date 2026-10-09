package com.yagay.yauto.platform.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidScreenshotContentFeaturePackTest {
    @Test fun `screenshot OCR matches only the configured watch`() {
        assertTrue(screenshotOcrMatchIsForTrigger("Authorize", true, "authorize", true))
        assertFalse(screenshotOcrMatchIsForTrigger("Authorize", true, "authorization", true))
        assertFalse(screenshotOcrMatchIsForTrigger("Authorize", true, "Authorize", false))
        assertFalse(screenshotOcrMatchIsForTrigger("Authorize", false, "authorize", false))
        assertFalse(screenshotOcrMatchIsForTrigger(" ", true, " ", true))
    }
}
