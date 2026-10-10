package com.yagay.yauto.ui.editor

import androidx.compose.runtime.staticCompositionLocalOf
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.registry.FeatureKind

/**
 * A test of an ACTION executes the live operation and can change device state.
 * CONDITION/STATE returns a real evaluator result. EVENT is a configuration
 * readiness check only; it does not forge system events or run other automations.
 */
data class FeatureTestResult(
    val success: Boolean,
    val detail: String,
    val elapsedMs: Long,
    val evaluation: Boolean? = null,
    val kind: FeatureKind,
    val executionId: String? = null,
)

interface FeatureTestGateway {
    suspend fun test(feature: FeatureRef, kind: FeatureKind): FeatureTestResult
}

val LocalFeatureTestGateway = staticCompositionLocalOf<FeatureTestGateway?> { null }
