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
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
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
            AutomationPhase.ENTER -> userText("engine.phase_enter")
            AutomationPhase.EVENT -> userText("engine.phase_event")
            AutomationPhase.EXIT -> userText("engine.phase_exit")
        }
        trace(executionId, TraceKind.EXECUTION_START, userText("engine.execution_start", automation.name, phaseLabel), automation)

        return try {
            require(automation.executionPolicy.maxRuntimeMs > 0) { userText("engine.runtime_limit_positive") }
            require(automation.executionPolicy.maxLoopIterations > 0) { userText("engine.loop_limit_positive") }
            val signal = withTimeoutOrNull(automation.executionPolicy.maxRuntimeMs) {
                executeNodes(nodes, executionId, variables, automation, null, automation.executionPolicy.maxLoopIterations)
            } ?: Signal.Failure(userText("engine.execution_timeout", automation.executionPolicy.maxRuntimeMs))
            val result = when (signal) {
                is Signal.Failure -> EngineResult(false, executionId, variables = variables.snapshot(), error = signal.message)
                is Signal.Return -> EngineResult(true, executionId, signal.value, variables.snapshot())
                Signal.Break, Signal.Continue, Signal.Next -> EngineResult(true, executionId, variables = variables.snapshot())
            }
            trace(executionId, TraceKind.EXECUTION_END, if (result.success) userText("engine.execution_completed") else userText("engine.execution_failed", result.error.orEmpty()), automation, success = result.success)
            result
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                trace(executionId, TraceKind.EXECUTION_END, userText("engine.execution_cancelled"), automation, success = false, level = TraceLevel.WARN)
            }
            throw cancelled
        } catch (t: Exception) {
            trace(executionId, TraceKind.ERROR, t.stackTraceToString().take(16_000), automation, success = false, level = TraceLevel.ERROR)
            trace(executionId, TraceKind.EXECUTION_END, userText("engine.execution_failed", t.message ?: t::class.simpleName.orEmpty()), automation, success = false)
            EngineResult(false, executionId, variables = variables.snapshot(), error = userText("engine.execution_failed", t.message ?: t::class.simpleName.orEmpty()))
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
            trace(executionId, TraceKind.NODE_START, userText("engine.node_start"), automation, flow, node.id)
            val signal = executeNode(node, executionId, variables, automation, flow, maxLoopIterations)
            trace(executionId, TraceKind.NODE_END, userText("engine.node_end"), automation, flow, node.id, success = signal !is Signal.Failure, durationMs = System.currentTimeMillis() - start)
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
            is ActionNode.Action -> executeActionNode(node, executionId, variables, automation, flow)
            is ActionNode.If -> executeNodes(if (evaluatePredicate(node.condition, executionId, node.id, variables)) node.thenActions else node.elseActions, executionId, variables, automation, flow, maxLoopIterations)
            is ActionNode.Switch -> {
                val actual = expressions.evaluateText(node.expression, variables)
                val branch = node.cases.firstOrNull { it.match == actual }?.actions ?: node.defaultActions
                executeNodes(branch, executionId, variables, automation, flow, maxLoopIterations)
            }
            is ActionNode.Repeat -> {
                if (node.times !in 0..maxLoopIterations) return Signal.Failure(userText("engine.repeat_limit"))
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
                    if (count++ >= maxLoopIterations) return Signal.Failure(userText("engine.while_limit"))
                    when (val signal = executeNodes(node.actions, executionId, variables, automation, flow, maxLoopIterations)) {
                        Signal.Next, Signal.Continue -> Unit
                        Signal.Break -> return Signal.Next
                        else -> return signal
                    }
                }
                Signal.Next
            }
            is ActionNode.ForEach -> {
                if (node.values.size > maxLoopIterations) return Signal.Failure(userText("engine.foreach_limit"))
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
                    Signal.Failure(userText("feature.operation_failed", error.message ?: error.javaClass.simpleName))
                }
                val handled = if (primary is Signal.Failure && node.onError.isNotEmpty()) {
                    variables.set("error.message", ConfigValue.StringValue(primary.message))
                    try { executeNodes(node.onError, executionId, variables, automation, flow, maxLoopIterations) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { Signal.Failure(userText("feature.operation_failed", error.message ?: error.javaClass.simpleName)) }
                } else primary
                val finalSignal = executeNodes(node.finallyActions, executionId, variables, automation, flow, maxLoopIterations)
                if (finalSignal != Signal.Next) finalSignal else handled
            }
            is ActionNode.WaitUntil -> {
                if (node.timeoutMs <= 0L || node.pollIntervalMs <= 0L) {
                    return Signal.Failure(userText("engine.wait_until_invalid"))
                }
                val pollInterval = node.pollIntervalMs.coerceIn(100L, 60_000L)
                val completed = withTimeoutOrNull(node.timeoutMs) {
                    while (currentCoroutineContext().isActive) {
                        if (evaluatePredicate(node.condition, executionId, node.id, variables)) {
                            return@withTimeoutOrNull true
                        }
                        delay(pollInterval)
                    }
                    false
                } ?: false
                if (completed) Signal.Next else Signal.Failure(userText("engine.wait_until_timeout", node.timeoutMs))
            }
            is ActionNode.CallFlow -> {
                val depth = currentCoroutineContext()[FlowDepth]?.value ?: 0
                if (depth >= 64) return Signal.Failure(userText("engine.flow_depth"))
                val target = flowResolver.resolve(node.flowId) ?: return Signal.Failure(userText("engine.unknown_flow", node.flowId.value))
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
                    if (it.required && value == ConfigValue.NullValue) return Signal.Failure(userText("engine.missing_flow_input", it.name))
                    childInitial[inputKey] = value
                    childInitial[directKey] = value
                }
                val childVariables = RuntimeVariables(childInitial)
                trace(executionId, TraceKind.FLOW, userText("engine.flow_call", target.name), automation, target, node.id)
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

    /**
     * Executes one leaf action with node-local failure semantics. Keeping this policy here instead
     * of inside individual feature executors makes every current and future Action automatically
     * support the same stop/continue/retry behavior without duplicating implementation code.
     */
    private suspend fun executeActionNode(
        node: ActionNode.Action,
        executionId: ExecutionId,
        variables: RuntimeVariables,
        automation: Automation?,
        flow: Flow?,
    ): Signal {
        if (!node.enabled) return Signal.Next

        val executor = registry.actionExecutor(node.feature.typeId)
        if (executor == null) {
            val message = userText("engine.unknown_action", node.feature.typeId)
            trace(
                executionId,
                TraceKind.ACTION,
                message,
                automation,
                flow,
                node.id,
                node.feature.typeId,
                success = false,
                level = TraceLevel.WARN,
            )
            return terminalActionFailure(node, message)
        }

        val maxAttempts = if (node.failurePolicy == ActionFailurePolicy.RETRY) {
            node.retryPolicy.maxAttempts.coerceIn(1, MAX_ACTION_RETRY_ATTEMPTS)
        } else {
            1
        }
        val retryDelayMs = node.retryPolicy.delayMs.coerceIn(0L, MAX_ACTION_RETRY_DELAY_MS)
        var lastFailure = userText("engine.action_failed", node.feature.typeId)

        for (attempt in 1..maxAttempts) {
            currentCoroutineContext().ensureActive()
            trace(executionId, TraceKind.ACTION, userText("engine.action_start", node.feature.typeId), automation, flow, node.id, node.feature.typeId)

            val failure = try {
                val result = executor.execute(
                    node.feature,
                    FeatureExecutionContext(executionId, node.id, variables, capabilities, tracer),
                )
                trace(
                    executionId,
                    TraceKind.ACTION,
                    result.message ?: node.feature.typeId,
                    automation,
                    flow,
                    node.id,
                    node.feature.typeId,
                    result.success,
                )
                if (result.success) return Signal.Next
                result.message ?: userText("engine.action_failed", node.feature.typeId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                trace(
                    executionId,
                    TraceKind.ERROR,
                    error.stackTraceToString().take(16_000),
                    automation,
                    flow,
                    node.id,
                    node.feature.typeId,
                    success = false,
                    level = TraceLevel.ERROR,
                )
                val message = userText(
                    "feature.operation_failed",
                    error.message ?: error.javaClass.simpleName,
                )
                trace(
                    executionId,
                    TraceKind.ACTION,
                    message,
                    automation,
                    flow,
                    node.id,
                    node.feature.typeId,
                    success = false,
                )
                message
            }

            lastFailure = failure
            if (node.failurePolicy == ActionFailurePolicy.RETRY && attempt < maxAttempts) {
                if (retryDelayMs > 0L) delay(retryDelayMs)
            } else {
                break
            }
        }

        return terminalActionFailure(node, lastFailure)
    }

    private fun terminalActionFailure(node: ActionNode.Action, message: String): Signal =
        when (node.failurePolicy) {
            ActionFailurePolicy.CONTINUE -> Signal.Next
            ActionFailurePolicy.STOP,
            ActionFailurePolicy.RETRY -> Signal.Failure(message)
        }

    private suspend fun evaluatePredicate(predicate: PredicateNode, executionId: ExecutionId, nodeId: NodeId, variables: RuntimeVariables): Boolean = when (predicate) {
        is PredicateNode.All -> predicate.children.all { evaluatePredicate(it, executionId, nodeId, variables) }
        is PredicateNode.Any -> predicate.children.any { evaluatePredicate(it, executionId, nodeId, variables) }
        is PredicateNode.None -> predicate.children.none { evaluatePredicate(it, executionId, nodeId, variables) }
        is PredicateNode.Literal -> predicate.value
        is PredicateNode.Expression -> expressions.evaluateBoolean(predicate.expression, variables)
        is PredicateNode.Condition -> {
            val evaluator = registry.conditionEvaluator(predicate.feature.typeId)
            if (evaluator == null) {
                trace(
                    executionId = executionId,
                    kind = TraceKind.CONDITION,
                    message = userText("engine.unknown_condition", predicate.feature.typeId),
                    nodeId = nodeId,
                    featureId = predicate.feature.typeId,
                    success = false,
                    level = TraceLevel.WARN,
                )
                false
            } else {
                val result = evaluator.evaluate(predicate.feature, FeatureExecutionContext(executionId, nodeId, variables, capabilities, tracer))
                trace(executionId, TraceKind.CONDITION, userText("engine.condition_result", predicate.feature.typeId, if (result) userText("value.true") else userText("value.false")), nodeId = nodeId, featureId = predicate.feature.typeId, success = result)
                result
            }
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

    private companion object {
        const val MAX_ACTION_RETRY_ATTEMPTS = 100
        const val MAX_ACTION_RETRY_DELAY_MS = 60_000L
    }
}
