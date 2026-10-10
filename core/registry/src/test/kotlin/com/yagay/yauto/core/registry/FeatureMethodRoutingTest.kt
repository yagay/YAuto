package com.yagay.yauto.core.registry

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FeatureMethodRoutingTest {
    @Test fun explicitNormalModeNeverCallsPrivilege() = runBlocking {
        var elevated = 0
        val result = routeFeatureMethod(FeatureMethod.MACRODROID, true, { false }, { elevated++; true }, { it })
        assertEquals(false, result)
        assertEquals(0, elevated)
        val unsupported = routeFeatureMethod(FeatureMethod.MACRODROID, false, { true }, { elevated++; true }, { it })
        assertNull(unsupported)
        assertEquals(0, elevated)
    }

    @Test fun explicitShortXNeverCallsNormalPath() = runBlocking {
        var normalCalls = 0
        val result = routeFeatureMethod(FeatureMethod.SHORTX, true, { normalCalls++; true }, { false }, { it })
        assertEquals(false, result)
        assertEquals(0, normalCalls)
    }

    @Test fun autoTriesNonRootThenPrivilegeWhenNeeded() = runBlocking {
        val order = mutableListOf<String>()
        val result = routeFeatureMethod(FeatureMethod.AUTO, true,
            { order += "macro"; false }, { order += "shortx"; true }, { it })
        assertEquals(true, result)
        assertEquals(listOf("macro", "shortx"), order)
    }

    @Test fun autoStopsAfterSuccessfulNonRoot() = runBlocking {
        var elevated = false
        val result = routeFeatureMethod(FeatureMethod.AUTO, true, { true }, { elevated = true; true }, { it })
        assertEquals(true, result)
        assertFalse(elevated)
    }

    @Test fun previousPrivilegedSelectionsRemainShortX() {
        val old = FeatureRef("android.lsposed.system.operation", 1,
            mapOf("__backend" to ConfigValue.StringValue("root")))
        assertEquals(FeatureMethod.SHORTX, old.preferredMethod())
    }

    @Test fun publicAndroidAndRootCanUseSameLogicalFeature() {
        val options = listOf(
            FeatureImplementationOption("android"),
            FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
        )
        val descriptor = FeatureDescriptor(FeatureId("android.app.launch"), FeatureKind.ACTION,
            "Launch app", "Open application", FeatureCategory.APP, implementationOptions = options)
        assertTrue(descriptor.hasDualMethodRoutes())
        val keys = descriptor.withAccessEditorMetadata().fields.map { it.key }
        assertTrue("__method" in keys)
    }

    @Test fun onlyGenuineDualRoutesExposeMethod() {
        val options = listOf(
            FeatureImplementationOption("accessibility", setOf(AccessRequirement.ACCESSIBILITY)),
            FeatureImplementationOption("lsposed", setOf(AccessRequirement.LSPOSED)),
            FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
        )
        val descriptor = FeatureDescriptor(FeatureId("test.dual"), FeatureKind.ACTION,
            "Dual", "Both methods", FeatureCategory.SYSTEM, implementationOptions = options)
        val decorated = descriptor.withAccessEditorMetadata()
        assertTrue(decorated.hasDualMethodRoutes())
        assertEquals(listOf("__method", "__backend"), decorated.fields.take(2).map { it.key })
        assertEquals(listOf("auto", "lsposed", "root"),
            (decorated.fields[1] as FieldSchema.Choice).options)
        assertEquals(FieldRule.Equals("__method", ConfigValue.StringValue("shortx")),
            decorated.fieldBehavior("__backend").visibleWhen)
    }
}
