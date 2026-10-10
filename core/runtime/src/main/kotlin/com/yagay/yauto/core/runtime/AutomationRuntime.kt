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

    private val activeStates = ConcurrentHashMap<String, Boolean>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val evaluationLocks = ConcurrentHashMap<String, Mutex>()
    private val executionJobs = ExecutionJobRegistry()
    private val eventWaitRegistry = RuntimeEventWaitRegistry()
    private val workspaceMutationLock = Mutex()
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
        notifyEventWaiters(event)

        val workspace = workspaceRepository.load()
        if (!workspace.runtimeEnabled && event.typeId != "core.event.runtime_enabled_changed") {
            return@coroutineScope RuntimeDispatchResult(dispatchId, event, emptyList())
        }
        val flows = workspace.flows.associateBy { it.id }
        val runs = mutableListOf<RuntimeAutomationRun>()
        val callStack = currentCoroutineContext()[AutomationCallStack]?.ids.orEmpty()

        for (automation in workspace.automations.filter {
            it.enabled &&
                it.id.value !in callStack &&
                categoryEnabled(it.category, workspace.disabledCategories)
        }) {
            if (statesOnly && automation.activation.states.isEmpty()) continue
            try {
                val variables = MapVariableAccess(buildMap {
                    workspace.globalVariables.forEach { (key, value) -> put(key, ConfigValue.StringValue(value)) }
                    putAll(workspace.persistentVariables)
                    putAll(automation.variables)
                    event.payload.forEach { (key, value) -> put("event.$key", value) }
                    put("event.type", ConfigValue.StringValue(event.typeId))
                    put("event.source", ConfigValue.StringValue(event.source))
                })

                val phases = evaluationLocks.getOrPut(automation.id.value) { Mutex() }.withLock {
                    var matchedEventFeature: FeatureRef? = null
                    val eventMatches = if (statesOnly) {
                        false
                    } else {
                        for (candidate in automation.activation.events) {
                            if (!triggerEnabled(workspace, automation, candidate)) continue
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
        val automation = resolveAutomation(workspace, trimmed)
            ?: return ActionExecutionResult(false, message = userText("runtime.automation_not_found", trimmed))
        if ((!automation.enabled || !categoryEnabled(automation.category, workspace.disabledCategories)) && !allowDisabled) {
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
        val result = workspaceMutationLock.withLock {
            val workspace = workspaceRepository.load()
            val automation = resolveAutomation(workspace, trimmed)
                ?: return@withLock ActionExecutionResult(
                    false,
                    message = userText("runtime.automation_not_found", trimmed),
                )
            val enabled = when (mode) {
                AutomationEnableMode.ENABLE -> true
                AutomationEnableMode.DISABLE -> false
                AutomationEnableMode.TOGGLE -> !automation.enabled
            }
            newEnabled = enabled
            if (enabled != automation.enabled) {
                workspaceRepository.save(
                    workspace.copy(
                        automations = workspace.automations.map {
                            if (it.id == automation.id) it.copy(enabled = enabled) else it
                        }
                    )
                )
                changedAutomation = automation
                if (!enabled) {
                    executionJobs.cancel(automation.id.value)
                    resetState(automation.id)
                }
            }
            ActionExecutionResult(true, ConfigValue.BooleanValue(enabled))
        }
        changedAutomation?.let { automation ->
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
        val automation = resolveAutomation(workspace, trimmed)
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
        return resolveAutomation(workspaceRepository.load(), trimmed)?.enabled
    }

    override suspend fun isRunning(target: String): Boolean? {
        val trimmed = target.trim()
        if (trimmed.isEmpty()) return null
        val automation = resolveAutomation(workspaceRepository.load(), trimmed) ?: return null
        return executionJobs.isRunning(automation.id.value)
    }

    override suspend fun setRuntimeEnabled(mode: AutomationEnableMode): ActionExecutionResult {
        var previous = true
        var enabled = true
        val result = workspaceMutationLock.withLock {
            val workspace = workspaceRepository.load()
            previous = workspace.runtimeEnabled
            enabled = when (mode) {
                AutomationEnableMode.ENABLE -> true
                AutomationEnableMode.DISABLE -> false
                AutomationEnableMode.TOGGLE -> !workspace.runtimeEnabled
            }
            if (enabled != workspace.runtimeEnabled) {
                workspaceRepository.save(workspace.copy(runtimeEnabled = enabled))
                if (!enabled) {
                    executionJobs.cancelAll()
                    activeStates.clear()
                }
            }
            ActionExecutionResult(true, ConfigValue.BooleanValue(enabled))
        }
        if (previous != enabled) {
            dispatch(
                RuntimeEvent(
                    typeId = "core.event.runtime_enabled_changed",
                    payload = mapOf("enabled" to ConfigValue.BooleanValue(enabled)),
                    source = "runtime.master_control",
                )
            )
        }
        return result
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
        return workspaceMutationLock.withLock {
            val workspace = workspaceRepository.load()
            val resolved = resolveAutomation(workspace, target)
                ?: return@withLock ActionExecutionResult(
                    false,
                    message = userText("runtime.automation_not_found", target),
                )
            val candidates = resolved.activation.events.filter { feature ->
                (type.isBlank() || feature.typeId == type || feature.config["source.type"]?.asTraceText() == type) &&
                    (triggerTag.isBlank() || feature.config.string("tag") == triggerTag)
            }
            if (candidates.isEmpty()) {
                return@withLock ActionExecutionResult(
                    false,
                    message = userText("runtime.trigger_not_found"),
                )
            }
            val keys = candidates.map { triggerKey(resolved, it) }.toSet()
            val currentlyEnabled = keys.any { it !in workspace.disabledTriggerKeys }
            val enable = when (mode) {
                AutomationEnableMode.ENABLE -> true
                AutomationEnableMode.DISABLE -> false
                AutomationEnableMode.TOGGLE -> !currentlyEnabled
            }
            val disabled = if (enable) workspace.disabledTriggerKeys - keys
            else workspace.disabledTriggerKeys + keys
            workspaceRepository.save(workspace.copy(disabledTriggerKeys = disabled))
            ActionExecutionResult(true, ConfigValue.BooleanValue(enable))
        }
    }

    override suspend fun isTriggerEnabled(
        automation: String,
        triggerType: String,
        tag: String,
    ): Boolean? {
        val workspace = workspaceRepository.load()
        val resolved = resolveAutomation(workspace, automation.trim()) ?: return null
        val type = triggerType.trim()
        val triggerTag = tag.trim()
        val candidates = resolved.activation.events.filter { feature ->
            (type.isBlank() || feature.typeId == type || feature.config["source.type"]?.asTraceText() == type) &&
                (triggerTag.isBlank() || feature.config.string("tag") == triggerTag)
        }
        if (candidates.isEmpty()) return null
        return candidates.any { triggerKey(resolved, it) !in workspace.disabledTriggerKeys }
    }

    override suspend fun setCategoryEnabled(
        category: String,
        mode: AutomationEnableMode,
    ): ActionExecutionResult {
        val name = category.trim()
        if (name.isEmpty()) return ActionExecutionResult(false)
        val changed = workspaceMutationLock.withLock {
            val workspace = workspaceRepository.load()
            val currentlyEnabled = name !in workspace.disabledCategories
            val enabled = when (mode) {
                AutomationEnableMode.ENABLE -> true
                AutomationEnableMode.DISABLE -> false
                AutomationEnableMode.TOGGLE -> !currentlyEnabled
            }
            if (enabled != currentlyEnabled) {
                val disabled = if (enabled) workspace.disabledCategories - name
                else workspace.disabledCategories + name
                workspaceRepository.save(workspace.copy(disabledCategories = disabled))
                if (!enabled) {
                    workspace.automations
                        .filter { it.category?.trim() == name }
                        .forEach {
                            executionJobs.cancel(it.id.value)
                            resetState(it.id)
                        }
                }
            }
            currentlyEnabled to enabled
        }
        if (changed.first != changed.second) {
            dispatch(
                RuntimeEvent(
                    typeId = "core.event.category_enabled_changed",
                    payload = mapOf(
                        "category" to ConfigValue.StringValue(name),
                        "enabled" to ConfigValue.BooleanValue(changed.second),
                    ),
                    source = "runtime.category",
                )
            )
        }
        return ActionExecutionResult(true, ConfigValue.BooleanValue(changed.second))
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
        val automation = resolveAutomation(workspace, trimmed) ?: return null
        return workspace.automationLastRunEpochMs[automation.id.value]
    }

    override suspend fun get(name: String): ConfigValue? {
        val key = name.trim()
        if (key.isEmpty()) return null
        val workspace = workspaceRepository.load()
        return workspace.persistentVariables[key]
            ?: workspace.globalVariables[key]?.let(ConfigValue::StringValue)
    }

    override suspend fun set(name: String, value: ConfigValue): PersistentVariableChange {
        val key = name.trim()
        if (key.isEmpty()) return PersistentVariableChange(false, key)
        val change = workspaceMutationLock.withLock {
            val workspace = workspaceRepository.load()
            val previous = workspace.persistentVariables[key]
                ?: workspace.globalVariables[key]?.let(ConfigValue::StringValue)
            if (previous != value) {
                workspaceRepository.save(
                    workspace.copy(
                        globalVariables = workspace.globalVariables - key,
                        persistentVariables = workspace.persistentVariables + (key to value),
                    )
                )
            }
            PersistentVariableChange(true, key, previous, value)
        }
        if (change.previous != change.current) dispatch(variableChangedEvent(change))
        return change
    }

    override suspend fun clear(name: String): PersistentVariableChange {
        val key = name.trim()
        if (key.isEmpty()) return PersistentVariableChange(false, key)
        val change = workspaceMutationLock.withLock {
            val workspace = workspaceRepository.load()
            val previous = workspace.persistentVariables[key]
                ?: workspace.globalVariables[key]?.let(ConfigValue::StringValue)
            if (previous != null) {
                workspaceRepository.save(
                    workspace.copy(
                        globalVariables = workspace.globalVariables - key,
                        persistentVariables = workspace.persistentVariables - key,
                    )
                )
            }
            PersistentVariableChange(true, key, previous, null)
        }
        if (change.previous != null) dispatch(variableChangedEvent(change))
        return change
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

        return when (automation.executionPolicy.conflictPolicy) {
            ConflictPolicy.PARALLEL -> runTracked()
            ConflictPolicy.QUEUE -> locks.getOrPut(key) { Mutex() }.withLock { runTracked() }
            ConflictPolicy.IGNORE_NEW -> {
                val lock = locks.getOrPut(key) { Mutex() }
                if (!lock.tryLock()) null else try { runTracked() } finally { lock.unlock() }
            }
            ConflictPolicy.CANCEL_PREVIOUS -> {
                executionJobs.cancel(key)
                runTracked()
            }
        }
    }


    private suspend fun recordLastRun(automationId: AutomationId, timestamp: Long) {
        workspaceMutationLock.withLock {
            val workspace = workspaceRepository.load()
            if (workspace.automationLastRunEpochMs[automationId.value] == timestamp) return@withLock
            workspaceRepository.save(
                workspace.copy(
                    automationLastRunEpochMs =
                        workspace.automationLastRunEpochMs + (automationId.value to timestamp),
                )
            )
        }
    }

    private fun categoryEnabled(category: String?, disabledCategories: Set<String>): Boolean {
        val name = category?.trim().orEmpty()
        return name.isBlank() || name !in disabledCategories
    }

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

    private fun triggerKey(automation: Automation, feature: FeatureRef): String {
        val sourceType = feature.config["source.type"]?.asTraceText().orEmpty()
        val tag = feature.config.string("tag").trim()
        return automation.id.value + "|" + (if (sourceType.isNotBlank()) sourceType else feature.typeId) + "|" + tag
    }

    private fun triggerEnabled(
        workspace: WorkspaceData,
        automation: Automation,
        feature: FeatureRef,
    ): Boolean = triggerKey(automation, feature) !in workspace.disabledTriggerKeys

    private fun resolveAutomation(workspace: WorkspaceData, target: String): Automation? {
        workspace.automations.firstOrNull { it.id.value == target }?.let { return it }
        val matches = workspace.automations.filter { it.name.equals(target, ignoreCase = true) }
        return matches.singleOrNull()
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
