package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.registry.FeatureCatalogIssueSeverity
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeaturePickerCategory
import com.yagay.yauto.core.registry.macroDroidCategoriesForKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import com.yagay.yauto.core.registry.catalogIssues
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardFeatureCatalogValidationTest {
    @Test
    fun `standard features use MacroDroid aligned categories without changing IDs`() {
        val registry = FeatureRegistry()
        StandardFeaturePacks.all().forEach(registry::install)
        val expected = mapOf(
            "core.automation.run" to FeaturePickerCategory.MACROS,
            "core.category.set_enabled" to FeaturePickerCategory.MACROS,
            "core.trigger.set_enabled" to FeaturePickerCategory.MACROS,
            "core.delay" to FeaturePickerCategory.YAUTO_SPECIFIC,
            "core.log" to FeaturePickerCategory.LOGGING,
            "core.condition.trigger_fired" to FeaturePickerCategory.YAUTO_SPECIFIC,
            "data.list.prepend" to FeaturePickerCategory.VARIABLES,
            "variable.decrement" to FeaturePickerCategory.VARIABLES,
            "time.condition.weekday" to FeaturePickerCategory.DATE_TIME,
        )
        expected.forEach { (id, category) ->
            val descriptor = registry.descriptor(id)
            assertNotNull("Expected a registered standard feature: $id", descriptor)
            assertEquals(id, category, descriptor!!.pickerCategory)
        }
    }

    @Test
    fun `every registered standard feature belongs to its kind-specific picker taxonomy`() {
        val registry = FeatureRegistry()
        StandardFeaturePacks.all().forEach(registry::install)
        val mismatches = registry.allDescriptors().filter { descriptor ->
            descriptor.pickerCategory !in macroDroidCategoriesForKind(descriptor.kind)
        }
        assertTrue(
            "Invalid picker categories: " + mismatches.joinToString { "${it.id.value}(${it.kind}): ${it.pickerCategory}" },
            mismatches.isEmpty(),
        )
    }

    @Test
    fun `standard feature catalog contains no structural or implementation errors`() {
        val registry = FeatureRegistry()
        StandardFeaturePacks.all().forEach(registry::install)

        val errors = registry.catalogIssues()
            .filter { it.severity == FeatureCatalogIssueSeverity.ERROR }

        assertTrue(
            errors.joinToString(prefix = "Catalog errors:\n", separator = "\n") {
                "[${it.code}] ${it.featureId}: ${it.message}"
            },
            errors.isEmpty(),
        )
    }
}
