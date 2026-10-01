package com.yagay.yauto.core.engine

import com.yagay.yauto.core.capability.CapabilityClient
import com.yagay.yauto.core.logging.ExecutionTracer
import com.yagay.yauto.core.logging.TraceEvent
import com.yagay.yauto.core.logging.TraceKind
import com.yagay.yauto.core.logging.TraceLevel
import com.yagay.yauto.core.model.*
import com.yagay.yauto.core.registry.FeatureExecutionContext
import com.yagay.yauto.core.registry.FeatureRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
        trace(executionId, TraceKind.EXECUTION_START, "${automation.name}:$phase", automation)

        return try {
            val signal = executeNodes(nodes, executionId, variables, automation, null, automation.executionPolicy.maxLoopIterations)
            val result = when (signal) {
                is Signal.Failure -> EngineResult(false, executionId, variables = variables.snapshot(), error = signal.message)
                is Signal.Return -> EngineResult(true, executionId, signal.value, variables.snapshot())
                Signal.Break, Signal.Continue, Signal.Next -> EngineResult(true, executionId, variables = variables.snapshot())
            }
            trace(executionId, TraceKind.EXECUTION_END, if (result.success) "Execution completed" else "Execution failed: ${result.error}", automation, success = result.success)
            result
        } catch (t: Throwable) {
            trace(executionId, TraceKind.ERROR, t.stackTraceToString().take(16_000), automation, success = false, level = TraceLevel.ERROR)
            EngineResult(false, executionId, variables = variables.snapshot(), error = t.message ?: t::class.simpleName)
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
            val start = System.currentTimeMillis()
            trace(executionId, TraceKind.NODE_START, node::class.simpleName ?: "node", automation, flow, node.id)
            val signal = executeNode(node, executionId, variables, automation, flow, maxLoopIterations)
            trace(executionId, TraceKind.NODE_END, node::class.simpleName ?: "node", automation, flow, node.id, success = signal !is Signal.Failure, durationMs = System.currentTimeMillis() - start)
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
    ): Signal = when (node) {
        is ActionNode.Action -> {
            if (!node.enabled) Signal.Next else {
                val executor = registry.actionExecutor(node.feature.typeId)
                    ?: return Signal.Failure("Unknown action: ${node.feature.typeId}")
                trace(executionId, TraceKind.ACTION, "Start ${node.feature.typeId}", automation, flow, node.id, node.feature.typeId)
                val result = executor.execute(node.feature, FeatureExecutionContext(executionId, node.id, variables, capabilities, tracer))
                trace(executionId, TraceKind.ACTION, result.message ?: node.feature.typeId, automation, flow, node.id, node.feature.typeId, result.success)
                if (result.success) Signal.Next else Signal.Failure(result.message ?: "Action failed: ${node.feature.typeId}")
            }
        }
        is ActionNode.If -> executeNodes(if (evaluatePredicate(node.condition, executionId, node.id, variables)) node.thenActions else node.elseActions, executionId, variables, automation, flow, maxLoopIterations)
        is ActionNode.Switch -> {
            val actual = expressions.evaluateText(node.expression, variables)
            val branch = node.cases.firstOrNull { it.match == actual }?.actions ?: node.defaultActions
            executeNodes(branch, executionId, variables, automation, flow, maxLoopIterations)
        }
        is ActionNode.Repeat -> {
            repeat(node.times.coerceIn(0, maxLoopIterations)) {
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
            while (count++ < maxLoopIterations && evaluatePredicate(node.condition, executionId, node.id, variables)) {
                when (val signal = executeNodes(node.actions, executionId, variables, automation, flow, maxLoopIterations)) {
                    Signal.Next, Signal.Continue -> Unit
                    Signal.Break -> return Signal.Next
                    else -> return signal
                }
            }
            Signal.Next
        }
        is ActionNode.ForEach -> {
            for (value in node.values.take(maxLoopIterations)) {
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
            val primary = executeNodes(node.actions, executionId, variables, automation, flow, maxLoopIterations)
            val handled = if (primary is Signal.Failure && node.onError.isNotEmpty()) {
                variables.set("error.message", ConfigValue.StringValue(primary.message))
                executeNodes(node.onError, executionId, variables, automation, flow, maxLoopIterations)
            } else primary
            val finalSignal = executeNodes(node.finallyActions, executionId, variables, automation, flow, maxLoopIterations)
            if (finalSignal != Signal.Next) finalSignal else handled
        }
        is ActionNode.CallFlow -> {
            val target = flowResolver.resolve(node.flowId) ?: return Signal.Failure("Unknown flow: ${node.flowId.value}")
            val childInitial = variables.snapshot().toMutableMap()
            node.input.forEach { (key, value) -> childInitial["input.$key"] = value }
            target.inputs.forEach { childInitial.putIfAbsent("input.${it.name}", it.defaultValue) }
            val childVariables = RuntimeVariables(childInitial)
            trace(executionId, TraceKind.FLOW, "Call ${target.name}", automation, target, node.id)
            when (val signal = executeNodes(target.actions, executionId, childVariables, automation, target, maxLoopIterations)) {
                is Signal.Failure -> signal
                is Signal.Return -> { node.resultVariable?.let { variables.set(it, signal.value) }; Signal.Next }
                else -> Signal.Next
            }
        }
        is ActionNode.Return -> Signal.Return(node.value)
        is ActionNode.Break -> Signal.Break
        is ActionNode.Continue -> Signal.Continue
    }

    private suspend fun evaluatePredicate(predicate: PredicateNode, executionId: ExecutionId, nodeId: NodeId, variables: RuntimeVariables): Boolean = when (predicate) {
        is PredicateNode.All -> predicate.children.all { evaluatePredicate(it, executionId, nodeId, variables) }
        is PredicateNode.Any -> predicate.children.any { evaluatePredicate(it, executionId, nodeId, variables) }
        is PredicateNode.None -> predicate.children.none { evaluatePredicate(it, executionId, nodeId, variables) }
        is PredicateNode.Literal -> predicate.value
        is PredicateNode.Expression -> expressions.evaluateBoolean(predicate.expression, variables)
        is PredicateNode.Condition -> {
            val evaluator = registry.conditionEvaluator(predicate.feature.typeId) ?: error("Unknown condition: ${predicate.feature.typeId}")
            val result = evaluator.evaluate(predicate.feature, FeatureExecutionContext(executionId, nodeId, variables, capabilities, tracer))
            trace(executionId, TraceKind.CONDITION, "${predicate.feature.typeId} = $result", nodeId = nodeId, featureId = predicate.feature.typeId, success = result)
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
}
