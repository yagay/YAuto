package com.yagay.yauto.platform.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Android5gToggleFeaturePackTest {
    private val lte = 1L shl 12
    private val gsm = 1L shl 15

    @Test fun `phone shell output is a list of network types`() {
        assertEquals(lte or gsm or NR_BIT, parseAllowedNetworkTypes("LTE|GSM|NR\n"))
        assertEquals(lte or NR_BIT, parseAllowedNetworkTypes("LTE|NR"))
        assertEquals(lte, parseAllowedNetworkTypes(java.lang.Long.toBinaryString(lte)))
        assertEquals(1L shl 14, parseAllowedNetworkTypes("HSPA+"))
        assertNull(parseAllowedNetworkTypes("UNKNOWN"))
        assertNull(parseAllowedNetworkTypes("LTE|FUTURE_MODE"))
        assertNull(parseAllowedNetworkTypes("LTE|NR\npermission denied"))
        assertNull(parseAllowedNetworkTypes(""))
    }

    @Test fun `toggle removes just NR and restores exactly when unchanged`() {
        val original = lte or gsm or NR_BIT
        val disabled = planFiveGMode(original, null, "toggle")!!
        assertEquals(lte or gsm, disabled)
        assertEquals(original, planFiveGMode(disabled, original, "toggle"))
    }

    @Test fun `external network mode changes are not overwritten when enabling`() {
        val original = lte or gsm or NR_BIT
        assertEquals(lte or NR_BIT, planFiveGMode(lte, original, "enable"))
        assertEquals(original, planFiveGMode(original, null, "enable"))
        assertNull(planFiveGMode(NR_BIT, null, "disable"))
        assertNull(planFiveGMode(lte, null, "bad"))
    }
}
