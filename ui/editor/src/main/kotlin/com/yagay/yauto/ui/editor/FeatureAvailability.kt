package com.yagay.yauto.ui.editor

import androidx.compose.runtime.staticCompositionLocalOf

enum class FeatureAvailabilityTone {
    READY,
    BLOCKED,
    BROKEN,
    UNSUPPORTED,
}

data class FeatureAvailabilityUi(
    val statusLabel: String,
    val summary: String? = null,
    val tone: FeatureAvailabilityTone,
)

val LocalFeatureAvailability = staticCompositionLocalOf<Map<String, FeatureAvailabilityUi>> {
    emptyMap()
}
