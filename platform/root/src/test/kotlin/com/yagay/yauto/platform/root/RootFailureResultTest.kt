package com.yagay.yauto.platform.root

import com.yagay.yauto.core.diagnostics.CommandOutput
import com.yagay.yauto.core.capability.PrivilegedOperationContract
import com.yagay.yauto.core.capability.SystemOperations
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RootFailureResultTest {
    @Test fun sharedShellIsRestrictedToReadOnlyCommands() {
        assertTrue(ReadOnlyRootCommand.accepts("id"))
        assertTrue(ReadOnlyRootCommand.accepts("getprop ro.build.version.sdk"))
        assertTrue(ReadOnlyRootCommand.accepts("settings get secure enabled_accessibility_services"))
        assertFalse(ReadOnlyRootCommand.accepts("settings put global airplane_mode_on 1"))
        assertFalse(ReadOnlyRootCommand.accepts("input tap 50 50"))
        assertFalse(ReadOnlyRootCommand.accepts("reboot"))
        assertFalse(ReadOnlyRootCommand.accepts("id; reboot"))
        assertFalse(ReadOnlyRootCommand.accepts("getprop ro.debuggable; echo 2"))
    }

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
    @Test fun privilegedOperationContractDoesNotOverclaimRootSupport() {
        assertTrue(PrivilegedOperationContract.rootSystemUiSupported(SystemOperations.SLEEP))
        assertFalse(PrivilegedOperationContract.rootSystemUiSupported(SystemOperations.SENSORS_OFF_QUERY))
        assertTrue(PrivilegedOperationContract.lsposedSystemUiSupported(SystemOperations.SENSORS_OFF_QUERY))
    }
}
