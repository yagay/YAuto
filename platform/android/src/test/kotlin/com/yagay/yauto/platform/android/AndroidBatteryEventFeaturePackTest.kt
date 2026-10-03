package com.yagay.yauto.platform.android

import android.os.BatteryManager
import com.yagay.yauto.core.capability.CapabilityClient
import com.yagay.yauto.core.capability.CapabilityResult
import com.yagay.yauto.core.logging.NoOpExecutionTracer
import com.yagay.yauto.core.model.ConfigMap
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.ExecutionId
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.registry.EventMatchContext
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.VariableAccess
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidBatteryEventFeaturePackTest {
    private val variables = object : VariableAccess {
        override fun get(name: String): ConfigValue? = null
        override fun set(name: String, value: ConfigValue) = Unit
        override fun snapshot(): Map<String, ConfigValue> = emptyMap()
    }
    private val capabilities = CapabilityClient { CapabilityResult(false, message = "unused") }

    private val batteryPayload: ConfigMap = mapOf(
        "percent" to ConfigValue.NumberValue(72.0),
        "temperatureC" to ConfigValue.NumberValue(31.5),
        "present" to ConfigValue.BooleanValue(true),
        "plugged" to ConfigValue.StringValue("usb"),
        "status" to ConfigValue.StringValue("charging"),
        "health" to ConfigValue.StringValue("good"),
        "voltageMv" to ConfigValue.NumberValue(4_220.0),
    )

    private suspend fun matches(
        registry: FeatureRegistry,
        featureId: String,
        config: ConfigMap = emptyMap(),
        payload: ConfigMap = batteryPayload,
        runtimeType: String = "android.event.battery_changed",
    ): Boolean = registry.eventMatcher(featureId)!!.matches(
        FeatureRef(featureId, config = config),
        EventMatchContext(
            executionId = ExecutionId("battery-test"),
            event = RuntimeEvent(runtimeType, payload),
            variables = variables,
            capabilities = capabilities,
            tracer = NoOpExecutionTracer,
        ),
    )

    @Test
    fun `battery detail pack installs six concrete event matchers`() {
        val registry = FeatureRegistry().apply { AndroidBatteryEventFeaturePack().install(this) }
        val descriptors = registry.allDescriptors()
        assertEquals(6, descriptors.size)
        assertTrue(descriptors.all { it.kind == FeatureKind.EVENT })
        descriptors.forEach { descriptor ->
            assertNotNull(registry.eventMatcher(descriptor.id.value))
        }
    }

    @Test
    fun `power source status health and presence filters match battery payload`() = runBlocking {
        val registry = FeatureRegistry().apply { AndroidBatteryEventFeaturePack().install(this) }

        assertTrue(matches(
            registry,
            "android.event.battery_power_source_filtered",
            mapOf("value" to ConfigValue.StringValue("usb")),
        ))
        assertFalse(matches(
            registry,
            "android.event.battery_power_source_filtered",
            mapOf("value" to ConfigValue.StringValue("wireless")),
        ))
        assertTrue(matches(
            registry,
            "android.event.battery_status_filtered",
            mapOf("value" to ConfigValue.StringValue("charging")),
        ))
        assertTrue(matches(
            registry,
            "android.event.battery_health_filtered",
            mapOf("value" to ConfigValue.StringValue("good")),
        ))
        assertTrue(matches(
            registry,
            "android.event.battery_present_filtered",
            mapOf("value" to ConfigValue.StringValue("yes")),
        ))
        assertFalse(matches(
            registry,
            "android.event.battery_present_filtered",
            mapOf("value" to ConfigValue.StringValue("no")),
        ))
    }

    @Test
    fun `battery voltage filter uses inclusive bounded millivolt range`() = runBlocking {
        val registry = FeatureRegistry().apply { AndroidBatteryEventFeaturePack().install(this) }
        val featureId = "android.event.battery_voltage_filtered"

        assertTrue(matches(
            registry,
            featureId,
            mapOf(
                "minMv" to ConfigValue.NumberValue(4_000.0),
                "maxMv" to ConfigValue.NumberValue(4_220.0),
            ),
        ))
        assertFalse(matches(
            registry,
            featureId,
            mapOf("minMv" to ConfigValue.NumberValue(4_300.0)),
        ))
        assertFalse(matches(
            registry,
            featureId,
            mapOf(
                "minMv" to ConfigValue.NumberValue(5_000.0),
                "maxMv" to ConfigValue.NumberValue(4_000.0),
            ),
        ))
    }

    @Test
    fun `battery profile combines charge thermal voltage source status health and presence`() = runBlocking {
        val registry = FeatureRegistry().apply { AndroidBatteryEventFeaturePack().install(this) }
        val featureId = "android.event.battery_profile_filtered"
        val config = mapOf(
            "minPercent" to ConfigValue.NumberValue(70.0),
            "maxPercent" to ConfigValue.NumberValue(80.0),
            "minTemperatureC" to ConfigValue.NumberValue(30.0),
            "maxTemperatureC" to ConfigValue.NumberValue(35.0),
            "minVoltageMv" to ConfigValue.NumberValue(4_000.0),
            "maxVoltageMv" to ConfigValue.NumberValue(4_300.0),
            "powerSource" to ConfigValue.StringValue("usb"),
            "status" to ConfigValue.StringValue("charging"),
            "health" to ConfigValue.StringValue("good"),
            "present" to ConfigValue.StringValue("yes"),
        )
        assertTrue(matches(registry, featureId, config))
        assertFalse(matches(
            registry,
            featureId,
            config + ("status" to ConfigValue.StringValue("discharging")),
        ))
        assertFalse(matches(
            registry,
            featureId,
            config,
            payload = batteryPayload - "voltageMv",
        ))
    }

    @Test
    fun `battery detail events never match unrelated runtime events`() = runBlocking {
        val registry = FeatureRegistry().apply { AndroidBatteryEventFeaturePack().install(this) }
        assertFalse(matches(
            registry,
            "android.event.battery_status_filtered",
            mapOf("value" to ConfigValue.StringValue("charging")),
            runtimeType = "android.event.screen_on",
        ))
    }

    @Test
    fun `battery integer values normalize to stable automation names`() {
        assertEquals("ac", batteryPluggedName(BatteryManager.BATTERY_PLUGGED_AC))
        assertEquals("usb", batteryPluggedName(BatteryManager.BATTERY_PLUGGED_USB))
        assertEquals("wireless", batteryPluggedName(BatteryManager.BATTERY_PLUGGED_WIRELESS))
        assertEquals("dock", batteryPluggedName(BatteryManager.BATTERY_PLUGGED_DOCK))
        assertEquals("none", batteryPluggedName(0))

        assertEquals("charging", batteryStatusName(BatteryManager.BATTERY_STATUS_CHARGING))
        assertEquals("full", batteryStatusName(BatteryManager.BATTERY_STATUS_FULL))
        assertEquals("good", batteryHealthName(BatteryManager.BATTERY_HEALTH_GOOD))
        assertEquals("overheat", batteryHealthName(BatteryManager.BATTERY_HEALTH_OVERHEAT))
    }
}
