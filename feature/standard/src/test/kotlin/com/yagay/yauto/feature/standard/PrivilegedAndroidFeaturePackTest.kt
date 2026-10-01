package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.capability.*
import com.yagay.yauto.core.logging.NoOpExecutionTracer
import com.yagay.yauto.core.model.*
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PrivilegedAndroidFeaturePackTest {
    private class Vars : VariableAccess {
        private val values = mutableMapOf<String, ConfigValue>()
        override fun get(name: String): ConfigValue? = values[name]
        override fun set(name: String, value: ConfigValue) { values[name] = value }
        override fun snapshot(): Map<String, ConfigValue> = values.toMap()
    }

    private fun context(client: CapabilityClient) = FeatureExecutionContext(
        ExecutionId("test"), NodeId("node"), Vars(), client, NoOpExecutionTracer,
    )

    @Test fun `clear data emits semantic privileged command`() = runBlocking {
        val registry = FeatureRegistry().apply { install(PrivilegedAndroidFeaturePack()) }
        var captured: CapabilityRequest? = null
        val feature = FeatureRef("android.app.clear_data", config = mapOf("package" to ConfigValue.StringValue("com.example.app")))
        val result = registry.actionExecutor(feature.typeId)!!.execute(feature, context(CapabilityClient { request ->
            captured = request
            CapabilityResult(true, backendId = "root")
        }))
        assertTrue(result.success)
        assertEquals(CapabilityIds.PRIVILEGED_SHELL, captured?.capability)
        assertEquals("android.app.clear_data", captured?.operationId)
        assertEquals("pm clear 'com.example.app'", (captured?.payload?.get("command") as ConfigValue.StringValue).value)
    }

    @Test fun `invalid package fails before capability invocation`() = runBlocking {
        val registry = FeatureRegistry().apply { install(PrivilegedAndroidFeaturePack()) }
        var invoked = false
        val feature = FeatureRef("android.app.enabled.set", config = mapOf(
            "package" to ConfigValue.StringValue("bad package; reboot"),
            "enabled" to ConfigValue.BooleanValue(false),
        ))
        val result = registry.actionExecutor(feature.typeId)!!.execute(feature, context(CapabilityClient {
            invoked = true
            CapabilityResult(true)
        }))
        assertFalse(result.success)
        assertFalse(invoked)
    }

    @Test fun `system setting value is shell quoted`() = runBlocking {
        val registry = FeatureRegistry().apply { install(PrivilegedAndroidFeaturePack()) }
        var command = ""
        val feature = FeatureRef("android.settings.put", config = mapOf(
            "namespace" to ConfigValue.StringValue("secure"),
            "key" to ConfigValue.StringValue("demo_key"),
            "value" to ConfigValue.StringValue("a'b c"),
        ))
        val result = registry.actionExecutor(feature.typeId)!!.execute(feature, context(CapabilityClient { request ->
            command = (request.payload["command"] as ConfigValue.StringValue).value
            CapabilityResult(true, backendId = "shizuku")
        }))
        assertTrue(result.success)
        assertEquals("settings put secure 'demo_key' 'a'\\''b c'", command)
    }

    @Test fun `whole pack can be removed cleanly`() {
        val registry = FeatureRegistry().apply { install(PrivilegedAndroidFeaturePack()) }
        assertEquals(7, registry.allDescriptors().count { it.ownerPackId == "standard.android.privileged" })
        registry.uninstallPack("standard.android.privileged")
        assertTrue(registry.allDescriptors().none { it.ownerPackId == "standard.android.privileged" })
    }
}
