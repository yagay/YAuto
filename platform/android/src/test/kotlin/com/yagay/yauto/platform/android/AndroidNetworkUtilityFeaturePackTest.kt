package com.yagay.yauto.platform.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidNetworkUtilityFeaturePackTest {
    @Test fun `host validation rejects shell-like and whitespace input`() {
        assertTrue(isReasonableHost("example.com"))
        assertTrue(isReasonableHost("192.168.1.10"))
        assertFalse(isReasonableHost(""))
        assertFalse(isReasonableHost("bad host"))
        assertFalse(isReasonableHost("example.com;reboot"))
        assertFalse(isReasonableHost("example.com&&reboot"))
    }

    @Test fun `tcp port validation uses valid internet port range`() {
        assertTrue(isValidPort(1))
        assertTrue(isValidPort(443))
        assertTrue(isValidPort(65535))
        assertFalse(isValidPort(0))
        assertFalse(isValidPort(65536))
    }
}
