package com.yagay.yauto.platform.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportReportFilenameTest {
    @Test fun `plugin importer identifiers cannot escape report folder`() {
        val id = safeImportReportId("../../private\\settings/root:importer")
        assertFalse(id.contains('/'))
        assertFalse(id.contains('\\'))
        assertFalse(id.contains(':'))
        assertTrue(id.length <= 64)
        assertEquals("macrodroid_5.67", safeImportReportId("macrodroid_5.67"))
    }

    @Test fun `blank and oversized import IDs are bounded`() {
        assertEquals("unknown", safeImportReportId(""))
        assertEquals(64, safeImportReportId("x".repeat(2000)).length)
    }
}
