package com.yagay.yauto

import com.yagay.yauto.core.registry.FeatureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadOnlyFeatureProbeCatalogTest {
    @Test fun stateAndConditionShareExactlyTheSameSafeQueryKey() {
        ReadOnlyFeatureProbeCatalog.keys.forEach { key ->
            assertEquals(key, ReadOnlyFeatureProbeCatalog.queryKey("android.state.$key", FeatureKind.STATE))
            assertEquals(key, ReadOnlyFeatureProbeCatalog.queryKey("android.condition.$key", FeatureKind.CONDITION))
        }
    }

    @Test fun noActionOrEventCanBeExecutedByBulkReadOnlyScan() {
        ReadOnlyFeatureProbeCatalog.keys.forEach { key ->
            assertNull(ReadOnlyFeatureProbeCatalog.queryKey("android.state.$key", FeatureKind.ACTION))
            assertNull(ReadOnlyFeatureProbeCatalog.queryKey("android.condition.$key", FeatureKind.EVENT))
        }
        assertNull(ReadOnlyFeatureProbeCatalog.queryKey("android.access.status", FeatureKind.ACTION))
        assertNull(ReadOnlyFeatureProbeCatalog.queryKey("android.event.app_ops_operation", FeatureKind.EVENT))
        assertNull(ReadOnlyFeatureProbeCatalog.queryKey("android.state.task_present", FeatureKind.STATE))
        assertNull(ReadOnlyFeatureProbeCatalog.queryKey("android.condition.hardware_key", FeatureKind.CONDITION))
    }

    @Test fun actualAndroidStateIdentifiersHaveSafeStandaloneQueries() {
        val expected = setOf(
            "screen", "charging", "power_save", "nfc_enabled", "location_enabled",
            "network", "battery_level", "battery_temperature", "media_volume",
            "bluetooth_enabled", "network_validated", "battery_present",
        )
        assertTrue(ReadOnlyFeatureProbeCatalog.keys.containsAll(expected))
        assertNull(ReadOnlyFeatureProbeCatalog.queryKey("android.state.app_installed", FeatureKind.STATE))
        assertNull(ReadOnlyFeatureProbeCatalog.queryKey("android.event.hardware_key", FeatureKind.EVENT))
    }

    @Test fun extendedSystemApiProbesCoverRegisteredParameterFreeObservations() {
        val additional = AdditionalReadOnlyInterfaceProbe.keys
        assertEquals(57, additional.size)
        assertTrue(additional.containsAll(
            setOf(
                "clock_24h", "build_sdk", "has_nfc", "device_locked",
                "memory_available", "battery_current", "audio.ringer_mode",
                "touch_exploration_enabled", "supports_picture_in_picture",
                "screen_width_dp", "thermal_level", "captioning_enabled",
            )
        ))
        assertEquals("clock_24h", ReadOnlyFeatureProbeCatalog.queryKey(
            "android.state.clock_24h", FeatureKind.STATE
        ))
        assertEquals("build_sdk", ReadOnlyFeatureProbeCatalog.queryKey(
            "android.condition.build_sdk", FeatureKind.CONDITION
        ))
        assertNull(ReadOnlyFeatureProbeCatalog.queryKey(
            "android.event.build_sdk", FeatureKind.EVENT
        ))
    }

    @Test fun observedFalseIsStillSuccessfulInterfaceResponse() {
        val result = FeatureProbeResult(
            FeatureProbeStatus.RESPONDED,
            "KeyguardManager.isKeyguardLocked",
            "false",
        )
        assertEquals(FeatureProbeStatus.RESPONDED, result.status)
        assertEquals("false", result.observedValue)
        assertTrue(result.interfaceName!!.contains("KeyguardManager"))
    }
}
