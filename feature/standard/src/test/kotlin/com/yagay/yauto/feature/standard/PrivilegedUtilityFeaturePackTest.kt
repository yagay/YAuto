package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.capability.CapabilityClient
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.capability.CapabilityResult
import com.yagay.yauto.core.logging.NoOpExecutionTracer
import com.yagay.yauto.core.model.*
import com.yagay.yauto.core.registry.FeatureExecutionContext
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.VariableAccess
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PrivilegedUtilityFeaturePackTest {
    private class Vars : VariableAccess {
        private val values = mutableMapOf<String, ConfigValue>()
        override fun get(name: String): ConfigValue? = values[name]
        override fun set(name: String, value: ConfigValue) { values[name] = value }
        override fun snapshot(): Map<String, ConfigValue> = values.toMap()
    }

    private fun context(client: CapabilityClient, vars: Vars = Vars()) = FeatureExecutionContext(
        ExecutionId("test"), NodeId("node"), vars, client, NoOpExecutionTracer,
    )

    @Test fun `pack exposes four removable utility actions`() {
        val registry = FeatureRegistry().apply { install(PrivilegedUtilityFeaturePack()) }
        assertEquals(4, registry.allDescriptors().count { it.ownerPackId == "standard.android.utilities" })
        assertNotNull(registry.actionExecutor("android.screen.wake"))
        assertNotNull(registry.actionExecutor("android.input.keyevent"))
        assertNotNull(registry.actionExecutor("android.screenshot.capture"))
        assertNotNull(registry.actionExecutor("android.screen.record"))
        registry.uninstallPack("standard.android.utilities")
        assertTrue(registry.allDescriptors().none { it.ownerPackId == "standard.android.utilities" })
    }

    @Test fun `wake screen sends Android wake key event`() = runBlocking {
        val registry = FeatureRegistry().apply { install(PrivilegedUtilityFeaturePack()) }
        var captured: CapabilityRequest? = null
        val result = registry.actionExecutor("android.screen.wake")!!.execute(
            FeatureRef("android.screen.wake"),
            context(CapabilityClient { request ->
                captured = request
                CapabilityResult(true, backendId = "root")
            }),
        )
        assertTrue(result.success)
        assertEquals("input keyevent 224", (captured?.payload?.get("command") as ConfigValue.StringValue).value)
    }

    @Test fun `named and long press key events map to stable Android codes`() = runBlocking {
        val registry = FeatureRegistry().apply { install(PrivilegedUtilityFeaturePack()) }
        val commands = mutableListOf<String>()
        val client = CapabilityClient { request ->
            commands += (request.payload["command"] as ConfigValue.StringValue).value
            CapabilityResult(true, backendId = "shizuku")
        }
        registry.actionExecutor("android.input.keyevent")!!.execute(
            FeatureRef("android.input.keyevent", config = mapOf("key" to ConfigValue.StringValue("home"))),
            context(client),
        )
        registry.actionExecutor("android.input.keyevent")!!.execute(
            FeatureRef("android.input.keyevent", config = mapOf(
                "key" to ConfigValue.StringValue("media_next"),
                "longPress" to ConfigValue.BooleanValue(true),
            )),
            context(client),
        )
        assertEquals(listOf("input keyevent 3", "input keyevent --longpress 87"), commands)
    }

    @Test fun `custom key code rejects invalid values before invoking backend`() = runBlocking {
        val registry = FeatureRegistry().apply { install(PrivilegedUtilityFeaturePack()) }
        var invoked = false
        val result = registry.actionExecutor("android.input.keyevent")!!.execute(
            FeatureRef("android.input.keyevent", config = mapOf(
                "key" to ConfigValue.StringValue("custom"),
                "customKeyCode" to ConfigValue.NumberValue(1001.0),
            )),
            context(CapabilityClient {
                invoked = true
                CapabilityResult(true)
            }),
        )
        assertFalse(result.success)
        assertFalse(invoked)
    }

    @Test fun `screenshot uses quoted shared-storage path and stores result variable`() = runBlocking {
        val registry = FeatureRegistry().apply { install(PrivilegedUtilityFeaturePack()) }
        val vars = Vars()
        var command = ""
        val path = "/sdcard/Download/YAuto/user's shot.png"
        val result = registry.actionExecutor("android.screenshot.capture")!!.execute(
            FeatureRef("android.screenshot.capture", config = mapOf(
                "path" to ConfigValue.StringValue(path),
                "resultVariable" to ConfigValue.StringValue("shot"),
            )),
            context(CapabilityClient { request ->
                command = (request.payload["command"] as ConfigValue.StringValue).value
                CapabilityResult(true, backendId = "root")
            }, vars),
        )
        assertTrue(result.success)
        assertEquals("mkdir -p '/sdcard/Download/YAuto' && screencap -p '/sdcard/Download/YAuto/user'\\''s shot.png'", command)
        assertEquals(ConfigValue.StringValue(path), vars.get("shot"))
        assertEquals(ConfigValue.StringValue(path), result.value)
    }

    @Test fun `screenshot rejects paths outside shared storage`() = runBlocking {
        val registry = FeatureRegistry().apply { install(PrivilegedUtilityFeaturePack()) }
        var invoked = false
        val result = registry.actionExecutor("android.screenshot.capture")!!.execute(
            FeatureRef("android.screenshot.capture", config = mapOf(
                "path" to ConfigValue.StringValue("/data/local/tmp/shot.png"),
            )),
            context(CapabilityClient {
                invoked = true
                CapabilityResult(true)
            }),
        )
        assertFalse(result.success)
        assertFalse(invoked)
    }

    @Test fun `screen recording uses bounded screenrecord command and stores path`() = runBlocking {
        val registry = FeatureRegistry().apply { install(PrivilegedUtilityFeaturePack()) }
        val vars = Vars()
        var command = ""
        val path = "/sdcard/Download/YAuto/demo record.mp4"
        val result = registry.actionExecutor("android.screen.record")!!.execute(
            FeatureRef("android.screen.record", config = mapOf(
                "path" to ConfigValue.StringValue(path),
                "durationSeconds" to ConfigValue.NumberValue(45.0),
                "bitrateMbps" to ConfigValue.NumberValue(8.0),
                "resultVariable" to ConfigValue.StringValue("recording"),
            )),
            context(CapabilityClient { request ->
                command = (request.payload["command"] as ConfigValue.StringValue).value
                CapabilityResult(true, backendId = "root")
            }, vars),
        )
        assertTrue(result.success)
        assertEquals("mkdir -p '/sdcard/Download/YAuto' && screenrecord --time-limit 45 --bit-rate 8000000 '/sdcard/Download/YAuto/demo record.mp4'", command)
        assertEquals(ConfigValue.StringValue(path), vars.get("recording"))
    }

    @Test fun `screen recording rejects unsafe duration before backend`() = runBlocking {
        val registry = FeatureRegistry().apply { install(PrivilegedUtilityFeaturePack()) }
        var invoked = false
        val result = registry.actionExecutor("android.screen.record")!!.execute(
            FeatureRef("android.screen.record", config = mapOf(
                "path" to ConfigValue.StringValue("/sdcard/Download/YAuto/demo.mp4"),
                "durationSeconds" to ConfigValue.NumberValue(181.0),
            )),
            context(CapabilityClient {
                invoked = true
                CapabilityResult(true)
            }),
        )
        assertFalse(result.success)
        assertFalse(invoked)
    }
}
