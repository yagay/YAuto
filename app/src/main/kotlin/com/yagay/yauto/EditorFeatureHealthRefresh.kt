package com.yagay.yauto

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.yagay.yauto.ui.editor.FeatureAvailabilityTone
import com.yagay.yauto.ui.editor.FeatureAvailabilityUi

/**
 * Feature-health scanning is asynchronous and invalidated when real grants
 * change while an editor is on screen. Never block a virtualized picker list.
 */
@Composable
internal fun RefreshFeatureHealthOnEditorOpen(active: Boolean, graph: AppGraph) {
    val access by graph.accessStatus.status.collectAsState()
    LaunchedEffect(active, access) {
        if (active && access.isNotEmpty()) graph.featureHealth.requestScan(force = true)
    }
}

/** Never show an old "ready" badge while a fresh runtime probe is in progress. */
@Composable
internal fun rememberLiveFeatureAvailability(graph: AppGraph): Map<String, FeatureAvailabilityUi> {
    val context = LocalContext.current
    val snapshot by graph.featureHealth.snapshot.collectAsState()
    val scanning by graph.featureHealth.scanning.collectAsState()
    return remember(snapshot, scanning, context) {
        if (scanning) emptyMap() else snapshot?.items.orEmpty().associate { item ->
            item.featureId to FeatureAvailabilityUi(
                statusLabel = featureHealthStatusLabel(context, item.status),
                summary = featureHealthListSummary(context, item),
                tone = when (item.status) {
                    FeatureHealthStatus.READY -> FeatureAvailabilityTone.READY
                    FeatureHealthStatus.UNKNOWN -> FeatureAvailabilityTone.UNSUPPORTED
                    FeatureHealthStatus.BLOCKED -> FeatureAvailabilityTone.BLOCKED
                    FeatureHealthStatus.BROKEN -> FeatureAvailabilityTone.BROKEN
                    FeatureHealthStatus.UNSUPPORTED -> FeatureAvailabilityTone.UNSUPPORTED
                },
            )
        }
    }
}
