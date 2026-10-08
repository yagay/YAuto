package com.yagay.yauto.core.storage

import com.yagay.yauto.core.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class WorkspaceBackup(val manifest: BackupManifest, val workspace: WorkspaceData)

object WorkspaceBackupCodec {
    private val json = Json { encodeDefaults = true; prettyPrint = true; ignoreUnknownKeys = true }
    fun encode(workspace: WorkspaceData, appVersion: String, now: Long = System.currentTimeMillis()): String =
        json.encodeToString(WorkspaceBackup.serializer(), WorkspaceBackup(
            BackupManifest(BackupFormat.CURRENT_VERSION, appVersion, now, workspace.featureIds()), workspace))

    fun decode(text: String): WorkspaceBackup {
        val backup = json.decodeFromString(WorkspaceBackup.serializer(), text)
        require(backup.manifest.formatVersion == BackupFormat.CURRENT_VERSION) { "Unsupported backup version" }
        require(backup.workspace.schemaVersion == 1 && backup.manifest.databaseSchemaVersion == 1) { "Unsupported workspace schema" }
        require(backup.manifest.pluginApiVersion == 1 && backup.manifest.ipcProtocolVersion == 1) { "Unsupported plugin or IPC version" }
        require(backup.workspace.automations.map { it.id }.distinct().size == backup.workspace.automations.size) { "Duplicate automation IDs" }
        require(backup.workspace.flows.map { it.id }.distinct().size == backup.workspace.flows.size) { "Duplicate flow IDs" }
        return backup
    }
}

fun List<ActionNode>.walk(): Sequence<ActionNode> = asSequence().flatMap { node ->
    sequenceOf(node) + when (node) {
        is ActionNode.If -> (node.thenActions + node.elseActions).walk()
        is ActionNode.Switch -> (node.cases.flatMap { it.actions } + node.defaultActions).walk()
        is ActionNode.Repeat -> node.actions.walk()
        is ActionNode.While -> node.actions.walk()
        is ActionNode.DoWhile -> node.actions.walk()
        is ActionNode.ForEach -> node.actions.walk()
        is ActionNode.Parallel -> node.branches.flatten().walk()
        is ActionNode.Try -> (node.actions + node.onError + node.finallyActions).walk()
        else -> emptySequence()
    }
}

fun WorkspaceData.references(flowId: FlowId): Boolean =
    (automations.flatMap { it.onEnter + it.onEvent + it.onExit } + flows.filterNot { it.id == flowId }.flatMap { it.actions })
        .walk().any { it is ActionNode.CallFlow && it.flowId == flowId }

fun WorkspaceData.featureIds(): Set<String> = buildSet {
    fun predicate(node: PredicateNode?) {
        when (node) {
            is PredicateNode.Condition -> add(node.feature.typeId)
            is PredicateNode.All -> node.children.forEach(::predicate)
            is PredicateNode.Any -> node.children.forEach(::predicate)
            is PredicateNode.None -> node.children.forEach(::predicate)
            is PredicateNode.Xor -> node.children.forEach(::predicate)
            else -> Unit
        }
    }
    automations.forEach { rule ->
        addAll(rule.activation.events.map { it.typeId }); addAll(rule.activation.states.map { it.typeId }); predicate(rule.activation.condition)
    }
    (automations.flatMap { it.onEnter + it.onEvent + it.onExit } + flows.flatMap { it.actions }).walk().forEach { node ->
        when (node) {
            is ActionNode.Action -> add(node.feature.typeId)
            is ActionNode.If -> predicate(node.condition)
            is ActionNode.While -> predicate(node.condition)
            is ActionNode.DoWhile -> predicate(node.condition)
            is ActionNode.WaitUntil -> predicate(node.condition)
            is ActionNode.WaitEvent -> addAll(node.events.map { it.typeId })
            else -> Unit
        }
    }
}
