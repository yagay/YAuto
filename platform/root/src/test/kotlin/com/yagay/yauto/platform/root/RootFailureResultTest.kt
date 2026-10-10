package com.yagay.yauto.platform.root

import com.yagay.yauto.core.diagnostics.CommandOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RootFailureResultTest {
    @Test fun successfulCommandHasNoError() {
        assertNull(rootCommandFailureMessage(CommandOutput(0, "ok", "")))
    }
    @Test fun failureUsesStderrFirst() {
        assertEquals("permission denied",
            rootCommandFailureMessage(CommandOutput(1, "not useful", "permission denied")))
    }
    @Test fun failureNeverReturnsNullOrBlank() {
        assertNotNull(rootCommandFailureMessage(CommandOutput(77, "", "")))
        assertEquals("invalid argument", rootCommandFailureMessage(CommandOutput(2, "invalid argument", "")))
    }
}
