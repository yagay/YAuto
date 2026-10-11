package com.yagay.yauto

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Feature-health scanning is asynchronous and happens when an editor opens.
 * It must not block a long, virtualized picker list or run for every row.
 */
@Composable
internal fun RefreshFeatureHealthOnEditorOpen(active: Boolean, scanner: FeatureHealthScanner) {
    LaunchedEffect(active, scanner) {
        if (active) scanner.requestScan(force = true)
    }
}
