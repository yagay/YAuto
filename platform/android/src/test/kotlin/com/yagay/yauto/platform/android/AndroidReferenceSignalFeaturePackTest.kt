package com.yagay.yauto.platform.android

import android.app.NotificationManager
import android.hardware.biometrics.BiometricManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidReferenceSignalFeaturePackTest {
    @Test
    fun `reference signal batch contains twenty four unique state-condition pairs`() {
        assertEquals(24, REFERENCE_SIGNAL_PAIR_KEYS.size)
        assertEquals(24, REFERENCE_SIGNAL_PAIR_KEYS.toSet().size)
    }

    @Test
    fun `biometric status maps to stable values`() {
        assertEquals("success", referenceBiometricStatusName(BiometricManager.BIOMETRIC_SUCCESS))
        assertEquals("no_hardware", referenceBiometricStatusName(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE))
        assertEquals("none_enrolled", referenceBiometricStatusName(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))
    }

    @Test
    fun `dnd filter maps to stable values`() {
        assertEquals("all", referenceDndFilterName(NotificationManager.INTERRUPTION_FILTER_ALL))
        assertEquals("priority", referenceDndFilterName(NotificationManager.INTERRUPTION_FILTER_PRIORITY))
        assertEquals("alarms", referenceDndFilterName(NotificationManager.INTERRUPTION_FILTER_ALARMS))
    }

    @Test
    fun `signal range rejects invalid bounds`() {
        assertTrue(matchesReferenceSignalRange(5.0, 1.0, 10.0, 0.0, 20.0))
        assertFalse(matchesReferenceSignalRange(5.0, 10.0, 1.0, 0.0, 20.0))
        assertFalse(matchesReferenceSignalRange(Double.NaN, null, null, 0.0, 20.0))
    }

    @Test
    fun `filtered text supports exact contains regex and invalid regex`() {
        assertTrue(referenceSignalTextMatches("Hello World", "hello world", "exact", true))
        assertTrue(referenceSignalTextMatches("Hello World", "WORLD", "contains", true))
        assertTrue(referenceSignalTextMatches("abc-123", "[a-z]+-\\d+", "regex", false))
        assertFalse(referenceSignalTextMatches("abc", "[", "regex", false))
    }
}
