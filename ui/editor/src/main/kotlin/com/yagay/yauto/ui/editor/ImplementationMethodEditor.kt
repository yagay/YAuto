package com.yagay.yauto.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yagay.yauto.ui.design.R as TextR

/** Only informational labels remain; no manual implementation/backend controls. */
@Composable
internal fun implementationTitle(backendId: String): String = stringResource(
    when (backendId) {
        "auto" -> TextR.string.implementation_auto_title
        "android" -> TextR.string.implementation_android_title
        "root" -> TextR.string.implementation_root_title
        "shizuku" -> TextR.string.implementation_shizuku_title
        "lsposed" -> TextR.string.implementation_lsposed_title
        "accessibility" -> TextR.string.implementation_accessibility_title
        "usage_stats" -> TextR.string.implementation_usage_stats_title
        else -> TextR.string.implementation_method
    }
)


