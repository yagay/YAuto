package com.yagay.yauto.core.storage

import com.yagay.yauto.core.model.Automation
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.Flow
import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceData(
    val schemaVersion: Int = 1,
    val automations: List<Automation> = emptyList(),
    val flows: List<Flow> = emptyList(),
    /** Legacy string-only globals kept for backward-compatible workspace restores. */
    val globalVariables: Map<String, String> = emptyMap(),
    /** Canonical typed persistent variables used by native YAuto automation features. */
    val persistentVariables: Map<String, ConfigValue> = emptyMap(),
    /** Named automation categories currently disabled at runtime. */
    val disabledCategories: Set<String> = emptySet(),
    /** Persistent last successful/finished execution timestamp by automation ID. */
    val automationLastRunEpochMs: Map<String, Long> = emptyMap(),
    /** Master runtime gate. When false, automatic event dispatch is paused. */
    val runtimeEnabled: Boolean = true,
    /** Disabled activation event keys in "automationId|typeId|tag" form. */
    val disabledTriggerKeys: Set<String> = emptySet(),
)

interface WorkspaceRepository {
    suspend fun load(): WorkspaceData
    suspend fun save(data: WorkspaceData)
}

interface ObservableWorkspaceRepository : WorkspaceRepository {
    fun snapshotOrNull(): WorkspaceData?
    fun addListener(listener: (WorkspaceData) -> Unit): AutoCloseable
}

fun WorkspaceData.merge(
    automationsToImport: List<Automation>,
    flowsToImport: List<Flow>,
    variablesToImport: Map<String, String>,
    persistentVariablesToImport: Map<String, ConfigValue> = emptyMap(),
): WorkspaceData {
    val incomingAutomations = automationsToImport.associateBy { it.id.value }
    val incomingFlows = flowsToImport.associateBy { it.id.value }
    return copy(
        automations = (automations.filterNot { it.id.value in incomingAutomations } + incomingAutomations.values).sortedBy { it.name.lowercase() },
        flows = (flows.filterNot { it.id.value in incomingFlows } + incomingFlows.values).sortedBy { it.name.lowercase() },
        globalVariables = globalVariables + variablesToImport,
        persistentVariables = persistentVariables + persistentVariablesToImport,
    )
}
