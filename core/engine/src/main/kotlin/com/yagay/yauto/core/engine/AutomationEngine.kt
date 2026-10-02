package com.yagay.yauto.core.engine

import com.yagay.yauto.core.capability.CapabilityClient
import com.yagay.yauto.core.logging.ExecutionTracer
import com.yagay.yauto.core.logging.TraceEvent
import com.yagay.yauto.core.logging.TraceKind
import com.yagay.yauto.core.logging.TraceLevel
import com.yagay.yauto.core.model.*
import com.yagay.yauto.core.registry.FeatureExecutionContext
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.resolveVariables
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import java.util.UUID

fun interface FlowResolver { suspend fun resolve(id: FlowId): Flow? }
object EmptyFlowResolver : FlowResolver { override suspend fun resolve(id: FlowId): Flow? = null }

enum class AutomationPhase { ENTER, EVENT, EXIT }

data class EngineResult(
    val success: Boolean,
    val executionId: ExecutionId,
    val returnValue: ConfigValue = ConfigValue.NullValue,
    val variables: Map<String, ConfigValue> = emptyMap(),
    val error: String? = null,
)

class AutomationEngine(
    private val registry: FeatureRegistry,
    private val capabilities: CapabilityClient,
    private val tracer: ExecutionTracer,
    private val flowResolver: FlowResolver = EmptyFlowResolver,
    private val expressions: ExpressionEngine = SimpleExpressionEngine(),
) {
    suspend fun execute(
        automation: Automation,
        phase: AutomationPhase,
        eventVariables: Map<String, ConfigValue> = emptyMap(),
    ): EngineResult {
        val executionId = ExecutionId(UUID.randomUUID().toString())
        val variables = RuntimeVariables(automation.variables + eventVariables)
        val nodes = when (phase) {
            AutomationPhase.ENTER -> automation.onEnter
            AutomationPhase.EVENT -> automation.onEvent
            AutomationPhase.EXIT -> automation.onExit
        }
        val phaseLabel = when (phase) {
            AutomationPhase.ENTER -> userText("engine.phase_enter", "Enter")
            AutomationPhase.EVENT -> userText("engine.phase_event", "Event")
            AutomationPhase.EXIT -> userText("engine.phase_exit", "Exit")
        }
        trace(executionId, TraceKind.EXECUTION_START, userText("engine.execution_start", "Execution started: %s · %s", automation.name, phaseLabel), automation)

        return try {
            require(automation.executionPolicy.maxRuntimeMs > 0) { userText("engine.runtime_limit_positive", "Runtime limit must be positive") }
            require(automation.executionPolicy.maxLoopIterations > 0) { userText("engine.loop_limit_positive", "Loop limit must be positive") }
            val signal = withTimeoutOrNull(automation.executionPolicy.maxRuntimeMs) {
                executeNodes(nodes, executionId, variables, automation, null, automation.executionPolicy.maxLoopIterations)
            } ?: Signal.Failure(userText("engine.execution_timeout", "Execution timed out after %s ms", automation.executionPolicy.maxRuntimeMs))
            val result = when (signal) {
                is Signal.Failure -> EngineResult(false, executionId, variables = variables.snapshot(), error = signal.message)
                is Signal.Return -> EngineResult(true, executionId, signal.value, variables.snapshot())
                Signal.Break, Signal.Continue, Signal.Next -> EngineResult(true, executionId, variables = variables.snapshot())
            }
            trace(executionId, TraceKind.EXECUTION_END, if (result.success) userText("engine.execution_completed", "Execution completed") else userText("engine.execution_failed", "Execution failed: %s", result.error.orEmpty()), automation, success = result.success)
            result
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                trace(executionId, TraceKind.EXECUTION_END, userText("engine.execution_cancelled", "Execution cancelled"), automation, success = false, level = TraceLevel.WARN)
            }
            throw cancelled
        } catch (t: Exception) {
            trace(executionId, TraceKind.ERROR, t.stackTraceToString().take(16_000), automation, success = false, level = TraceLevel.ERROR)
            trace(executionId, TraceKind.EXECUTION_END, userText("engine.execution_failed", "Execution failed: %s", t.message ?: t::class.simpleName.orEmpty()), automation, success = false)
            EngineResult(false, executionId, variables = variables.snapshot(), error = userText("engine.execution_failed", "Execution failed: %s", t.message ?: t::class.simpleName.orEmpty()))
        }
    }

    private suspend fun executeNodes(
        nodes: List<ActionNode>,
        executionId: ExecutionId,
        variables: RuntimeVariables,
        automation: Automation?,
        flow: Flow?,
        maxLoopIterations: Int,
    ): Signal {
        for (node in nodes) {
            currentCoroutineContext().ensureActive()
            val start = System.currentTimeMillis()
            trace(executionId, TraceKind.NODE_START, userText("engine.node_start", "Node started"), automation, flow, node.id)
            val signal = executeNode(node, executionId, variables, automation, flow, maxLoopIterations)
            trace(executionId, TraceKind.NODE_END, userText("engine.node_end", "Node completed"), automation, flow, node.id, success = signal !is Signal.Failure, durationMs = System.currentTimeMillis() - start)
            if (signal != Signal.Next) return signal
        }
        return Signal.Next
    }

    private suspend fun executeNode(
        node: ActionNode,
        executionId: ExecutionId,
        variables: RuntimeVariables,
        automation: Automation?,
        flow: Flow?,
        maxLoopIterations: Int,
    ): Signal {
        return when (node) {
            is ActionNode.Action -> {
                if (!node.enabled) Signal.Next else {
                    val executor = registry.actionExecutor(node.feature.typeId)
                        ?: return Signal.Failure(userText("engine.unknown_action", "Unknown action: %s", node.feature.typeId))
                    trace(executionId, TraceKind.ACTION, userText("engine.action_start", "Start action: %s", node.feature.typeId), automation, flow, node.id, node.feature.typeId)
                    val result = executor.execute(node.feature, FeatureExecutionContext(executionId, node.id, variables, capabilities, tracer))
                    trace(executionId, TraceKind.ACTION, result.message ?: node.feature.typeId, automation, flow, node.id, node.feature.typeId, result.success)
                    if (result.success) Signal.Next else Signal.Failure(result.message ?: userText("engine.action_failed", "Action failed: %s", node.feature.typeId))
                }
            }
            is ActionNode.If -> executeNodes(if (evaluatePredicate(node.condition, executionId, node.id, variables)) node.thenActions else node.elseActions, executionId, variables, automation, flow, maxLoopIterations)
            is ActionNode.Switch -> {
                val actual = expressions.evaluateText(node.expression, variables)
                val branch = node.cases.firstOrNull { it.match == actual }?.actions ?: node.defaultActions
                executeNodes(branch, executionId, variables, automation, flow, maxLoopIterations)
            }
            is ActionNode.Repeat -> {
                if (node.times !in 0..maxLoopIterations) return Signal.Failure(userText("engine.repeat_limit", "Repeat count exceeds the loop limit or is negative"))
                repeat(node.times) {
                    currentCoroutineContext().ensureActive()
                    when (val signal = executeNodes(node.actions, executionId, variables, automation, flow, maxLoopIterations)) {
                        Signal.Next, Signal.Continue -> Unit
                        Signal.Break -> return Signal.Next
                        else -> return signal
                    }
                }
                Signal.Next
            }
            is ActionNode.While -> {
                var count = 0
                while (evaluatePredicate(node.condition, executionId, node.id, variables)) {
                    currentCoroutineContext().ensureActive()
                    if (count++ >= maxLoopIterations) return Signal.Failure(userText("engine.while_limit", "While-loop limit exceeded"))
                    when (val signal = executeNodes(node.actions, executionId, variables, automation, flow, maxLoopIterations)) {
                        Signal.Next, Signal.Continue -> Unit
                        Signal.Break -> return Signal.Next
                        else -> return signal
                    }
                }
                Signal.Next
            }
            is ActionNode.ForEach -> {
                if (node.values.size > maxLoopIterations) return Signal.Failure(userText("engine.foreach_limit", "For-each count exceeds the loop limit"))
                for (value in node.values) {
                    currentCoroutineContext().ensureActive()
                    variables.set(node.variableName, value)
                    when (val signal = executeNodes(node.actions, executionId, variables, automation, flow, maxLoopIterations)) {
                        Signal.Next, Signal.Continue -> Unit
                        Signal.Break -> return Signal.Next
                        else -> return signal
                    }
                }
                Signal.Next
            }
            is ActionNode.Parallel -> coroutineScope {
                val results = node.branches.map { branch -> async { executeNodes(branch, executionId, RuntimeVariables(variables.snapshot()), automation, flow, maxLoopIterations) } }.map { it.await() }
                results.firstOrNull { it is Signal.Failure || it is Signal.Return } ?: Signal.Next
            }
            is ActionNode.Try -> {
                val primary = try {
                    executeNodes(node.actions, executionId, variables, automation, flow, maxLoopIterations)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Signal.Failure(userText("feature.operation_failed", "Operation failed: %s", error.message ?: error.javaClass.simpleName))
                }
                val handled = if (primary is Signal.Failure && node.onError.isNotEmpty()) {
                    variables.set("error.message", ConfigValue.StringValue(primary.message))
                    try { executeNodes(node.onError, executionId, variables, automation, flow, maxLoopIterations) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { Signal.Failure(userText("feature.operation_failed", "Operation failed: %s", error.message ?: error.javaClass.simpleName)) }
                } else primary
                val finalSignal = executeNodes(node.finallyActions, executionId, variables, automation, flow, maxLoopIterations)
                if (finalSignal != Signal.Next) finalSignal else handled
            }
            is ActionNode.CallFlow -> {
                val depth = currentCoroutineContext()[FlowDepth]?.value ?: 0
                if (depth >= 64) return Signal.Failure(userText("engine.flow_depth", "Flow call depth exceeds 64"))
                val target = flowResolver.resolve(node.flowId) ?: return Signal.Failure(userText("engine.unknown_flow", "Unknown flow: %s", node.flowId.value))
                val childInitial = variables.snapshot().toMutableMap()
                node.input.forEach { (key, rawValue) ->
                    val value = rawValue.resolveVariables(variables)
                    childInitial["input.$key"] = value
                    childInitial[key] = value
                }
                target.inputs.forEach {
                    val directKey = it.name
                    val inputKey = "input.${it.name}"
                    val value = node.input[it.name]?.resolveVariables(variables) ?: it.defaultValue
                    if (it.required && value == ConfigValue.NullValue) return Signal.Failure(userText("engine.missing_flow_input", "Missing required flow input: %s", it.name))
                    childInitial[inputKey] = value
                    childInitial[directKey] = value
                }
                val childVariables = RuntimeVariables(childInitial)
                trace(executionId, TraceKind.FLOW, userText("engine.flow_call", "Call flow: %s", target.name), automation, target, node.id)
                val flowSignal = withContext(FlowDepth(depth + 1)) {
                    executeNodes(target.actions, executionId, childVariables, automation, target, maxLoopIterations)
                }
                when (val signal = flowSignal) {
                    is Signal.Failure -> signal
                    is Signal.Return -> { node.resultVariable?.let { variables.set(it, signal.value) }; Signal.Next }
                    else -> Signal.Next
                }
            }
            is ActionNode.Return -> Signal.Return(node.value.resolveVariables(variables))
            is ActionNode.Break -> Signal.Break
            is ActionNode.Continue -> Signal.Continue
        }
    }

    private suspend fun evaluatePredicate(predicate: PredicateNode, executionId: ExecutionId, nodeId: NodeId, variables: RuntimeVariables): Boolean = when (predicate) {
        is PredicateNode.All -> predicate.children.all { evaluatePredicate(it, executionId, nodeId, variables) }
        is PredicateNode.Any -> predicate.children.any { evaluatePredicate(it, executionId, nodeId, variables) }
        is PredicateNode.None -> predicate.children.none { evaluatePredicate(it, executionId, nodeId, variables) }
        is PredicateNode.Literal -> predicate.value
        is PredicateNode.Expression -> expressions.evaluateBoolean(predicate.expression, variables)
        is PredicateNode.Condition -> {
            val evaluator = registry.conditionEvaluator(predicate.feature.typeId) ?: error(userText("engine.unknown_condition", "Unknown condition: %s", predicate.feature.typeId))
            val result = evaluator.evaluate(predicate.feature, FeatureExecutionContext(executionId, nodeId, variables, capabilities, tracer))
            trace(executionId, TraceKind.CONDITION, userText("engine.condition_result", "Condition %s: %s", predicate.feature.typeId, if (result) userText("value.true", "True") else userText("value.false", "False")), nodeId = nodeId, featureId = predicate.feature.typeId, success = result)
            result
        }
    }

    private suspend fun trace(
        executionId: ExecutionId, kind: TraceKind, message: String,
        automation: Automation? = null, flow: Flow? = null, nodeId: NodeId? = null,
        featureId: String? = null, success: Boolean? = null, durationMs: Long? = null,
        level: TraceLevel = TraceLevel.INFO,
    ) = tracer.record(TraceEvent(executionId, kind = kind, level = level, timestampEpochMs = System.currentTimeMillis(), message = message, automationId = automation?.id, flowId = flow?.id, nodeId = nodeId, featureId = featureId, success = success, durationMs = durationMs))

    private sealed interface Signal {
        data object Next : Signal
        data object Break : Signal
        data object Continue : Signal
        data class Return(val value: ConfigValue) : Signal
        data class Failure(val message: String) : Signal
    }

    private class FlowDepth(val value: Int) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<FlowDepth>
    }
}
