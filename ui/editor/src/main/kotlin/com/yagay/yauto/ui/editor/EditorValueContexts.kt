package com.yagay.yauto.ui.editor

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Variable names visible to the feature configuration editor.
 *
 * The app layer contributes workspace globals/persistent variables while automation and flow
 * editors extend the set with their local variables/parameters.
 */
internal val LocalEditorVariableNames = staticCompositionLocalOf<Set<String>> { emptySet() }
