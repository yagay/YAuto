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
}
