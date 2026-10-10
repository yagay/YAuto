package com.yagay.yauto.core.importer

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceMappingContractTest {
    @Test fun nonNativeTargetsAreFlaggedBeforeImporterActivation() {
        val mapper = MapSourceFeatureMapper(mapOf(
            (SourceFeatureKind.ACTION to "Launch") to "android.app.launch",
            (SourceFeatureKind.ACTION to "Unknown") to "android.missing",
        ))
        assertEquals(listOf("ACTION:Unknown -> android.missing"),
            mapper.validateTargets(setOf("android.app.launch")))
    }
}
