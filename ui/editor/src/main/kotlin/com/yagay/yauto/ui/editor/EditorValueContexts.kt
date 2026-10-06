package com.yagay.yauto.ui.editor

import androidx.compose.runtime.staticCompositionLocalOf
import com.yagay.yauto.core.registry.HardwareKeyPickerCatalog

/**
 * Variable names visible to the feature configuration editor.
 *
 * The app layer contributes workspace globals/persistent variables while automation and flow
 * editors extend the set with their local variables/parameters.
 */
val LocalEditorVariableNames = staticCompositionLocalOf<Set<String>> { emptySet() }

val LocalHardwareKeyCatalogLoader = staticCompositionLocalOf<suspend () -> HardwareKeyPickerCatalog> {
    { HardwareKeyPickerCatalog() }
}
