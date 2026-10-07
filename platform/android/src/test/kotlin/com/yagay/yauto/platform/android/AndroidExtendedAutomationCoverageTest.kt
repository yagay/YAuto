package com.yagay.yauto.platform.android

import com.yagay.yauto.core.capability.CapabilityClient
import com.yagay.yauto.core.capability.CapabilityResult
import com.yagay.yauto.core.logging.NoOpExecutionTracer
import com.yagay.yauto.core.model.ConfigMap
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.ExecutionId
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.registry.EventMatchContext
import com.yagay.yauto.core.registry.FeatureExecutionContext
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.VariableAccess
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidExtendedAutomationCoverageTest {
    private class Reader : AndroidExtendedStateReader {
        var idle = true
        var locked = true
        var secure = true
        var music = false
        var microphoneMute = false
        var speakerphone = false
        var clock24 = true
        var alarmSet = true
        var validated = true
        var metered = false
        var roaming = false
        var internet = true
        var restricted = false
        var suspended = false
        var currentOrientation = "portrait"
        var currentRingerMode = "normal"
        var present = true
        var plugged = "usb"
        var status = "charging"
        var health = "good"
        var voltage: Double? = 4_250.0

        override fun deviceIdle() = idle
        override fun keyguardLocked() = locked
        override fun deviceSecure() = secure
        override fun musicActive() = music
        override fun microphoneMuted() = microphoneMute
        override fun speakerphoneOn() = speakerphone
        override fun uses24HourClock() = clock24
        override fun nextAlarmSet() = alarmSet
        override fun networkValidated() = validated
        override fun networkMetered() = metered
        override fun networkRoaming() = roaming
        override fun networkInternet() = internet
        override fun networkRestricted() = restricted
        override fun networkSuspended() = suspended
        override fun orientation() = currentOrientation
        override fun ringerMode() = currentRingerMode
        override fun batteryPresent() = present
        override fun batteryPlugged() = plugged
        override fun batteryStatus() = status
        override fun batteryHealth() = health
        override fun batteryVoltageMv() = voltage
    }

    private val variables = object : VariableAccess {
        override fun get(name: String): ConfigValue? = null
        override fun set(name: String, value: ConfigValue) = Unit
        override fun snapshot(): Map<String, ConfigValue> = emptyMap()
    }
    private val capabilities = CapabilityClient { CapabilityResult(false, message = "unused") }

    @Test
    fun `extended state pack installs only states not owned by canonical packs`() {
        val registry = FeatureRegistry()
        AndroidExtendedStateFeaturePack(Reader()).install(registry)

        val descriptors = registry.allDescriptors()
        assertEquals(14, descriptors.size)
        assertEquals(7, descriptors.count { it.kind == FeatureKind.STATE })
        assertEquals(7, descriptors.count { it.kind == FeatureKind.CONDITION })

        val keys = listOf(
            "next_alarm_set",
            "network_validated",
            "network_internet",
            "network_restricted",
            "network_suspended",
            "battery_present",
            "battery_plugged",
        )
        keys.forEach { key ->
            assertNotNull(registry.stateEvaluator("android.state.$key"))
            assertNotNull(registry.conditionEvaluator("android.condition.$key"))
        }

        val ownedByEstablishedPacks = listOf(
            "device_idle",
            "device_secure",
            "keyguard_locked",
            "music_active",
            "microphone_muted",
            "speakerphone_on",
            "clock_24_hour",
            "network_metered",
            "ringer_mode",
            "network_roaming",
            "orientation",
            "battery_status",
            "battery_voltage",
            "battery_health",
        )
        ownedByEstablishedPacks.forEach { key ->
            assertNull("Extended pack must not reclaim android.state.$key", registry.stateEvaluator("android.state.$key"))
            assertNull("Extended pack must not reclaim android.condition.$key", registry.conditionEvaluator("android.condition.$key"))
        }
    }

    @Test
    fun `extended state values are live and configurable`() = runBlocking {
        val reader = Reader()
        val registry = FeatureRegistry().apply { AndroidExtendedStateFeaturePack(reader).install(this) }
        val context = FeatureExecutionContext(
            executionId = ExecutionId("test"),
            nodeId = null,
            variables = variables,
            capabilities = capabilities,
            tracer = NoOpExecutionTracer,
        )

        suspend fun matches(key: String, config: ConfigMap = emptyMap()): Boolean =
            registry.stateEvaluator("android.state.$key")!!.evaluate(
                FeatureRef("android.state.$key", config = config),
                context,
            )

        assertTrue(matches("network_validated"))
        reader.validated = false
        assertFalse(matches("network_validated"))

        assertTrue(matches("network_internet"))
        reader.internet = false
        assertFalse(matches("network_internet"))

        assertTrue(matches("battery_plugged", mapOf("value" to ConfigValue.StringValue("usb"))))
    }

    @Test
    fun `derived event pack installs twenty one real matchers`() {
        val registry = FeatureRegistry()
        AndroidDerivedEventFeaturePack().install(registry)

        val descriptors = registry.allDescriptors()
        assertEquals(21, descriptors.size)
        assertTrue(descriptors.all { it.kind == FeatureKind.EVENT })
        descriptors.forEach { descriptor ->
            assertNotNull("Missing matcher for ${descriptor.id.value}", registry.eventMatcher(descriptor.id.value))
        }
    }

    @Test
    fun `derived network events filter enriched network payloads`() = runBlocking {
        val registry = FeatureRegistry().apply { AndroidDerivedEventFeaturePack().install(this) }
        val payload = mapOf(
            "internet" to ConfigValue.BooleanValue(true),
            "validated" to ConfigValue.BooleanValue(true),
            "metered" to ConfigValue.BooleanValue(false),
            "roaming" to ConfigValue.BooleanValue(false),
            "restricted" to ConfigValue.BooleanValue(false),
            "suspended" to ConfigValue.BooleanValue(false),
            "wifi" to ConfigValue.BooleanValue(true),
            "cellular" to ConfigValue.BooleanValue(false),
            "ethernet" to ConfigValue.BooleanValue(false),
            "vpn" to ConfigValue.BooleanValue(false),
            "bluetooth" to ConfigValue.BooleanValue(false),
        )

        suspend fun matches(featureId: String, config: ConfigMap = emptyMap()): Boolean {
            val matcher = registry.eventMatcher(featureId)!!
            return matcher.matches(
                FeatureRef(featureId, config = config),
                EventMatchContext(
                    executionId = ExecutionId("test"),
                    event = RuntimeEvent("android.event.network_changed", payload),
                    variables = variables,
                    capabilities = capabilities,
                    tracer = NoOpExecutionTracer,
                ),
            )
        }

        assertTrue(matches("android.event.network_validated"))
        assertFalse(matches("android.event.network_unvalidated"))
        assertTrue(matches("android.event.network_unmetered"))
        assertFalse(matches("android.event.network_metered"))
        assertTrue(matches("android.event.network_wifi"))
        assertFalse(matches("android.event.network_cellular"))
        assertFalse(matches("android.event.network_roaming"))
        assertFalse(matches("android.event.network_restricted"))
        assertFalse(matches("android.event.network_suspended"))
        assertTrue(matches(
            "android.event.network_capabilities_filtered",
            mapOf(
                "transport" to ConfigValue.StringValue("wifi"),
                "internet" to ConfigValue.StringValue("yes"),
                "validated" to ConfigValue.StringValue("yes"),
                "metered" to ConfigValue.StringValue("no"),
                "roaming" to ConfigValue.StringValue("no"),
                "restricted" to ConfigValue.StringValue("no"),
                "suspended" to ConfigValue.StringValue("no"),
            ),
        ))
        assertFalse(matches(
            "android.event.network_capabilities_filtered",
            mapOf("transport" to ConfigValue.StringValue("vpn")),
        ))
    }

    @Test
    fun `derived wifi signal and supplicant triggers use reference payloads`() = runBlocking {
        val registry = FeatureRegistry().apply { AndroidDerivedEventFeaturePack().install(this) }

        suspend fun matches(
            featureId: String,
            runtimeType: String,
            config: ConfigMap = emptyMap(),
            payload: ConfigMap = emptyMap(),
        ): Boolean = registry.eventMatcher(featureId)!!.matches(
            FeatureRef(featureId, config = config),
            EventMatchContext(
                executionId = ExecutionId("test"),
                event = RuntimeEvent(runtimeType, payload),
                variables = variables,
                capabilities = capabilities,
                tracer = NoOpExecutionTracer,
            ),
        )

        assertTrue(matches(
            "android.event.wifi_rssi_filtered",
            "android.event.wifi_rssi_changed",
            mapOf(
                "minRssi" to ConfigValue.NumberValue(-70.0),
                "maxRssi" to ConfigValue.NumberValue(-40.0),
            ),
            mapOf("rssi" to ConfigValue.NumberValue(-55.0)),
        ))
        assertFalse(matches(
            "android.event.wifi_rssi_filtered",
            "android.event.wifi_rssi_changed",
            mapOf("minRssi" to ConfigValue.NumberValue(-50.0)),
            mapOf("rssi" to ConfigValue.NumberValue(-55.0)),
        ))
        assertTrue(matches(
            "android.event.wifi_supplicant_connected",
            "android.event.wifi_supplicant_connection_changed",
            payload = mapOf("connected" to ConfigValue.BooleanValue(true)),
        ))
        assertTrue(matches(
            "android.event.wifi_supplicant_disconnected",
            "android.event.wifi_supplicant_connection_changed",
            payload = mapOf("connected" to ConfigValue.BooleanValue(false)),
        ))
    }
}
