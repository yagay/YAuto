package com.yagay.yauto.platform.android

import com.yagay.yauto.core.capability.*
import com.yagay.yauto.core.logging.*
import com.yagay.yauto.core.model.*
import com.yagay.yauto.core.registry.*
import com.yagay.yauto.core.runtime.AutomationRuntime
import com.yagay.yauto.core.storage.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AndroidStateFeaturePackTest {
    private class Reader : AndroidStateReader {
        var on = true
        var types = setOf("wifi", "vpn", "connected")
        var percent: Double? = 50.0
        var temperatureC: Double? = 30.0
        var mediaPercent: Double? = 40.0
        var brightness: Double? = 60.0
        var automaticBrightness = false
        var nfc = true
        var location = true
        var rotate = true
        var dark = false
        var stayAwake = false
        var timeoutMs: Double? = 30_000.0
        var headset = false
        var failure = false
        override fun screenOn(): Boolean { if (failure) error("unavailable"); return on }
        override fun networkTypes() = types
        override fun charging() = true
        override fun batteryPercent() = percent
        override fun batteryTemperatureC() = temperatureC
        override fun powerSave() = false
        override fun appInstalled(packageName: String) = packageName == "installed.app"
        override fun mediaVolumePercent() = mediaPercent
        override fun brightnessPercent() = brightness
        override fun autoBrightness() = automaticBrightness
        override fun nfcEnabled() = nfc
        override fun locationEnabled() = location
        override fun autoRotate() = rotate
        override fun darkMode() = dark
        override fun stayAwakeWhileCharging() = stayAwake
        override fun screenTimeoutMs() = timeoutMs
        override fun headsetConnected() = headset
    }
    private val reader = Reader()
    private val registry = FeatureRegistry().apply { install(AndroidStateFeaturePack(reader)) }
    private val tracer = InMemoryExecutionTracer()
    private val capabilities = CapabilityClient { CapabilityResult(false, message = "unused") }
    private val variables = object : VariableAccess {
        override fun get(name: String): ConfigValue? = if (name == "pkg") ConfigValue.StringValue("installed.app") else null
        override fun set(name: String, value: ConfigValue) = Unit
        override fun snapshot() = emptyMap<String, ConfigValue>()
    }
    private val ctx = FeatureExecutionContext(ExecutionId("test"), null, variables, capabilities, tracer)
    private suspend fun matches(key: String, config: ConfigMap = emptyMap()) =
        registry.stateEvaluator("android.state.$key")!!.evaluate(FeatureRef("android.state.$key", config = config), ctx)

    @Test fun `registry states query current values and remain removable as a pack`() = runBlocking {
        assertEquals(32, registry.allDescriptors().size)
        assertTrue(matches("screen"))
        reader.on = false
        assertFalse(matches("screen"))
        assertTrue(matches("screen", mapOf("value" to ConfigValue.BooleanValue(false))))
        assertTrue(matches("charging"))
        assertTrue(matches("power_save", mapOf("value" to ConfigValue.BooleanValue(false))))
        assertTrue(matches("network", mapOf("type" to ConfigValue.StringValue("vpn"))))
        assertTrue(matches("network", mapOf("type" to ConfigValue.StringValue("wifi"))))
        reader.types = setOf("none")
        assertFalse(matches("network"))
        assertTrue(matches("network", mapOf("type" to ConfigValue.StringValue("none"))))
        assertTrue(matches("app_installed", mapOf("package" to ConfigValue.StringValue("installed.app"))))
        assertTrue(matches("app_installed", mapOf("package" to ConfigValue.StringValue("missing.app"), "value" to ConfigValue.BooleanValue(false))))
        registry.uninstallPack("android.state")
        assertTrue(registry.allDescriptors().isEmpty())
        assertNull(registry.stateEvaluator("android.state.screen"))
    }

    @Test fun `new device states are live and configurable`() = runBlocking {
        assertTrue(matches("nfc_enabled"))
        reader.nfc = false
        assertTrue(matches("nfc_enabled", mapOf("value" to ConfigValue.BooleanValue(false))))

        assertTrue(matches("location_enabled"))
        reader.location = false
        assertTrue(matches("location_enabled", mapOf("value" to ConfigValue.BooleanValue(false))))

        assertTrue(matches("auto_rotate"))
        reader.rotate = false
        assertTrue(matches("auto_rotate", mapOf("value" to ConfigValue.BooleanValue(false))))

        assertFalse(matches("dark_mode"))
        reader.dark = true
        assertTrue(matches("dark_mode"))

        assertFalse(matches("stay_awake_while_charging"))
        reader.stayAwake = true
        assertTrue(matches("stay_awake_while_charging"))

        assertFalse(matches("headset_connected"))
        reader.headset = true
        assertTrue(matches("headset_connected"))

        assertTrue(matches("screen_timeout", mapOf(
            "minMs" to ConfigValue.NumberValue(20_000.0),
            "maxMs" to ConfigValue.NumberValue(40_000.0),
        )))
        reader.timeoutMs = 60_000.0
        assertFalse(matches("screen_timeout", mapOf("maxMs" to ConfigValue.NumberValue(40_000.0))))
    }

    @Test fun `battery temperature comparison is live and bounded`() = runBlocking {
        assertTrue(matches("battery_temperature", mapOf(
            "operator" to ConfigValue.StringValue(">="),
            "value" to ConfigValue.NumberValue(30.0),
        )))
        reader.temperatureC = 29.9
        assertFalse(matches("battery_temperature", mapOf(
            "operator" to ConfigValue.StringValue(">="),
            "value" to ConfigValue.NumberValue(30.0),
        )))
        reader.temperatureC = 30.04
        assertTrue(matches("battery_temperature", mapOf(
            "operator" to ConfigValue.StringValue("=="),
            "value" to ConfigValue.NumberValue(30.0),
        )))
        reader.temperatureC = null
        assertFalse(matches("battery_temperature", mapOf("value" to ConfigValue.NumberValue(30.0))))
        assertFalse(matches("battery_temperature", mapOf("value" to ConfigValue.NumberValue(200.0))))
    }

    @Test fun `media volume and brightness comparisons use current live values`() = runBlocking {
        assertTrue(matches("media_volume", mapOf("operator" to ConfigValue.StringValue("<"), "value" to ConfigValue.NumberValue(50.0))))
        assertFalse(matches("media_volume", mapOf("operator" to ConfigValue.StringValue(">"), "value" to ConfigValue.NumberValue(50.0))))
        reader.mediaPercent = 50.2
        assertTrue(matches("media_volume", mapOf("operator" to ConfigValue.StringValue("=="), "value" to ConfigValue.NumberValue(50.0))))

        assertTrue(matches("brightness", mapOf(
            "mode" to ConfigValue.StringValue("manual"),
            "compareLevel" to ConfigValue.BooleanValue(true),
            "operator" to ConfigValue.StringValue(">"),
            "value" to ConfigValue.NumberValue(50.0),
        )))
        reader.automaticBrightness = true
        assertFalse(matches("brightness", mapOf("mode" to ConfigValue.StringValue("manual"), "compareLevel" to ConfigValue.BooleanValue(false))))
        assertTrue(matches("brightness", mapOf("mode" to ConfigValue.StringValue("auto"), "compareLevel" to ConfigValue.BooleanValue(false))))
        reader.brightness = null
        assertFalse(matches("brightness", mapOf("mode" to ConfigValue.StringValue("auto"), "compareLevel" to ConfigValue.BooleanValue(true), "value" to ConfigValue.NumberValue(20.0))))
    }

    @Test fun `battery bounds are inclusive and unknown or invalid values fail closed`() = runBlocking {
        val range = mapOf("min" to ConfigValue.NumberValue(50.0), "max" to ConfigValue.NumberValue(50.0))
        assertTrue(matches("battery_level", range))
        reader.percent = 49.9
        assertFalse(matches("battery_level", range))
        reader.percent = null
        assertFalse(matches("battery_level"))
        assertEquals(false, tracer.snapshot().last().success)
        assertFalse(matches("battery_level", mapOf("min" to ConfigValue.NumberValue(101.0))))
        assertFalse(matches("app_installed"))
        reader.failure = true
        assertFalse(matches("screen"))
        val trace = tracer.snapshot().last()
        assertEquals(TraceKind.STATE, trace.kind)
        assertEquals("android", trace.backendId)
        assertEquals("android.state.screen", trace.featureId)
        assertEquals(false, trace.success)
    }

    @Test fun `state changes run enter and exit once through runtime`() = runBlocking {
        val phases = mutableListOf<String>()
        for (phase in listOf("enter", "exit")) registry.registerAction(
            FeatureDescriptor(FeatureId(phase), FeatureKind.ACTION, phase, "", FeatureCategory.CORE)
        ) { _, _ -> phases += phase; ActionExecutionResult(true) }
        val automation = Automation(AutomationId("state-test"), "State test",
            activation = Activation(states = listOf(FeatureRef("android.state.screen"))),
            onEnter = listOf(ActionNode.Action(NodeId("enter"), FeatureRef("enter"))),
            onExit = listOf(ActionNode.Action(NodeId("exit"), FeatureRef("exit"))))
        val repository = object : WorkspaceRepository {
            override suspend fun load() = WorkspaceData(automations = listOf(automation))
            override suspend fun save(data: WorkspaceData) = Unit
        }
        val runtime = AutomationRuntime(repository, registry, capabilities, tracer)
        runtime.dispatch(RuntimeEvent("android.event.runtime_started"))
        runtime.dispatch(RuntimeEvent("android.event.screen_on"))
        reader.on = false
        runtime.dispatch(RuntimeEvent("android.event.screen_off"))
        runtime.dispatch(RuntimeEvent("android.event.screen_off"))
        assertEquals(listOf("enter", "exit"), phases)
    }
}
