package com.yagay.yauto.ui.editor

import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.registry.AccessRequirement
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeatureImplementationOption
import com.yagay.yauto.core.model.FeatureRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootOnlyPickerFilterTest {
    private val ordinary = FeatureDescriptor(
        FeatureId("android.public"), FeatureKind.ACTION, "Public", "",
        FeatureCategory.APP,
    )
    private val rootOnly = FeatureDescriptor(
        FeatureId("android.root.only"), FeatureKind.ACTION, "Root", "",
        FeatureCategory.SYSTEM, capabilities = setOf(CapabilityIds.LSPOSED),
    )
    private val mixed = FeatureDescriptor(
        FeatureId("android.mixed"), FeatureKind.ACTION, "Mixed", "",
        FeatureCategory.SYSTEM, implementationOptions = listOf(
            FeatureImplementationOption("lsposed", setOf(AccessRequirement.LSPOSED)),
            FeatureImplementationOption("shizuku", setOf(AccessRequirement.SHIZUKU)),
        ),
    )
    private val condition = FeatureDescriptor(
        FeatureId("android.root.condition"), FeatureKind.CONDITION, "Root condition", "",
        FeatureCategory.SYSTEM, capabilities = setOf(CapabilityIds.LSPOSED),
    )

    @Test fun toggleHidesOnlyExclusiveFeatures() {
        val descriptors = listOf(ordinary, rootOnly, mixed, condition)
        assertEquals(listOf("android.public", "android.mixed"),
            filterPickerFeatures(descriptors, FeatureKind.ACTION, false).map { it.id.value })
        assertEquals(3, filterPickerFeatures(descriptors, FeatureKind.ACTION, true).size)
    }

    @Test fun existingHiddenFeatureCanStillBeEdited() {
        val shown = filterPickerFeatures(
            listOf(ordinary, rootOnly),
            FeatureKind.ACTION,
            false,
            FeatureRef("android.root.only"),
        )
        assertTrue(shown.any { it.id.value == "android.root.only" })
    }

    @Test fun conditionFilteringAlsoApplies() {
        assertTrue(filterPickerFeatures(listOf(condition), FeatureKind.CONDITION, false).isEmpty())
    }
}
