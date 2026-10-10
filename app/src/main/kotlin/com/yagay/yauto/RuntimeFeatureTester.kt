package com.yagay.yauto

import android.content.Context
import com.yagay.yauto.core.engine.AutomationPhase
import com.yagay.yauto.core.engine.EngineDebugSession
import com.yagay.yauto.core.model.Automation
import com.yagay.yauto.core.model.NodeId
import com.yagay.yauto.ui.editor.EngineDebugRun
import com.yagay.yauto.ui.editor.EngineDebugSnapshotUi
import com.yagay.yauto.ui.editor.EngineDebugStepUi
import com.yagay.yauto.core.logging.TraceEvent
import com.yagay.yauto.core.logging.TraceKind
import com.yagay.yauto.core.logging.TraceLevel
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.ExecutionId
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.registry.FeatureExecutionContext
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.applyDefaults
import com.yagay.yauto.core.registry.VariableAccess
import com.yagay.yauto.ui.design.R as TextR
import com.yagay.yauto.ui.editor.FeatureTestGateway
import com.yagay.yauto.ui.editor.FeatureTestResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Runs an unsaved draft in isolation from the automation scheduler.
 * Variables are snapshotted and never persisted. Device actions remain REAL,
 * and the editor must require explicit confirmation before calling ACTION.
 */
