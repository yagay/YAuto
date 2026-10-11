package com.yagay.yauto.ui.editor

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.registry.AccessRequirement
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureImplementationOption
import com.yagay.yauto.core.registry.FeatureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeaturePermissionSelectionTest {
    private fun descriptor(vararg paths: FeatureImplementationOption, required: Set<AccessRequirement> = emptySet()) =
        FeatureDescriptor(
            id = FeatureId("test.permissions"),
            kind = FeatureKind.ACTION,
            title = "Test",
            description = "Permission test",
            category = FeatureCategory.SYSTEM,
            implementationOptions = paths.toList(),
            accessRequirements = required,
        )

    @Test fun alternativePermissionsAreNotAllMandatory() {
        val feature = descriptor(
            FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
            FeatureImplementationOption("shizuku", setOf(AccessRequirement.SHIZUKU)),
        )
        assertEquals(listOf(AccessRequirement.SHIZUKU),
            permissionsForFeature(feature, null) { PermissionAvailability.NOT_GRANTED })
    }

    @Test fun alreadyAuthorizedMethodWinsWithoutPromptingForAnother() {
        val feature = descriptor(
            FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
            FeatureImplementationOption("shizuku", setOf(AccessRequirement.SHIZUKU)),
        )
        assertEquals(listOf(AccessRequirement.ROOT),
            permissionsForFeature(feature, null) {
                if (it == AccessRequirement.ROOT) PermissionAvailability.GRANTED
                else PermissionAvailability.NOT_GRANTED
            })
    }

    @Test fun legacyPinnedMethodStillShowsItsOwnPermissions() {
        val feature = descriptor(
            FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
            FeatureImplementationOption("shizuku", setOf(AccessRequirement.SHIZUKU)),
        )
        val saved = FeatureRef("test.permissions", config = mapOf(
            "__backend" to ConfigValue.StringValue("root"),
        ))
        assertEquals(listOf(AccessRequirement.ROOT),
            permissionsForFeature(feature, saved) { PermissionAvailability.NOT_GRANTED })
    }

    @Test fun actualCommonPermissionIsRetained() {
        val feature = descriptor(
            FeatureImplementationOption("android", emptySet()),
            FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
            required = setOf(AccessRequirement.CAMERA),
        )
        assertEquals(listOf(AccessRequirement.CAMERA),
            permissionsForFeature(feature, null) { PermissionAvailability.NOT_GRANTED })
    }

    @Test fun featureWithNoPermissionHasNoPermissionRows() {
        assertTrue(permissionsForFeature(descriptor(), null) {
            PermissionAvailability.UNKNOWN
        }.isEmpty())
    }

    @Test fun lsposedWinsWhenAllBackendsAreAuthorized() {
        val feature = descriptor(
            FeatureImplementationOption("accessibility", setOf(AccessRequirement.ACCESSIBILITY)),
            FeatureImplementationOption("shizuku", setOf(AccessRequirement.SHIZUKU)),
            FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
            FeatureImplementationOption("lsposed", setOf(AccessRequirement.LSPOSED)),
        )
        assertEquals(listOf(AccessRequirement.LSPOSED),
            permissionsForFeature(feature, null) { PermissionAvailability.GRANTED })
    }

    @Test fun rootWinsWhenBothRootAndShizukuAreAuthorized() {
        val feature = descriptor(
            FeatureImplementationOption("accessibility", setOf(AccessRequirement.ACCESSIBILITY)),
            FeatureImplementationOption("shizuku", setOf(AccessRequirement.SHIZUKU)),
            FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
        )
        assertEquals(listOf(AccessRequirement.ROOT),
            permissionsForFeature(feature, null) { PermissionAvailability.GRANTED })
    }

    @Test fun disabledRootPreferenceUsesAuthorizedShizukuFirst() {
        val feature = descriptor(
            FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
            FeatureImplementationOption("shizuku", setOf(AccessRequirement.SHIZUKU)),
        )
        assertEquals(listOf(AccessRequirement.SHIZUKU),
            permissionsForFeature(feature, null, preferRootWhenAvailable = false) {
                PermissionAvailability.GRANTED
            })
    }

    @Test fun globalBackActionNeverRecommendsUnavailableLsposedPath() {
        val supported = FeatureDescriptor(
            id = FeatureId("accessibility.global_action"), kind = FeatureKind.ACTION,
            title = "Global action", description = "", category = FeatureCategory.SYSTEM,
            implementationOptions = listOf(
                FeatureImplementationOption("lsposed", setOf(AccessRequirement.LSPOSED)),
                FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
                FeatureImplementationOption("accessibility", setOf(AccessRequirement.ACCESSIBILITY)),
            ),
        )
        val saved = FeatureRef("accessibility.global_action", config = mapOf(
            "action" to ConfigValue.StringValue("back")
        ))
        assertEquals(listOf(AccessRequirement.ROOT), permissionsForFeature(supported, saved) {
            PermissionAvailability.GRANTED
        })
    }

    @Test fun powerDialogOnlyRequestsAccessibility() {
        val supported = FeatureDescriptor(
            id = FeatureId("accessibility.global_action"), kind = FeatureKind.ACTION,
            title = "Global action", description = "", category = FeatureCategory.SYSTEM,
            implementationOptions = listOf(
                FeatureImplementationOption("lsposed", setOf(AccessRequirement.LSPOSED)),
                FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
                FeatureImplementationOption("accessibility", setOf(AccessRequirement.ACCESSIBILITY)),
            ),
        )
        val saved = FeatureRef("accessibility.global_action", config = mapOf(
            "action" to ConfigValue.StringValue("power_dialog")
        ))
        assertEquals(listOf(AccessRequirement.ACCESSIBILITY), permissionsForFeature(supported, saved) {
            PermissionAvailability.GRANTED
        })
    }

    @Test fun pinnedUnsupportedBackendNeverFallsBackForPermissionAdvice() {
        val supported = FeatureDescriptor(
            id = FeatureId("accessibility.global_action"), kind = FeatureKind.ACTION,
            title = "Global action", description = "", category = FeatureCategory.SYSTEM,
            implementationOptions = listOf(
                FeatureImplementationOption("lsposed", setOf(AccessRequirement.LSPOSED)),
                FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
                FeatureImplementationOption("accessibility", setOf(AccessRequirement.ACCESSIBILITY)),
            ),
        )
        val pinned = FeatureRef("accessibility.global_action", config = mapOf(
            "action" to ConfigValue.StringValue("back"),
            "__backend" to ConfigValue.StringValue("lsposed"),
        ))
        assertTrue(permissionsForFeature(supported, pinned) { PermissionAvailability.GRANTED }.isEmpty())
    }
}
