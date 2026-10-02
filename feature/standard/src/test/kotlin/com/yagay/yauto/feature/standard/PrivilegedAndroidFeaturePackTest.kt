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
        assertNull(captured?.preferredBackendId)
        assertEquals("pm clear 'com.example.app'", (captured?.payload?.get("command") as ConfigValue.StringValue).value)
    }

    @Test fun `privileged descriptors expose language neutral backend choices`() {
        val registry = FeatureRegistry().apply { install(PrivilegedAndroidFeaturePack()) }
        val descriptor = requireNotNull(registry.descriptor("android.app.clear_data"))
        val backend = descriptor.fields.filterIsInstance<FieldSchema.Choice>()
            .first { it.key == FEATURE_BACKEND_CONFIG_KEY }
        assertEquals(listOf("auto", "root", "shizuku"), backend.options)
        assertEquals(FEATURE_BACKEND_CONFIG_KEY, backend.label)
        assertEquals(
            listOf("root", "shizuku"),
            descriptor.resolvedImplementationOptions().mapNotNull { it.backendId },
        )
        assertEquals(
            setOf(AccessRequirement.ROOT, AccessRequirement.SHIZUKU),
            descriptor.resolvedAccessRequirements(),
        )
    }

    @Test fun `system ui exposes LSPosed Root and Shizuku implementations`() {
        val registry = FeatureRegistry().apply { install(SystemFeaturePack()) }
        val descriptor = requireNotNull(registry.descriptor(SystemOperations.SLEEP))
        val backend = descriptor.fields.filterIsInstance<FieldSchema.Choice>()
            .first { it.key == FEATURE_BACKEND_CONFIG_KEY }
        assertEquals(listOf("auto", "lsposed", "root", "shizuku"), backend.options)
        val implementations = descriptor.resolvedImplementationOptions()
        assertEquals(listOf("lsposed", "root", "shizuku"), implementations.mapNotNull { it.backendId })
        assertTrue(implementations.first { it.backendId == "lsposed" }.restartRequired)
    }

    @Test fun `selected backend is forwarded by privileged feature`() = runBlocking {
        val registry = FeatureRegistry().apply { install(PrivilegedAndroidFeaturePack()) }
        var captured: CapabilityRequest? = null
        val feature = FeatureRef("android.app.clear_data", config = mapOf(
            "package" to ConfigValue.StringValue("com.example.app"),
            FEATURE_BACKEND_CONFIG_KEY to ConfigValue.StringValue("root"),
        ))
        val result = registry.actionExecutor(feature.typeId)!!.execute(feature, context(CapabilityClient { request ->
            captured = request
            CapabilityResult(true, backendId = "root")
        }))
        assertTrue(result.success)
        assertEquals("root", captured?.preferredBackendId)
    }

    @Test fun `registry wrapper applies backend to direct capability calls`() = runBlocking {
        val registry = FeatureRegistry()
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("test.direct.capability"), FeatureKind.ACTION, "Direct", "Direct capability call",
                FeatureCategory.SYSTEM, capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
            )
        ) { _, ctx ->
            val result = ctx.capabilities.execute(CapabilityRequest(CapabilityIds.PRIVILEGED_SHELL, "direct"))
            ActionExecutionResult(result.success, result.value, result.message)
        }
        var captured: CapabilityRequest? = null
        val feature = FeatureRef("test.direct.capability", config = mapOf(
            FEATURE_BACKEND_CONFIG_KEY to ConfigValue.StringValue("shizuku"),
        ))
        val result = registry.actionExecutor(feature.typeId)!!.execute(feature, context(CapabilityClient { request ->
            captured = request
            CapabilityResult(true, backendId = request.preferredBackendId)
        }))
        assertTrue(result.success)
        assertEquals("shizuku", captured?.preferredBackendId)
    }

    @Test fun `broker auto falls back but explicit backend stays strict`() = runBlocking {
        class FakeBackend(
            override val id: String,
            override val priority: Int,
            private val succeeds: Boolean,
        ) : CapabilityBackend {
            override suspend fun isAvailable(environment: RuntimeEnvironment) = true
            override fun supports(request: CapabilityRequest, environment: RuntimeEnvironment) =
                request.capability == CapabilityIds.PRIVILEGED_SHELL
            override suspend fun execute(request: CapabilityRequest, environment: RuntimeEnvironment) =
                CapabilityResult(succeeds, message = if (succeeds) null else "$id failed")
        }

        val broker = CapabilityBroker(
            environmentProvider = { RuntimeEnvironment(31) },
            backends = listOf(FakeBackend("root", 80, false), FakeBackend("shizuku", 70, true)),
        )
        val auto = broker.execute(CapabilityRequest(CapabilityIds.PRIVILEGED_SHELL, "test"))
        assertTrue(auto.success)
        assertEquals("shizuku", auto.backendId)
        assertEquals(listOf("root", "shizuku"), auto.attempts.map { it.backendId })

        val rootOnly = broker.execute(CapabilityRequest(CapabilityIds.PRIVILEGED_SHELL, "test", preferredBackendId = "root"))
        assertFalse(rootOnly.success)
        assertEquals("root", rootOnly.backendId)
        assertEquals(listOf("root"), rootOnly.attempts.map { it.backendId })
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

    @Test fun `connectivity toggles use stable svc entry points`() = runBlocking {
        val registry = FeatureRegistry().apply { install(PrivilegedAndroidFeaturePack()) }
        val commands = mutableMapOf<String, String>()
        val client = CapabilityClient { request ->
            commands[request.operationId] = (request.payload["command"] as ConfigValue.StringValue).value
            CapabilityResult(true, backendId = "shizuku")
        }
        for ((id, enabled) in listOf(
            "android.wifi.set" to false,
            "android.mobile_data.set" to true,
            "android.bluetooth.set" to false,
            "android.nfc.set" to true,
        )) {
            registry.actionExecutor(id)!!.execute(
                FeatureRef(id, config = mapOf("enabled" to ConfigValue.BooleanValue(enabled))),
                context(client),
            )
        }
        assertEquals("svc wifi disable", commands["android.wifi.set"])
        assertEquals("svc data enable", commands["android.mobile_data.set"])
        assertEquals("svc bluetooth disable", commands["android.bluetooth.set"])
        assertEquals("svc nfc enable", commands["android.nfc.set"])
    }

    @Test fun `device controls emit stable Android shell commands`() = runBlocking {
        val registry = FeatureRegistry().apply { install(PrivilegedAndroidFeaturePack()) }
        val commands = mutableMapOf<String, String>()
        val client = CapabilityClient { request ->
            commands[request.operationId] = (request.payload["command"] as ConfigValue.StringValue).value
            CapabilityResult(true, backendId = "root")
        }
        suspend fun execute(id: String, config: ConfigMap) {
            val result = registry.actionExecutor(id)!!.execute(FeatureRef(id, config = config), context(client))
            assertTrue(result.success)
        }

        execute("android.location.enabled.set", mapOf("enabled" to ConfigValue.BooleanValue(false)))
        execute("android.display.auto_rotate.set", mapOf("enabled" to ConfigValue.BooleanValue(true)))
        execute("android.display.screen_timeout.set", mapOf("timeoutMs" to ConfigValue.NumberValue(60_000.0)))
        execute("android.display.dark_mode.set", mapOf("mode" to ConfigValue.StringValue("dark")))
        execute("android.power.battery_saver.set", mapOf("enabled" to ConfigValue.BooleanValue(true)))
        execute("android.power.stay_awake.set", mapOf("mode" to ConfigValue.StringValue("wireless")))

        assertEquals("cmd location set-location-enabled false", commands["android.location.enabled.set"])
        assertEquals("settings put system accelerometer_rotation 1", commands["android.display.auto_rotate.set"])
        assertEquals("settings put system screen_off_timeout 60000", commands["android.display.screen_timeout.set"])
        assertEquals("cmd uimode night yes", commands["android.display.dark_mode.set"])
        assertEquals("cmd power set-mode 1", commands["android.power.battery_saver.set"])
        assertEquals("svc power stayon wireless", commands["android.power.stay_awake.set"])
    }

    @Test fun `screen timeout rejects unsafe values before shell execution`() = runBlocking {
        val registry = FeatureRegistry().apply { install(PrivilegedAndroidFeaturePack()) }
        var invoked = false
        val result = registry.actionExecutor("android.display.screen_timeout.set")!!.execute(
            FeatureRef("android.display.screen_timeout.set", config = mapOf("timeoutMs" to ConfigValue.NumberValue(999.0))),
            context(CapabilityClient {
                invoked = true
                CapabilityResult(true)
            }),
        )
        assertFalse(result.success)
        assertFalse(invoked)
    }

    @Test fun `whole pack can be removed cleanly`() {
        val registry = FeatureRegistry().apply { install(PrivilegedAndroidFeaturePack()) }
        assertEquals(18, registry.allDescriptors().count { it.ownerPackId == "standard.android.privileged" })
        registry.uninstallPack("standard.android.privileged")
        assertTrue(registry.allDescriptors().none { it.ownerPackId == "standard.android.privileged" })
    }
}
