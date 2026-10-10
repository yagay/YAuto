package com.yagay.yauto.core.registry

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FeatureMethodRoutingTest {
    @Test fun explicitNormalModeNeverCallsPrivilege() = runBlocking {
        var elevated = 0
        val result = routeFeatureMethod(FeatureMethod.NO_ROOT, true, { false }, { elevated++; true }, { it })
        assertEquals(false, result)
        assertEquals(0, elevated)
        val unsupported = routeFeatureMethod(FeatureMethod.NO_ROOT, false, { true }, { elevated++; true }, { it })
        assertNull(unsupported)
        assertEquals(0, elevated)
    }

    @Test fun explicitShortXNeverCallsNormalPath() = runBlocking {
        var normalCalls = 0
        val result = routeFeatureMethod(FeatureMethod.ROOT_REQUIRED, true, { normalCalls++; true }, { false }, { it })
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

    @Test fun savedShizukuIsNonRootEvenForLegacyShortX() {
        val legacy = FeatureRef("accessibility.global_action", 1,
            mapOf("__method" to ConfigValue.StringValue("shortx"),
                "__backend" to ConfigValue.StringValue("shizuku")))
        assertEquals(FeatureMethod.NO_ROOT, legacy.preferredMethod())
        assertTrue(legacy.methodBackendIsCompatible())
    }

    @Test fun invalidCrossFamilyBackendIsRejected() {
        val wrong = FeatureRef("accessibility.global_action", 1,
            mapOf("__method" to ConfigValue.StringValue("no_root"),
                "__backend" to ConfigValue.StringValue("root")))
        assertFalse(wrong.methodBackendIsCompatible())
    }

    @Test fun genericShellGroupsNeverChooseRootByAccident() {
        val regular = FeatureRef("android.wifi.set", 1,
            mapOf("__method" to ConfigValue.StringValue("no_root")))
        val privileged = regular.copy(config =
            mapOf("__method" to ConfigValue.StringValue("root_required")))
        assertEquals("shizuku", regular.effectiveMethodBackendId())
        assertEquals("root", privileged.effectiveMethodBackendId())
        val old = FeatureRef("android.wifi.set", 1)
        assertNull(old.effectiveMethodBackendId())
    }

    @Test fun explicitNoRootCanSelectShizukuOnly() = runBlocking {
        var called = mutableListOf<String>()
        val result = routeBackendCandidates("shizuku", listOf("android", "shizuku"),
            { backend -> called.add(backend); true }, { it })
        assertEquals(true, result)
        assertEquals(listOf("shizuku"), called)
        val notAllowed = routeBackendCandidates("root", listOf("android", "shizuku"),
            { backend -> called.add(backend); true }, { it })
        assertNull(notAllowed)
    }

    @Test fun previousPrivilegedSelectionsRemainShortX() {
        val old = FeatureRef("android.lsposed.system.operation", 1,
            mapOf("__backend" to ConfigValue.StringValue("root")))
        assertEquals(FeatureMethod.ROOT_REQUIRED, old.preferredMethod())
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
        assertEquals(listOf("no_root", "root_required"), (decorated.fields[0] as FieldSchema.Choice).options)
        assertEquals(listOf("auto", "accessibility", "lsposed", "root"),
            (decorated.fields[1] as FieldSchema.Choice).options)
        assertNull(decorated.fieldBehavior("__backend").visibleWhen)
    }
}
