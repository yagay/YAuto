package com.yagay.yauto

import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.model.ActionNode
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.PredicateNode
import com.yagay.yauto.core.registry.AccessRequirement
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.FieldSchema
import com.yagay.yauto.core.registry.preferredBackendId
import com.yagay.yauto.core.registry.resolvedImplementationOptions
import com.yagay.yauto.core.storage.WorkspaceData
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LsposedScopeState(
    val connected: Boolean = false,
    val currentScope: List<String> = emptyList(),
    val frameworkName: String? = null,
    val frameworkVersion: String? = null,
    val lastError: String? = null,
)

class LsposedScopeManager : XposedServiceHelper.OnServiceListener {
    @Volatile
    private var service: XposedService? = null

    private val _state = MutableStateFlow(LsposedScopeState())
    val state: StateFlow<LsposedScopeState> = _state.asStateFlow()

    fun start() {
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        this.service = service
        publish(service, null)
    }

    override fun onServiceDied(service: XposedService) {
        if (this.service === service) this.service = null
        _state.value = LsposedScopeState(lastError = "LSPosed service disconnected")
    }

    fun refresh() {
        val current = service
        if (current == null) {
            _state.value = _state.value.copy(
                connected = false,
                currentScope = emptyList(),
                lastError = "LSPosed service is not connected",
            )
        } else {
            publish(current, null)
        }
    }

    fun requestScopes(
        packages: Collection<String>,
        onResult: (Result<List<String>>) -> Unit = {},
    ) {
        val requested = packages
            .asSequence()
            .map(String::trim)
            .filter { it == SYSTEM_SCOPE || PACKAGE_NAME.matches(it) }
            .distinct()
            .toList()

        val current = service
        if (current == null) {
            val error = IllegalStateException("LSPosed service is not connected")
            _state.value = _state.value.copy(lastError = error.message)
            onResult(Result.failure(error))
            return
        }

        val granted = runCatching { current.scope.toSet() }.getOrElse { error ->
            _state.value = _state.value.copy(lastError = error.message)
            onResult(Result.failure(error))
            return
        }
        val missing = requested.filterNot(granted::contains)
        if (missing.isEmpty()) {
            publish(current, null)
            onResult(Result.success(granted.sorted()))
            return
        }

        runCatching {
            current.requestScope(missing, object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(scope: List<String>) {
                    publish(current, null)
                    onResult(Result.success(scope.sorted()))
                }

                override fun onScopeRequestFailed(message: String) {
                    _state.value = _state.value.copy(lastError = message)
                    onResult(Result.failure(IllegalStateException(message)))
                }
            })
        }.onFailure { error ->
            _state.value = _state.value.copy(lastError = error.message)
            onResult(Result.failure(error))
        }
    }

    private fun publish(service: XposedService, error: String?) {
        _state.value = runCatching {
            LsposedScopeState(
                connected = true,
                currentScope = service.scope.distinct().sorted(),
                frameworkName = service.frameworkName,
                frameworkVersion = service.frameworkVersion,
                lastError = error,
            )
        }.getOrElse { failure ->
            LsposedScopeState(lastError = failure.message)
        }
    }

    companion object {
        const val SYSTEM_SCOPE = "system"
        private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    }
}

fun recommendedLsposedScopes(
    workspace: WorkspaceData,
    registry: FeatureRegistry,
): List<String> = buildSet {
    add(LsposedScopeManager.SYSTEM_SCOPE)

    workspaceFeatureRefs(workspace).forEach { feature ->
        val descriptor = registry.descriptor(feature.typeId) ?: return@forEach
        val preferredBackend = feature.preferredBackendId()
        val explicitlyLsposed =
            AccessRequirement.LSPOSED in descriptor.accessRequirements ||
                CapabilityIds.LSPOSED_HOOK in descriptor.capabilities ||
                CapabilityIds.LSPOSED in descriptor.capabilities
        val lsposedOption = descriptor.resolvedImplementationOptions().any { option ->
            AccessRequirement.LSPOSED in option.requirements
        }
        val usesLsposed = explicitlyLsposed ||
            preferredBackend == "lsposed" ||
            (preferredBackend == null && lsposedOption)

        if (!usesLsposed || (preferredBackend != null && preferredBackend != "lsposed" && !explicitlyLsposed)) {
            return@forEach
        }

        descriptor.fields
            .filterIsInstance<FieldSchema.AppPicker>()
            .forEach { field ->
                val packageName = (feature.config[field.key] as? ConfigValue.StringValue)
                    ?.value
                    ?.trim()
                    .orEmpty()
                if (PACKAGE_NAME.matches(packageName)) add(packageName)
            }
    }
}.sortedWith(compareBy<String> { it != LsposedScopeManager.SYSTEM_SCOPE }.thenBy { it })

