package com.yagay.yauto.core.importer

import com.yagay.yauto.core.model.userText

/** Identical integrity checks for MacroDroid, ShortX, Tasker and future importer providers. */
internal fun ImportResult.validateImporterIdentity(expectedImporterId: String, fileName: String?): ImportResult {
    if (importerId == expectedImporterId) return this
    return copy(
        success = false,
        issues = issues + CompatibilityIssue(
            severity = ImportSeverity.ERROR,
            sourcePath = fileName ?: "input",
            message = "Importer identity mismatch: expected $expectedImporterId, received $importerId",
        ),
    )
}