internal class RuntimeFeatureTester(
    private val context: Context,
    private val graph: AppGraph,
) : FeatureTestGateway {
    private val debugPreferences by lazy {
        context.getSharedPreferences("yauto_debug_breakpoints", Context.MODE_PRIVATE)
    }

    override fun savedDebugBreakpoints(automationId: String): Set<String> =
        debugPreferences.getStringSet(automationId, emptySet()).orEmpty().toSet()

    override fun saveDebugBreakpoints(automationId: String, ids: Set<String>) {
        debugPreferences.edit().putStringSet(automationId, ids.toSet()).apply()
    }

    override fun newDebugRun(automation: Automation): EngineDebugRun {
        val debug = EngineDebugSession()
        return object : EngineDebugRun {
            override suspend fun execute(): FeatureTestResult {
                val began = System.currentTimeMillis()
                val result = graph.runtime.createDebugEngine().execute(automation, AutomationPhase.EVENT, debugObserver = debug)
                return FeatureTestResult(
                    success = result.success,
                    detail = result.error ?: if (result.success) "Completed" else "Failed",
                    elapsedMs = System.currentTimeMillis() - began,
                    kind = FeatureKind.ACTION,
                    executionId = result.executionId.value,
                )
            }
            override suspend fun step() = debug.step()
            override suspend fun resume() = debug.continueExecution()
            override suspend fun setBreakpoint(id: String, enabled: Boolean) {
                debug.setBreakpoint(NodeId(id), enabled)
            }
            override suspend fun snapshot(): EngineDebugSnapshotUi {
                val paused = debug.pausedAt()
                val rows = debug.snapshot().map {
                    EngineDebugStepUi(
                        id = it.nodeId.value,
                        kind = it.nodeType,
                        success = it.success,
                        elapsedMs = it.elapsedMs,
                        before = it.variablesBefore.toString().take(1000),
                        after = it.variablesAfter.toString().take(1000),
                    )
                }
                return EngineDebugSnapshotUi(paused?.nodeId?.value, rows)
            }
        }
    }

    private class SandboxVariables(initial: Map<String, ConfigValue>) : VariableAccess {
        private val values = initial.toMutableMap()
        override fun get(name: String): ConfigValue? = values[name]
        override fun set(name: String, value: ConfigValue) { values[name] = value }
        override fun snapshot(): Map<String, ConfigValue> = values.toMap()
    }

    override suspend fun testSavedAutomation(id: String): FeatureTestResult {
        val start = System.currentTimeMillis()
        val executionId = ExecutionId(UUID.randomUUID().toString())
        val timeoutResult = FeatureTestResult(
            false, context.getString(TextR.string.feature_test_timeout), 0L,
            kind = FeatureKind.ACTION, executionId = executionId.value,
        )
        val result = try {
            withTimeoutOrNull(120_000L) {
                val action = graph.runtime.run(id, emptyMap(), allowDisabled = true)
                FeatureTestResult(
                    action.success,
                    action.message?.takeIf { it.isNotBlank() }?.take(600)
                        ?: context.getString(if (action.success)
                            TextR.string.feature_test_action_completed
                        else TextR.string.feature_test_failed),
                    0L, kind = FeatureKind.ACTION,
                )
            } ?: timeoutResult
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FeatureTestResult(false, error.message?.take(600)
                ?: context.getString(TextR.string.feature_test_failed), 0L,
                kind = FeatureKind.ACTION)
        }
        return result.copy(elapsedMs = System.currentTimeMillis() - start,
            executionId = executionId.value)
    }

    override suspend fun test(feature: FeatureRef, kind: FeatureKind): FeatureTestResult {
        val began = System.currentTimeMillis()
        val id = ExecutionId(UUID.randomUUID().toString())
        val descriptor = graph.features.descriptor(feature.typeId)
        if (descriptor == null || descriptor.kind != kind) {
            return FeatureTestResult(
                false, context.getString(TextR.string.feature_test_unavailable), 0L,
                kind = kind, executionId = id.value,
            )
        }
        val prepared = descriptor.applyDefaults(graph.features.canonicalRef(feature, kind))
        val snapshot = graph.workspace.load()
        val variables = SandboxVariables(buildMap {
            snapshot.globalVariables.forEach { (key, value) -> put(key, ConfigValue.StringValue(value)) }
            putAll(snapshot.persistentVariables)
        })
        var outcome: FeatureTestResult? = null
        try {
            outcome = withTimeoutOrNull(if (kind == FeatureKind.ACTION) 30_000L else 15_000L) {
                val execution = FeatureExecutionContext(id, null, variables, graph.capabilities, graph.tracer)
                when (kind) {
                    FeatureKind.ACTION -> {
                        val executor = graph.features.actionExecutor(prepared.typeId)
                            ?: return@withTimeoutOrNull FeatureTestResult(
                                false, context.getString(TextR.string.feature_test_unavailable), 0, kind = kind)
                        val result = executor.execute(prepared, execution)
                        FeatureTestResult(
                            result.success,
                            result.message?.takeIf { it.isNotBlank() }?.take(600)
                                ?: context.getString(if (result.success)
                                    TextR.string.feature_test_action_completed
                                else TextR.string.feature_test_failed),
                            0L, kind = kind,
                        )
                    }
                    FeatureKind.CONDITION, FeatureKind.STATE -> {
                        val evaluator = if (kind == FeatureKind.CONDITION)
                            graph.features.conditionEvaluator(prepared.typeId)
                        else graph.features.stateEvaluator(prepared.typeId)
                        if (evaluator == null) FeatureTestResult(
                            false, context.getString(TextR.string.feature_test_unavailable), 0, kind = kind)
                        else {
                            val matched = evaluator.evaluate(prepared, execution)
                            FeatureTestResult(
                                true,
                                context.getString(if (matched) TextR.string.feature_test_true
                                    else TextR.string.feature_test_false),
                                0L, evaluation = matched, kind = kind,
                            )
                        }
                    }
                    FeatureKind.EVENT -> {
                        // Never synthesize a broadcast: that could trigger other macros and
                        // would give a misleading "trigger fired" result.
                        FeatureTestResult(
                            true,
                            context.getString(TextR.string.feature_test_event_check_only),
                            0L, kind = kind,
                        )
                    }
                }
            } ?: FeatureTestResult(
                false, context.getString(TextR.string.feature_test_timeout), 0L, kind = kind)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            outcome = FeatureTestResult(
                false,
                error.message?.takeIf { it.isNotBlank() }?.take(600)
                    ?: context.getString(TextR.string.feature_test_failed),
                0L, kind = kind,
            )
        }
        val final = requireNotNull(outcome).copy(
            elapsedMs = System.currentTimeMillis() - began, executionId = id.value,
        )
        val traceKind = when (kind) {
            FeatureKind.ACTION -> TraceKind.ACTION
            FeatureKind.CONDITION -> TraceKind.CONDITION
            FeatureKind.STATE -> TraceKind.STATE
            FeatureKind.EVENT -> TraceKind.TRIGGER
        }
        graph.tracer.record(TraceEvent(
            executionId = id,
            kind = traceKind,
            level = if (final.success) TraceLevel.INFO else TraceLevel.ERROR,
            timestampEpochMs = System.currentTimeMillis(),
            featureId = feature.typeId,
            message = "UI test: " + final.detail,
            success = final.success,
            durationMs = final.elapsedMs,
            attributes = mapOf(
                "test.mode" to if (kind == FeatureKind.EVENT) "configuration_only" else "live",
                "test.unsaved" to "true",
            ),
        ))
        return final
    }
}