private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")

private fun workspaceFeatureRefs(workspace: WorkspaceData): Sequence<FeatureRef> = sequence {
    workspace.automations.forEach { automation ->
        yieldAll(automation.activation.events)
        yieldAll(automation.activation.states)
        yieldAll(predicateFeatureRefs(automation.activation.condition))
        yieldAll(actionFeatureRefs(automation.onEnter))
        yieldAll(actionFeatureRefs(automation.onEvent))
        yieldAll(actionFeatureRefs(automation.onExit))
    }
    workspace.flows.forEach { flow ->
        yieldAll(actionFeatureRefs(flow.actions))
    }
}

private fun predicateFeatureRefs(node: PredicateNode?): Sequence<FeatureRef> = sequence {
    when (node) {
        null -> Unit
        is PredicateNode.Condition -> yield(node.feature)
        is PredicateNode.All -> node.children.forEach { yieldAll(predicateFeatureRefs(it)) }
        is PredicateNode.Any -> node.children.forEach { yieldAll(predicateFeatureRefs(it)) }
        is PredicateNode.None -> node.children.forEach { yieldAll(predicateFeatureRefs(it)) }
        is PredicateNode.Xor -> node.children.forEach { yieldAll(predicateFeatureRefs(it)) }
        is PredicateNode.Expression,
        is PredicateNode.Literal -> Unit
    }
}

private fun actionFeatureRefs(nodes: List<ActionNode>): Sequence<FeatureRef> = sequence {
    nodes.forEach { node ->
        when (node) {
            is ActionNode.Action -> yield(node.feature)
            is ActionNode.If -> {
                yieldAll(predicateFeatureRefs(node.condition))
                yieldAll(actionFeatureRefs(node.thenActions))
                yieldAll(actionFeatureRefs(node.elseActions))
            }
            is ActionNode.Switch -> {
                node.cases.forEach { yieldAll(actionFeatureRefs(it.actions)) }
                yieldAll(actionFeatureRefs(node.defaultActions))
            }
            is ActionNode.Repeat -> yieldAll(actionFeatureRefs(node.actions))
            is ActionNode.While -> {
                yieldAll(predicateFeatureRefs(node.condition))
                yieldAll(actionFeatureRefs(node.actions))
            }
            is ActionNode.DoWhile -> {
                yieldAll(predicateFeatureRefs(node.condition))
                yieldAll(actionFeatureRefs(node.actions))
            }
            is ActionNode.ForEach -> yieldAll(actionFeatureRefs(node.actions))
            is ActionNode.Parallel -> node.branches.forEach { yieldAll(actionFeatureRefs(it)) }
            is ActionNode.Try -> {
                yieldAll(actionFeatureRefs(node.actions))
                yieldAll(actionFeatureRefs(node.onError))
                yieldAll(actionFeatureRefs(node.finallyActions))
            }
            is ActionNode.WaitUntil -> yieldAll(predicateFeatureRefs(node.condition))
            is ActionNode.WaitEvent -> yieldAll(node.events)
            is ActionNode.CallFlow,
            is ActionNode.Label,
            is ActionNode.Goto,
            is ActionNode.Return,
            is ActionNode.Break,
            is ActionNode.Continue -> Unit
        }
    }
}
