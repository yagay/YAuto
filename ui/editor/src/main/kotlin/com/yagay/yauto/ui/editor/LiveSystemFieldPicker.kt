package com.yagay.yauto.ui.editor

import androidx.compose.runtime.staticCompositionLocalOf

/** Shared host for read-only, live Android data without editor/app coupling. */
fun interface LiveSystemFieldPicker {
    suspend fun load(
        descriptorId: String,
        fieldKey: String,
        values: Map<String, String>,
    ): List<SystemPickerOption>
}

val LocalLiveSystemFieldPicker = staticCompositionLocalOf<LiveSystemFieldPicker?> { null }
