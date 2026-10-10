package com.yagay.yauto.core.importer

import org.junit.Assert.*
import org.junit.Test

class ImporterResultContractTest {
    @Test fun validImporterIdentityIsNotChanged() {
        val result = ImportResult(importerId = "shortx", success = true)
        assertEquals(result, result.validateImporterIdentity("shortx", null))
    }

    @Test fun mismatchedImporterIdentityCannotBeReportedSuccessful() {
        val result = ImportResult(importerId = "tasker", success = true)
            .validateImporterIdentity("macrodroid", "test.macro")
        assertFalse(result.success)
        assertEquals(ImportSeverity.ERROR, result.issues.last().severity)
        assertEquals("test.macro", result.issues.last().sourcePath)
    }
}
