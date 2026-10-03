package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.registry.FeatureRegistry
import org.junit.Assert.assertNotNull
import org.junit.Test

class DataTextExpansionFeaturePackTest {
    @Test
    fun `advanced text and regex operations are executable`() {
        val registry = FeatureRegistry()
        DataUtilityFeaturePack().install(registry)

        listOf(
            "data.regex.matches",
            "data.regex.replace",
            "data.text.replace",
            "data.text.contains",
            "data.text.starts_with",
            "data.text.ends_with",
            "data.text.index_of",
            "data.text.last_index_of",
            "data.text.reverse",
            "data.text.repeat",
            "data.text.pad_start",
            "data.text.pad_end",
            "data.text.normalize_whitespace",
            "data.text.remove_prefix",
            "data.text.remove_suffix",
            "data.text.char_at",
            "data.text.lines",
            "data.text.words",
        ).forEach { id ->
            assertNotNull("Missing descriptor $id", registry.descriptor(id))
            assertNotNull("Missing executor $id", registry.actionExecutor(id))
        }
    }
}
