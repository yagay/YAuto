package com.yagay.yauto.ui.editor

import androidx.compose.runtime.staticCompositionLocalOf
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.Automation
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

data class EngineDebugStepUi(
    val invocationId: Long,
    val id: String,
    val kind: String,
    val success: Boolean,
    val elapsedMs: Long,
    val before: String,
    val after: String,
)
data class EngineDebugSnapshotUi(
    val pausedId: String?,
    val steps: List<EngineDebugStepUi>,
)
interface EngineDebugRun {
    suspend fun execute(): FeatureTestResult
    suspend fun step()
    suspend fun resume()
    suspend fun setBreakpoint(id: String, enabled: Boolean)
    suspend fun snapshot(): EngineDebugSnapshotUi
}

interface FeatureTestGateway {
    fun savedDebugBreakpoints(automationId: String): Set<String> = emptySet()
    fun saveDebugBreakpoints(automationId: String, ids: Set<String>) {}
    fun newDebugRun(automation: Automation): EngineDebugRun? = null
    suspend fun test(feature: FeatureRef, kind: FeatureKind): FeatureTestResult

    /** Runs only the currently saved automation's EVENT actions; never invokes triggers. */
    suspend fun testSavedAutomation(id: String): FeatureTestResult
}

val LocalFeatureTestGateway = staticCompositionLocalOf<FeatureTestGateway?> { null }
