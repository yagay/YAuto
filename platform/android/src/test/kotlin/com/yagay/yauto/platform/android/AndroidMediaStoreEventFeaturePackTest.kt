package com.yagay.yauto.platform.android

import com.yagay.yauto.core.capability.CapabilityClient
import com.yagay.yauto.core.capability.CapabilityResult
import com.yagay.yauto.core.logging.NoOpExecutionTracer
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
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidMediaStoreEventFeaturePackTest {
    private val variables = object : VariableAccess {
        override fun get(name: String): ConfigValue? = null
        override fun set(name: String, value: ConfigValue) = Unit
        override fun snapshot(): Map<String, ConfigValue> = emptyMap()
    }
    private val capabilities = CapabilityClient { CapabilityResult(false, message = "unused") }

    @Test
    fun `MediaStore pack exposes one canonical trigger and migrates convenience aliases`() = runBlocking {
        val registry = FeatureRegistry().apply { AndroidMediaStoreEventFeaturePack().install(this) }

        assertEquals(1, registry.allDescriptors().size)
        assertEquals("android.event.media_store_changed", registry.allDescriptors().single().id.value)

        val photo = registry.canonicalRef(
            FeatureRef(
                "android.event.photo_taken",
                config = mapOf("uriContains" to ConfigValue.StringValue("DCIM")),
            ),
            FeatureKind.EVENT,
        )
        assertEquals("android.event.media_store_changed", photo.typeId)
        assertEquals(ConfigValue.StringValue("inserted"), photo.config["changeType"])
        assertEquals(ConfigValue.StringValue("images"), photo.config["collection"])
        assertEquals(ConfigValue.StringValue("DCIM"), photo.config["uriContains"])

        val matcher = registry.eventMatcher("android.event.photo_taken")!!
        val match = matcher.matches(
            FeatureRef(
                "android.event.photo_taken",
                config = mapOf("uriContains" to ConfigValue.StringValue("DCIM")),
            ),
            EventMatchContext(
                executionId = ExecutionId("media-store-test"),
                event = RuntimeEvent(
                    typeId = "android.event.media_store_changed",
                    payload = mapOf(
                        "changeType" to ConfigValue.StringValue("inserted"),
                        "collection" to ConfigValue.StringValue("images"),
                        "uri" to ConfigValue.StringValue("content://media/DCIM/42"),
                    ),
                ),
                variables = variables,
                capabilities = capabilities,
                tracer = NoOpExecutionTracer,
            ),
        )
        assertTrue(match)

        val wrongType = registry.eventMatcher("android.event.media_store_deleted")!!.matches(
            FeatureRef("android.event.media_store_deleted"),
            EventMatchContext(
                executionId = ExecutionId("media-store-test-2"),
                event = RuntimeEvent(
                    typeId = "android.event.media_store_changed",
                    payload = mapOf(
                        "changeType" to ConfigValue.StringValue("updated"),
                        "collection" to ConfigValue.StringValue("images"),
                        "uri" to ConfigValue.StringValue("content://media/images/42"),
                    ),
                ),
                variables = variables,
                capabilities = capabilities,
                tracer = NoOpExecutionTracer,
            ),
        )
        assertFalse(wrongType)
    }
}
