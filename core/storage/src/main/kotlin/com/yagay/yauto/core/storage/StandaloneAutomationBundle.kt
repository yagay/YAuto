package com.yagay.yauto.core.storage

import com.yagay.yauto.core.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class StandaloneAutomationManifest(
    val formatVersion: Int = 1,
    val createdAtEpochMs: Long,
    val sourceAutomationId: String,
    val name: String,
    val requiredFeatureIds: Set<String>,
)

@Serializable
data class StandaloneAutomationBundle(
    val manifest: StandaloneAutomationManifest,
    val automation: Automation,
    val flows: List<Flow>,
    val persistentVariables: Map<String, ConfigValue> = emptyMap(),
)

object StandaloneAutomationCodec {
    private val json = Json {
        encodeDefaults = true
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun encode(
        workspace: WorkspaceData,
        automationIdOrName: String,
        includePersistentVariables: Boolean = true,
        now: Long = System.currentTimeMillis(),
    ): String {
        val automation = workspace.automations.firstOrNull {
            it.id.value == automationIdOrName || it.name.equals(automationIdOrName, true)
        } ?: error("Automation not found: $automationIdOrName")

        val flows = workspace.flowClosure(automation)
        val subset = WorkspaceData(
            automations = listOf(automation),
            flows = flows,
            persistentVariables = if (includePersistentVariables) workspace.persistentVariables else emptyMap(),
        )
        return json.encodeToString(
            StandaloneAutomationBundle.serializer(),
            StandaloneAutomationBundle(
                manifest = StandaloneAutomationManifest(
                    createdAtEpochMs = now,
                    sourceAutomationId = automation.id.value,
                    name = automation.name,
                    requiredFeatureIds = subset.featureIds(),
                ),
                automation = automation,
                flows = flows,
                persistentVariables = subset.persistentVariables,
            ),
        )
    }

    fun decode(text: String): StandaloneAutomationBundle {
        val bundle = json.decodeFromString(StandaloneAutomationBundle.serializer(), text)
        require(bundle.manifest.formatVersion == 1) { "Unsupported standalone format" }
        require(bundle.automation.id.value == bundle.manifest.sourceAutomationId) {
            "Standalone manifest automation mismatch"
        }
        require(bundle.flows.map { it.id }.distinct().size == bundle.flows.size) {
            "Duplicate flow IDs"
        }
        return bundle
    }
}

fun WorkspaceData.flowClosure(automation: Automation): List<Flow> {
    val byId = flows.associateBy { it.id }
    val needed = linkedSetOf<FlowId>()

    fun collect(nodes: List<ActionNode>) {
        nodes.walk().forEach { node ->
            if (node is ActionNode.CallFlow && needed.add(node.flowId)) {
                byId[node.flowId]?.let { flow -> collect(flow.actions) }
            }
        }
    }

    collect(automation.onEnter)
    collect(automation.onEvent)
    collect(automation.onExit)

    return needed.mapNotNull(byId::get)
}
