package com.yagay.yauto.platform.android

import com.yagay.yauto.core.capability.CapabilityClient
import com.yagay.yauto.core.capability.CapabilityResult
import com.yagay.yauto.core.logging.NoOpExecutionTracer
import com.yagay.yauto.core.model.*
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AndroidEventFeaturePackTest {
    // Canonical battery matcher lives in its own feature pack; mirror the production registry.
    private val registry = FeatureRegistry().apply {
        install(AndroidEventFeaturePack())
        install(AndroidBatteryEventFeaturePack())
    }
    private val variables = object : VariableAccess {
        override fun get(name: String): ConfigValue? = null
        override fun set(name: String, value: ConfigValue) = Unit
        override fun snapshot(): Map<String, ConfigValue> = emptyMap()
    }
    private val capabilities = CapabilityClient { CapabilityResult(false, message = "unused") }

    private suspend fun matches(
        id: String,
        config: ConfigMap = emptyMap(),
        payload: ConfigMap = emptyMap(),
    ): Boolean {
        val matcher = requireNotNull(registry.eventMatcher(id))
        return matcher.matches(
            FeatureRef(id, config = config),
            EventMatchContext(
                executionId = ExecutionId("test"),
                event = RuntimeEvent(id, payload),
                variables = variables,
                capabilities = capabilities,
                tracer = NoOpExecutionTracer,
            ),
        )
    }

    @Test fun `boolean change events support any on and off filters`() = runBlocking {
        for (id in listOf(
            "android.event.power_save_changed",
            "android.event.airplane_mode_changed",
            "android.event.nfc_state_changed",
            "android.event.location_mode_changed",
            "android.event.dark_mode_changed",
            "android.event.auto_rotate_changed",
        )) {
            val payloadKey = if (id == "android.event.airplane_mode_changed") "state" else "enabled"
            assertTrue(matches(id, payload = mapOf(payloadKey to ConfigValue.BooleanValue(true))))
            assertTrue(matches(id, mapOf("state" to ConfigValue.StringValue("on")), mapOf(payloadKey to ConfigValue.BooleanValue(true))))
            assertFalse(matches(id, mapOf("state" to ConfigValue.StringValue("off")), mapOf(payloadKey to ConfigValue.BooleanValue(true))))
            assertTrue(matches(id, mapOf("state" to ConfigValue.StringValue("off")), mapOf(payloadKey to ConfigValue.BooleanValue(false))))
        }
    }

    @Test fun `screen timeout event filters by inclusive range`() = runBlocking {
        val id = "android.event.screen_timeout_changed"
        val payload = mapOf("timeoutMs" to ConfigValue.NumberValue(30_000.0))
        assertTrue(matches(id, payload = payload))
        assertTrue(matches(id, mapOf(
            "minMs" to ConfigValue.NumberValue(30_000.0),
            "maxMs" to ConfigValue.NumberValue(30_000.0),
        ), payload))
        assertFalse(matches(id, mapOf("minMs" to ConfigValue.NumberValue(31_000.0)), payload))
        assertFalse(matches(id, mapOf(
            "minMs" to ConfigValue.NumberValue(40_000.0),
            "maxMs" to ConfigValue.NumberValue(20_000.0),
        ), payload))
    }

    @Test fun `battery event can filter level and temperature independently`() = runBlocking {
        val id = "android.event.battery_changed"
        val payload = mapOf(
            "percent" to ConfigValue.NumberValue(55.0),
            "temperatureC" to ConfigValue.NumberValue(31.5),
        )
        assertTrue(matches(id, payload = payload))
        assertTrue(matches(id, mapOf(
            "minPercent" to ConfigValue.NumberValue(50.0),
            "maxPercent" to ConfigValue.NumberValue(60.0),
            "minTemperatureC" to ConfigValue.NumberValue(30.0),
            "maxTemperatureC" to ConfigValue.NumberValue(32.0),
        ), payload))
        assertFalse(matches(id, mapOf("minPercent" to ConfigValue.NumberValue(56.0)), payload))
        assertFalse(matches(id, mapOf("maxTemperatureC" to ConfigValue.NumberValue(31.0)), payload))
    }

    @Test fun `headset event filters connection category and device name`() = runBlocking {
        val id = "android.event.headset_changed"
        val payload = mapOf(
            "connected" to ConfigValue.BooleanValue(true),
            "category" to ConfigValue.StringValue("bluetooth"),
            "name" to ConfigValue.StringValue("Pixel Buds Pro"),
        )
        assertTrue(matches(id, payload = payload))
        assertTrue(matches(id, mapOf(
            "state" to ConfigValue.StringValue("connected"),
            "category" to ConfigValue.StringValue("bluetooth"),
            "nameContains" to ConfigValue.StringValue("buds"),
        ), payload))
        assertFalse(matches(id, mapOf("state" to ConfigValue.StringValue("disconnected")), payload))
        assertFalse(matches(id, mapOf("category" to ConfigValue.StringValue("wired")), payload))
        assertFalse(matches(id, mapOf("nameContains" to ConfigValue.StringValue("Sony")), payload))
    }

    @Test fun `wrong event type never matches`() = runBlocking {
        val matcher = requireNotNull(registry.eventMatcher("android.event.nfc_state_changed"))
        val result = matcher.matches(
            FeatureRef("android.event.nfc_state_changed"),
            EventMatchContext(
                executionId = ExecutionId("test"),
                event = RuntimeEvent("android.event.screen_on"),
                variables = variables,
                capabilities = capabilities,
                tracer = NoOpExecutionTracer,
            ),
        )
        assertFalse(result)
    }
}
