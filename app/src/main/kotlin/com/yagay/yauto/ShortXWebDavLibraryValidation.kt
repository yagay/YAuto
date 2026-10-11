package com.yagay.yauto
/** Shared import validation, before applying any remote workspace change. */
internal object ShortXWebDavLibraryValidation {
    fun validate(json: String) {
        RuntimeLibraryStore.validateImportJson(json)
    }
}
