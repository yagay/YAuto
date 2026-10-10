package com.yagay.yauto.core.runtime

import com.yagay.yauto.core.capability.CapabilityClient
import com.yagay.yauto.core.engine.AutomationEngine
import com.yagay.yauto.core.engine.AutomationPhase
import com.yagay.yauto.core.engine.EngineResult
import com.yagay.yauto.core.engine.FlowResolver
import com.yagay.yauto.core.engine.RuntimeEventWaiter
import com.yagay.yauto.core.engine.SimpleExpressionEngine
import com.yagay.yauto.core.logging.ExecutionTracer
import com.yagay.yauto.core.logging.TraceEvent
import com.yagay.yauto.core.logging.TraceKind
import com.yagay.yauto.core.logging.TraceLevel
import com.yagay.yauto.core.model.*
import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.AutomationControl
import com.yagay.yauto.core.registry.AutomationEnableMode
import com.yagay.yauto.core.registry.EventMatchContext
import com.yagay.yauto.core.registry.FeatureExecutionContext
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.PersistentVariableChange
import com.yagay.yauto.core.registry.PersistentVariableControl
import com.yagay.yauto.core.registry.VariableAccess
import com.yagay.yauto.core.storage.WorkspaceData
import com.yagay.yauto.core.storage.WorkspaceRepository
import com.yagay.yauto.core.storage.WorkspaceMutationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext


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
) : AutomationControl, PersistentVariableControl {
    /** Shared engine configuration for scheduled runs and interactive debugging. */
    suspend fun createDebugEngine(): AutomationEngine {
        val flows = workspaceRepository.load().flows.associateBy { it.id }
        return createExecutionEngine(flows)
    }

    private fun createExecutionEngine(flows: Map<FlowId, Flow>): AutomationEngine =
        AutomationEngine(
            registry = registry,
            capabilities = capabilities,
            tracer = tracer,
            flowResolver = FlowResolver { id -> flows[id] },
            eventWaiter = RuntimeEventWaiter { events, baseVariables, timeoutMs, waitExecutionId, nodeId ->
                waitForRuntimeEvent(
                    events = events,
                    baseVariables = baseVariables,
                    timeoutMs = timeoutMs,
                    executionId = waitExecutionId,
                    nodeId = nodeId,
                )
            },
        )

    private val eventState = RuntimeEventState()
    private val executionJobs = ExecutionJobRegistry()
    private val conflictCoordinator = RuntimeConflictCoordinator(executionJobs)
    private val eventWaitRegistry = RuntimeEventWaitRegistry()
    private val workspaceMutationLock = Mutex()
    private val lastRunStore = RuntimeLastRunStore(workspaceRepository, workspaceMutationLock)
    private val persistentVariableStore = RuntimeVariableStore(workspaceRepository, workspaceMutationLock)
    private val expressions = SimpleExpressionEngine()

    /** Shared transaction boundary for runtime controls and persistent metadata. */
    private suspend fun updateWorkspace(transform: (WorkspaceData) -> WorkspaceData): WorkspaceData =
        if (workspaceRepository is WorkspaceMutationRepository) workspaceRepository.update(transform)
        else workspaceMutationLock.withLock {
            val updated = transform(workspaceRepository.load())
            workspaceRepository.save(updated)
            updated
        }


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
        notifyEventWaiters(event)

        val workspace = workspaceRepository.load()
        if (!workspace.runtimeEnabled && event.typeId != "core.event.runtime_enabled_changed") {
            return@coroutineScope RuntimeDispatchResult(dispatchId, event, emptyList())
        }
        val flows = workspace.flows.associateBy { it.id }
        val runs = mutableListOf<RuntimeAutomationRun>()
        val callStack = currentCoroutineContext()[AutomationCallStack]?.ids.orEmpty()

        for (automation in RuntimeEventDispatchPolicy.eligible(workspace, callStack, statesOnly)) {
            try {
                val variables = MapVariableAccess(RuntimeEventContext.variables(workspace, automation.variables, event))

                val phases = eventState.withEvaluationLock(automation.id.value) {
                    var matchedEventFeature: FeatureRef? = null
                    val eventMatches = if (statesOnly) {
                        false
                    } else {
                        for (candidate in automation.activation.events) {
                            if (!RuntimeTriggerPolicy.enabled(workspace, automation, candidate)) continue
                            if (matchEvent(candidate, event, variables, dispatchId)) {
                                matchedEventFeature = candidate
                                break
                            }
                        }
                        matchedEventFeature != null
                    }
                    matchedEventFeature
                        ?.config
                        ?.get("tag")
                        ?.let { tag -> variables.set("event.fact_tag", tag) }

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
                    val wasActive = eventState.isActive(automation.id.value)

                    val phases = RuntimeEventDispatchPolicy.phases(stateful, gateOpen, wasActive, eventMatches, conditionMatches)
                    if (stateful) eventState.setActive(automation.id.value, gateOpen)
                    phases
                }

                if (phases.isNotEmpty()) {
                    val results = executeCoordinated(automation, phases, variables.snapshot(), flows)
                    if (results != null) runs += RuntimeAutomationRun(automation.id, phases, results)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                tracer.record(
                    TraceEvent(
                        dispatchId,
                        kind = TraceKind.ERROR,
                        level = TraceLevel.ERROR,
                        timestampEpochMs = System.currentTimeMillis(),
                        message = userText("runtime.dispatch_failed", error.message.orEmpty()),
                        automationId = automation.id,
                        success = false,
                        attributes = mapOf("event.type" to event.typeId, "exception" to error.javaClass.name),
                    )
                )
            }
        }
        RuntimeDispatchResult(dispatchId, event, runs)
    }

    override suspend fun run(
        target: String,
        variables: Map<String, ConfigValue>,
        allowDisabled: Boolean,
    ): ActionExecutionResult {
        val trimmed = target.trim()
        if (trimmed.isEmpty()) return ActionExecutionResult(false, message = userText("runtime.automation_target_required"))
        val workspace = workspaceRepository.load()
        val automation = RuntimeSelectionPolicy.resolveAutomation(workspace, trimmed)
            ?: return ActionExecutionResult(false, message = userText("runtime.automation_not_found", trimmed))
        if ((!automation.enabled || !RuntimeSelectionPolicy.categoryEnabled(automation.category, workspace.disabledCategories)) && !allowDisabled) {
            return ActionExecutionResult(false, message = userText("runtime.automation_disabled", automation.name))
        }

        val stack = currentCoroutineContext()[AutomationCallStack]?.ids.orEmpty()
        if (automation.id.value in stack) {
            return ActionExecutionResult(false, message = userText("runtime.automation_recursive_call", automation.name))
        }
        if (stack.size >= MAX_AUTOMATION_CALL_DEPTH) {
            return ActionExecutionResult(false, message = userText("runtime.automation_call_depth", MAX_AUTOMATION_CALL_DEPTH))
        }

        val initial = buildMap {
            workspace.globalVariables.forEach { (key, value) -> put(key, ConfigValue.StringValue(value)) }
            putAll(workspace.persistentVariables)
            putAll(variables)
        }
        val results = executeCoordinated(
            automation = automation,
            phases = listOf(AutomationPhase.EVENT),
            variables = initial,
            flows = workspace.flows.associateBy { it.id },
        ) ?: return ActionExecutionResult(false, message = userText("runtime.automation_busy", automation.name))
        val result = results.lastOrNull()
            ?: return ActionExecutionResult(false, message = userText("runtime.automation_run_failed", automation.name))
        return ActionExecutionResult(result.success, result.returnValue, result.error)
    }

    override suspend fun setEnabled(
        target: String,
        mode: AutomationEnableMode,
    ): ActionExecutionResult {
        val trimmed = target.trim()
        if (trimmed.isEmpty()) {
            return ActionExecutionResult(false, message = userText("runtime.automation_target_required"))
        }
        var changedAutomation: Automation? = null
        var newEnabled = false
        var result = ActionExecutionResult(false, message = userText("runtime.automation_not_found", trimmed))
        updateWorkspace { workspace ->
            val automation = RuntimeSelectionPolicy.resolveAutomation(workspace, trimmed)
            if (automation == null) workspace else {
                newEnabled = when (mode) {
                    AutomationEnableMode.ENABLE -> true
                    AutomationEnableMode.DISABLE -> false
                    AutomationEnableMode.TOGGLE -> !automation.enabled
                }
                result = ActionExecutionResult(true, ConfigValue.BooleanValue(newEnabled))
                if (newEnabled == automation.enabled) workspace else {
                    changedAutomation = automation
                    workspace.copy(
                        automations = workspace.automations.map {
                            if (it.id == automation.id) it.copy(enabled = newEnabled) else it
                        },
                    )
                }
            }
        }
        changedAutomation?.let { automation ->
            if (!newEnabled) {
                executionJobs.cancel(automation.id.value)
                resetState(automation.id)
            }
            dispatch(
                RuntimeEvent(
                    typeId = "core.event.automation_enabled_changed",
                    payload = mapOf(
                        "automationId" to ConfigValue.StringValue(automation.id.value),
                        "automationName" to ConfigValue.StringValue(automation.name),
                        "enabled" to ConfigValue.BooleanValue(newEnabled),
                    ),
                    source = "runtime.automation_control",
                )
            )
        }
        return result
    }

    override suspend fun cancel(target: String): ActionExecutionResult {
        val trimmed = target.trim()
        if (trimmed.isEmpty()) return ActionExecutionResult(false, message = userText("runtime.automation_target_required"))
        val workspace = workspaceRepository.load()
        val automation = RuntimeSelectionPolicy.resolveAutomation(workspace, trimmed)
            ?: return ActionExecutionResult(false, message = userText("runtime.automation_not_found", trimmed))
        val stack = currentCoroutineContext()[AutomationCallStack]?.ids.orEmpty()
        if (automation.id.value in stack) {
            return ActionExecutionResult(false, message = userText("runtime.automation_cancel_self", automation.name))
        }
        val cancelled = executionJobs.cancel(automation.id.value)
        return ActionExecutionResult(true, ConfigValue.BooleanValue(cancelled))
    }

    override suspend fun isEnabled(target: String): Boolean? {
        val trimmed = target.trim()
        if (trimmed.isEmpty()) return null
        return RuntimeSelectionPolicy.resolveAutomation(workspaceRepository.load(), trimmed)?.enabled
    }

    override suspend fun isRunning(target: String): Boolean? {
        val trimmed = target.trim()
        if (trimmed.isEmpty()) return null
        val automation = RuntimeSelectionPolicy.resolveAutomation(workspaceRepository.load(), trimmed) ?: return null
        return executionJobs.isRunning(automation.id.value)
    }

    override suspend fun setRuntimeEnabled(mode: AutomationEnableMode): ActionExecutionResult {
        var previous = true
        var enabled = true
        updateWorkspace { workspace ->
            previous = workspace.runtimeEnabled
            enabled = when (mode) {
                AutomationEnableMode.ENABLE -> true
                AutomationEnableMode.DISABLE -> false
                AutomationEnableMode.TOGGLE -> !workspace.runtimeEnabled
            }
            if (previous == enabled) workspace else workspace.copy(runtimeEnabled = enabled)
        }
        if (previous != enabled) {
            if (!enabled) {
                executionJobs.cancelAll()
                eventState.reset()
            }
            dispatch(
                RuntimeEvent(
                    typeId = "core.event.runtime_enabled_changed",
                    payload = mapOf("enabled" to ConfigValue.BooleanValue(enabled)),
                    source = "runtime.master_control",
                )
            )
        }
        return ActionExecutionResult(true, ConfigValue.BooleanValue(enabled))
    }

    override suspend fun isRuntimeEnabled(): Boolean = workspaceRepository.load().runtimeEnabled

    override suspend fun setTriggerEnabled(
        automation: String,
        triggerType: String,
        tag: String,
        mode: AutomationEnableMode,
    ): ActionExecutionResult {
        val target = automation.trim()
        val type = triggerType.trim()
        val triggerTag = tag.trim()
        if (target.isEmpty() || (type.isEmpty() && triggerTag.isEmpty())) {
            return ActionExecutionResult(false, message = userText("runtime.trigger_target_required"))
        }
        var result = ActionExecutionResult(false, message = userText("runtime.automation_not_found", target))
        updateWorkspace { workspace ->
            val resolved = RuntimeSelectionPolicy.resolveAutomation(workspace, target)
            if (resolved == null) workspace else {
                val candidates = resolved.activation.events.filter { feature ->
                    (type.isBlank() || feature.typeId == type || feature.config["source.type"]?.asTraceText() == type) &&
                        (triggerTag.isBlank() || feature.config.string("tag") == triggerTag)
                }
                if (candidates.isEmpty()) {
                    result = ActionExecutionResult(false, message = userText("runtime.trigger_not_found"))
                    workspace
                } else {
                    val keys = candidates.map { RuntimeTriggerPolicy.key(resolved, it) }.toSet()
                    val currentlyEnabled = keys.any { it !in workspace.disabledTriggerKeys }
                    val enable = when (mode) {
                        AutomationEnableMode.ENABLE -> true
                        AutomationEnableMode.DISABLE -> false
                        AutomationEnableMode.TOGGLE -> !currentlyEnabled
                    }
                    val disabled = if (enable) workspace.disabledTriggerKeys - keys
                        else workspace.disabledTriggerKeys + keys
                    result = ActionExecutionResult(true, ConfigValue.BooleanValue(enable))
                    workspace.copy(disabledTriggerKeys = disabled)
                }
            }
        }
        return result
    }

    override suspend fun isTriggerEnabled(
        automation: String,
        triggerType: String,
        tag: String,
    ): Boolean? {
        val workspace = workspaceRepository.load()
        val resolved = RuntimeSelectionPolicy.resolveAutomation(workspace, automation.trim()) ?: return null
        val type = triggerType.trim()
        val triggerTag = tag.trim()
        val candidates = resolved.activation.events.filter { feature ->
            (type.isBlank() || feature.typeId == type || feature.config["source.type"]?.asTraceText() == type) &&
                (triggerTag.isBlank() || feature.config.string("tag") == triggerTag)
        }
        if (candidates.isEmpty()) return null
        return candidates.any { RuntimeTriggerPolicy.key(resolved, it) !in workspace.disabledTriggerKeys }
    }

    override suspend fun setCategoryEnabled(
        category: String,
        mode: AutomationEnableMode,
    ): ActionExecutionResult {
        val name = category.trim()
        if (name.isEmpty()) return ActionExecutionResult(false)
        var previouslyEnabled = true
        var enabled = true
        var affectedIds = emptyList<AutomationId>()
        updateWorkspace { workspace ->
            previouslyEnabled = name !in workspace.disabledCategories
            enabled = when (mode) {
                AutomationEnableMode.ENABLE -> true
                AutomationEnableMode.DISABLE -> false
                AutomationEnableMode.TOGGLE -> !previouslyEnabled
            }
            if (enabled == previouslyEnabled) workspace else {
                if (!enabled) affectedIds = workspace.automations
                    .filter { it.category?.trim() == name }.map { it.id }
                val disabled = if (enabled) workspace.disabledCategories - name
                    else workspace.disabledCategories + name
                workspace.copy(disabledCategories = disabled)
            }
        }
        if (previouslyEnabled != enabled) {
            if (!enabled) affectedIds.forEach {
                executionJobs.cancel(it.value)
                resetState(it)
            }
            dispatch(
                RuntimeEvent(
                    typeId = "core.event.category_enabled_changed",
                    payload = mapOf(
                        "category" to ConfigValue.StringValue(name),
                        "enabled" to ConfigValue.BooleanValue(enabled),
                    ),
                    source = "runtime.category",
                )
            )
        }
        return ActionExecutionResult(true, ConfigValue.BooleanValue(enabled))
    }

    override suspend fun isCategoryEnabled(category: String): Boolean? {
        val name = category.trim()
        if (name.isEmpty()) return null
        return name !in workspaceRepository.load().disabledCategories
    }

    override suspend fun lastRunEpochMs(target: String): Long? {
        val trimmed = target.trim()
        if (trimmed.isEmpty()) return null
        val workspace = workspaceRepository.load()
        val automation = RuntimeSelectionPolicy.resolveAutomation(workspace, trimmed) ?: return null
        return lastRunStore.get(automation.id)
    }

    override suspend fun get(name: String): ConfigValue? = persistentVariableStore.get(name)

    override suspend fun set(name: String, value: ConfigValue): PersistentVariableChange {
        val change = persistentVariableStore.set(name, value)
        if (change.success && change.previous != change.current) dispatch(variableChangedEvent(change))
        return change
    }

    override suspend fun clear(name: String): PersistentVariableChange {
        val change = persistentVariableStore.clear(name)
        if (change.success && change.previous != null) dispatch(variableChangedEvent(change))
        return change
    }

    fun resetState(automationId: AutomationId? = null) {
        eventState.reset(automationId?.value)
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
        is PredicateNode.Xor -> {
            var matches = 0
            for (child in predicate.children) {
                if (evaluatePredicate(child, variables, dispatchId, automationId)) matches++
            }
            matches == 1
        }
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
        val runTracked: suspend () -> List<EngineResult> = {
            coroutineScope {
                val parentStack = currentCoroutineContext()[AutomationCallStack]?.ids.orEmpty()
                val job = async(AutomationCallStack(parentStack + key)) {
                    val lifecycleSourceEvent = (variables["event.type"] as? ConfigValue.StringValue)?.value.orEmpty()
                    val emitLifecycle = !lifecycleSourceEvent.startsWith("core.event.automation_")
                    val startedAt = System.currentTimeMillis()
                    if (emitLifecycle) {
                        dispatch(
                            RuntimeEvent(
                                typeId = "core.event.automation_started",
                                payload = mapOf(
                                    "automationId" to ConfigValue.StringValue(automation.id.value),
                                    "automationName" to ConfigValue.StringValue(automation.name),
                                    "phases" to ConfigValue.ListValue(phases.map { ConfigValue.StringValue(it.name.lowercase()) }),
                                ),
                                source = "runtime.automation",
                            )
                        )
                    }
                    val engine = createExecutionEngine(flows)
                    val results = phases.map { phase -> engine.execute(automation, phase, variables) }
                    val finishedAt = System.currentTimeMillis()
                    recordLastRun(automation.id, finishedAt)
                    if (emitLifecycle) {
                        val success = results.all { it.success }
                        dispatch(
                            RuntimeEvent(
                                typeId = "core.event.automation_finished",
                                payload = mapOf(
                                    "automationId" to ConfigValue.StringValue(automation.id.value),
                                    "automationName" to ConfigValue.StringValue(automation.name),
                                    "success" to ConfigValue.BooleanValue(success),
                                    "durationMs" to ConfigValue.NumberValue((finishedAt - startedAt).toDouble()),
                                    "phases" to ConfigValue.ListValue(phases.map { ConfigValue.StringValue(it.name.lowercase()) }),
                                ),
                                source = "runtime.automation",
                            )
                        )
                    }
                    results
                }
                executionJobs.track(key, job)
                try {
                    job.await()
                } catch (cancelled: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw cancelled
                    listOf(
                        EngineResult(
                            success = false,
                            executionId = ExecutionId("cancelled-${UUID.randomUUID()}"),
                            error = userText("runtime.automation_cancelled", automation.name),
                        )
                    )
                } finally {
                    executionJobs.untrack(key, job)
                }
            }
        }

        return conflictCoordinator.execute(key, automation.executionPolicy.conflictPolicy, runTracked)
    }


    private suspend fun recordLastRun(automationId: AutomationId, timestamp: Long) =
        lastRunStore.record(automationId, timestamp)

    private suspend fun waitForRuntimeEvent(
        events: List<FeatureRef>,
        baseVariables: Map<String, ConfigValue>,
        timeoutMs: Long?,
        executionId: ExecutionId,
        nodeId: NodeId,
    ): Boolean = eventWaitRegistry.await(events, baseVariables, timeoutMs, executionId, nodeId)

    private suspend fun notifyEventWaiters(event: RuntimeEvent) {
        eventWaitRegistry.notify(event) { request, incoming ->
            val variables = MapVariableAccess(buildMap {
                putAll(request.baseVariables)
                incoming.payload.forEach { (key, value) -> put("event.$key", value) }
                put("event.type", ConfigValue.StringValue(incoming.typeId))
                put("event.source", ConfigValue.StringValue(incoming.source))
            })
            request.events.any { feature ->
                val matcher = registry.eventMatcher(feature.typeId)
                if (matcher != null) {
                    try {
                        matcher.matches(
                            feature,
                            EventMatchContext(
                                request.executionId, incoming, variables, capabilities, tracer,
                            ),
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                } else feature.typeId == incoming.typeId
            }
        }
    }

    private class MapVariableAccess(initial: Map<String, ConfigValue>) : VariableAccess {
        private val values = initial.toMutableMap()
        override fun get(name: String): ConfigValue? = values[name]
        override fun set(name: String, value: ConfigValue) { values[name] = value }
        override fun snapshot(): Map<String, ConfigValue> = values.toMap()
    }

    private class AutomationCallStack(val ids: List<String>) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<AutomationCallStack>
    }

    private companion object {
        const val MAX_AUTOMATION_CALL_DEPTH = 32
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
