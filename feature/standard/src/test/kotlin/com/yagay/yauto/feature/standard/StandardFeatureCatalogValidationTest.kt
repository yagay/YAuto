package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.registry.FeatureCatalogIssueSeverity
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.catalogIssues
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardFeatureCatalogValidationTest {
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
