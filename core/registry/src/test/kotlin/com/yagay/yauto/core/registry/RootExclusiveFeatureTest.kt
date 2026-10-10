package com.yagay.yauto.core.registry

import com.yagay.yauto.core.capability.CapabilityIds
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootExclusiveFeatureTest {
    private fun feature(
        capabilities: Set<com.yagay.yauto.core.capability.CapabilityId> = emptySet(),
        implementations: List<FeatureImplementationOption> = emptyList(),
        access: Set<AccessRequirement> = emptySet(),
    ) = FeatureDescriptor(
        id = FeatureId("test.permission.feature"),
        kind = FeatureKind.ACTION,
        title = "Permission feature",
        description = "A feature",
        category = FeatureCategory.SYSTEM,
        capabilities = capabilities,
        implementationOptions = implementations,
        accessRequirements = access,
    )

    @Test fun lsposedOnlyIsRootExclusive() {
        assertTrue(feature(capabilities = setOf(CapabilityIds.LSPOSED)).isRootExclusiveFeature())
    }

    @Test fun rootAndLsposedOnlyIsRootExclusive() {
        assertTrue(feature(implementations = listOf(
            FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
            FeatureImplementationOption("lsposed", setOf(AccessRequirement.LSPOSED)),
        )).isRootExclusiveFeature())
    }

    @Test fun perImplementationFilterDropsOnlyRootAndLsposed() {
        val implementations = listOf(
            FeatureImplementationOption("android"),
            FeatureImplementationOption("accessibility", setOf(AccessRequirement.ACCESSIBILITY)),
            FeatureImplementationOption("shizuku", setOf(AccessRequirement.SHIZUKU)),
            FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
            FeatureImplementationOption("lsposed", setOf(AccessRequirement.LSPOSED)),
        )
        val feature = feature(implementations = implementations)
        assertFalse(feature.isRootExclusiveFeature())
        assertTrue(feature.visibleImplementationOptions(true) == implementations)
        assertTrue(feature.visibleImplementationOptions(false).map { it.backendId } ==
            listOf("android", "accessibility", "shizuku"))
    }

    @Test fun conservativeUnknownMethodDoesNotHideNonRootAlternative() {
        val implementation = FeatureImplementationOption(
            "custom", setOf(AccessRequirement.ROOT, AccessRequirement.SHIZUKU))
        assertFalse(implementation.requiresRootOrLsposed())
    }

    @Test fun shizukuAlternativeKeepsFeatureVisible() {
        assertFalse(feature(capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL)).isRootExclusiveFeature())
        assertFalse(feature(implementations = listOf(
            FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
            FeatureImplementationOption("shizuku", setOf(AccessRequirement.SHIZUKU)),
        )).isRootExclusiveFeature())
    }

    @Test fun accessibilityAlternativeKeepsFeatureVisible() {
        assertFalse(feature(implementations = listOf(
            FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
            FeatureImplementationOption("accessibility", setOf(AccessRequirement.ACCESSIBILITY)),
        )).isRootExclusiveFeature())
        assertFalse(feature(implementations = listOf(
            FeatureImplementationOption("android"),
            FeatureImplementationOption("lsposed", setOf(AccessRequirement.LSPOSED)),
        )).isRootExclusiveFeature())
    }

    @Test fun explicitRootOrZygiskWithoutAlternativeIsExclusive() {
        assertTrue(feature(access = setOf(AccessRequirement.ROOT)).isRootExclusiveFeature())
        assertTrue(feature(access = setOf(AccessRequirement.ZYGISK)).isRootExclusiveFeature())
    }

    @Test fun ordinaryAndroidAndUnclassifiedOptionsAreNotHidden() {
        assertFalse(feature().isRootExclusiveFeature())
        assertFalse(feature(implementations = listOf(FeatureImplementationOption("unknown"))).isRootExclusiveFeature())
    }
}
