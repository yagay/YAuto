package com.yagay.yauto.core.runtime

import com.yagay.yauto.core.capability.CapabilityClient
import com.yagay.yauto.core.engine.AutomationEngine
import com.yagay.yauto.core.engine.AutomationPhase
import com.yagay.yauto.core.engine.EngineResult
import com.yagay.yauto.core.engine.FlowResolver
import com.yagay.yauto.core.engine.SimpleExpressionEngine
import com.yagay.yauto.core.logging.ExecutionTracer
import com.yagay.yauto.core.logging.TraceEvent
import com.yagay.yauto.core.logging.TraceKind
import com.yagay.yauto.core.logging.TraceLevel
import com.yagay.yauto.core.model.*
import com.yagay.yauto.core.registry.EventMatchContext
import com.yagay.yauto.core.registry.FeatureExecutionContext
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.VariableAccess
import com.yagay.yauto.core.storage.WorkspaceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap


data class RuntimeAutomationRun(
    val automationId: AutomationId,
    val phases: List<AutomationPhase>,
    val results: List<EngineResult>,
)

data class RuntimeDispatchResult(
    val dispatchId: ExecutionId,
    val event: RuntimeEvent,
    val runs: List<RuntimeAutomationRun>,
)

class AutomationRuntime(
    private val workspaceRepository: WorkspaceRepository,
    private val registry: FeatureRegistry,
    private val capabilities: CapabilityClient,
    private val tracer: ExecutionTracer,
) {
    private val activeStates = ConcurrentHashMap<String, Boolean>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val evaluationLocks = ConcurrentHashMap<String, Mutex>()
    private val runningJobs = ConcurrentHashMap<String, Job>()
    private val expressions = SimpleExpressionEngine()

    suspend fun dispatch(event: RuntimeEvent, statesOnly: Boolean = false): RuntimeDispatchResult = coroutineScope {
        val dispatchId = ExecutionId(UUID.randomUUID().toString())
        tracer.record(
            TraceEvent(
                executionId = dispatchId,
                kind = TraceKind.TRIGGER,
                timestampEpochMs = event.timestampEpochMs,
                message = userText("runtime.event", event.typeId),
                featureId = event.typeId,
                attributes = event.payload.mapValues { (_, value) -> value.asTraceText() },
            )
        )

        val workspace = workspaceRepository.load()
        val flows = workspace.flows.associateBy { it.id }
        val runs = mutableListOf<RuntimeAutomationRun>()

        for (automation in workspace.automations.filter { it.enabled }) {
            if (statesOnly && automation.activation.states.isEmpty()) continue
            try {
            val variables = MapVariableAccess(buildMap {
                workspace.globalVariables.forEach { (key, value) -> put(key, ConfigValue.StringValue(value)) }
                putAll(automation.variables)
                event.payload.forEach { (key, value) -> put("event.$key", value) }
                put("event.type", ConfigValue.StringValue(event.typeId))
                put("event.source", ConfigValue.StringValue(event.source))
            })

            val phases = evaluationLocks.getOrPut(automation.id.value) { Mutex() }.withLock {
            val eventMatches = !statesOnly && automation.activation.events.any {
                matchEvent(it, event, variables, dispatchId)
            }

            val stateful = automation.activation.states.isNotEmpty()
            val statesMatch = automation.activation.states.all { state ->
                val evaluator = registry.stateEvaluator(state.typeId)
                if (evaluator == null) {
                    tracer.record(
                        TraceEvent(
                            executionId = dispatchId,
                            kind = TraceKind.STATE,
                            level = TraceLevel.WARN,
                            timestampEpochMs = System.currentTimeMillis(),
                            message = userText("runtime.no_state_evaluator", state.typeId),
                            automationId = automation.id,
                            featureId = state.typeId,
                            success = false,
                        )
                    )
                    false
                } else {
                    evaluator.evaluate(state, FeatureExecutionContext(dispatchId, null, variables, capabilities, tracer))
                }
            }

            val shouldCheckCondition = stateful || eventMatches
            val conditionMatches = if (!shouldCheckCondition) false else automation.activation.condition?.let {
                evaluatePredicate(it, variables, dispatchId, automation.id)
            } ?: true
            val gateOpen = statesMatch && conditionMatches
            val wasActive = activeStates[automation.id.value] ?: false

            buildList {
                if (stateful) {
                    if (gateOpen && !wasActive) add(AutomationPhase.ENTER)
                    if (gateOpen && eventMatches) add(AutomationPhase.EVENT)
                    if (!gateOpen && wasActive) add(AutomationPhase.EXIT)
                    activeStates[automation.id.value] = gateOpen
                } else if (eventMatches && conditionMatches) {
                    add(AutomationPhase.EVENT)
                }
            }
            }

            if (phases.isNotEmpty()) {
                val results = executeCoordinated(automation, phases, variables.snapshot(), flows)
                if (results != null) runs += RuntimeAutomationRun(automation.id, phases, results)
            }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                tracer.record(TraceEvent(dispatchId, kind = TraceKind.ERROR, level = TraceLevel.ERROR,
                    timestampEpochMs = System.currentTimeMillis(), message = userText("runtime.dispatch_failed", error.message.orEmpty()),
                    automationId = automation.id, success = false,
                    attributes = mapOf("event.type" to event.typeId, "exception" to error.javaClass.name)))
            }
        }
        RuntimeDispatchResult(dispatchId, event, runs)
    }

    fun resetState(automationId: AutomationId? = null) {
        if (automationId == null) activeStates.clear() else activeStates.remove(automationId.value)
    }

    private suspend fun matchEvent(
        feature: FeatureRef,
        event: RuntimeEvent,
        variables: VariableAccess,
        dispatchId: ExecutionId,
    ): Boolean {
        val matcher = registry.eventMatcher(feature.typeId)
        return if (matcher != null) {
            matcher.matches(feature, EventMatchContext(dispatchId, event, variables, capabilities, tracer))
        } else {
            feature.typeId == event.typeId
        }
    }

    private suspend fun evaluatePredicate(
        predicate: PredicateNode,
        variables: VariableAccess,
        dispatchId: ExecutionId,
        automationId: AutomationId,
    ): Boolean = when (predicate) {
        is PredicateNode.All -> predicate.children.all { evaluatePredicate(it, variables, dispatchId, automationId) }
        is PredicateNode.Any -> predicate.children.any { evaluatePredicate(it, variables, dispatchId, automationId) }
        is PredicateNode.None -> predicate.children.none { evaluatePredicate(it, variables, dispatchId, automationId) }
        is PredicateNode.Literal -> predicate.value
        is PredicateNode.Expression -> expressions.evaluateBoolean(predicate.expression, variables)
        is PredicateNode.Condition -> {
            val evaluator = registry.conditionEvaluator(predicate.feature.typeId)
            if (evaluator == null) {
                tracer.record(
                    TraceEvent(
                        executionId = dispatchId,
                        kind = TraceKind.CONDITION,
                        level = TraceLevel.WARN,
                        timestampEpochMs = System.currentTimeMillis(),
                        message = userText("runtime.no_condition_evaluator", predicate.feature.typeId),
                        automationId = automationId,
                        featureId = predicate.feature.typeId,
                        success = false,
                    )
                )
                false
            } else {
                evaluator.evaluate(predicate.feature, FeatureExecutionContext(dispatchId, null, variables, capabilities, tracer))
            }
        }
    }

    private suspend fun executeCoordinated(
        automation: Automation,
        phases: List<AutomationPhase>,
        variables: Map<String, ConfigValue>,
        flows: Map<FlowId, Flow>,
    ): List<EngineResult>? {
        val key = automation.id.value
        val execute: suspend () -> List<EngineResult> = {
            val engine = AutomationEngine(
                registry = registry,
                capabilities = capabilities,
                tracer = tracer,
                flowResolver = FlowResolver { id -> flows[id] },
            )
            phases.map { phase -> engine.execute(automation, phase, variables) }
        }

        return when (automation.executionPolicy.conflictPolicy) {
            ConflictPolicy.PARALLEL -> execute()
            ConflictPolicy.QUEUE -> locks.getOrPut(key) { Mutex() }.withLock { execute() }
            ConflictPolicy.IGNORE_NEW -> {
                val lock = locks.getOrPut(key) { Mutex() }
                if (!lock.tryLock()) null else try { execute() } finally { lock.unlock() }
            }
            ConflictPolicy.CANCEL_PREVIOUS -> coroutineScope {
                val job = async { execute() }
                runningJobs.put(key, job)?.cancel()
                try {
                    job.await()
                } catch (cancelled: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw cancelled
                    null
                } finally {
                    runningJobs.remove(key, job)
                }
            }
        }
    }

    private class MapVariableAccess(initial: Map<String, ConfigValue>) : VariableAccess {
        private val values = initial.toMutableMap()
        override fun get(name: String): ConfigValue? = values[name]
        override fun set(name: String, value: ConfigValue) { values[name] = value }
        override fun snapshot(): Map<String, ConfigValue> = values.toMap()
    }
}

private fun ConfigValue.asTraceText(): String = when (this) {
    ConfigValue.NullValue -> "null"
    is ConfigValue.StringValue -> value
    is ConfigValue.NumberValue -> value.toString()
    is ConfigValue.BooleanValue -> value.toString()
    is ConfigValue.ListValue -> value.toString()
    is ConfigValue.ObjectValue -> value.toString()
}
