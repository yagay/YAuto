package com.yagay.yauto.importer.macrodroid

import com.yagay.yauto.core.importer.*
import com.yagay.yauto.core.model.*
import kotlinx.serialization.json.*
import java.util.UUID

class MacroDroidImporter(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val mapper: SourceFeatureMapper = MacroDroidMappings.mapper,
) : AutomationImporter {
    override val id = "macrodroid"
    override val displayName = "MacroDroid"

    override fun confidence(input: ImportInput): Int {
        val text = input.utf8OrNull()?.trim() ?: return 0
        if (!text.startsWith("{") && !text.startsWith("[")) return 0
        val score = listOf("m_triggerList", "m_actionList", "m_constraintList", "macroList", "macros", "macroExportVersion").count { it in text }
        return score * 20
    }

    override fun import(input: ImportInput): ImportResult = runCatching {
        val root = json.parseToJsonElement(input.utf8OrNull() ?: error("Not UTF-8 JSON"))
        val macros = findMacros(root)
        require(macros.isNotEmpty()) { "No MacroDroid macro found in export" }

        val issues = mutableListOf<CompatibilityIssue>()
        val trace = mutableListOf<ImportTrace>()
        val blockAliases = buildActionBlockAliases(root, macros)
        val flows = findActionBlocks(root, macros).mapIndexed { index, block ->
            val sourceId = blockIdentifier(block, index)
            val flowId = blockAliases[sourceId] ?: FlowId("import-md-block-$sourceId")
            val name = block.string("m_name", "name", "actionBlockName") ?: "Imported Action Block ${index + 1}"
            val actions = mapActions(
                block.array("m_actionList", "actionList", "actions"),
                "actionBlock[$index]",
                issues,
                blockAliases,
            )
            Flow(
                id = flowId,
                name = name,
                actions = actions,
                description = block.string("m_description", "description"),
                source = SourceMetadata(id, sourceId = sourceId, sourceType = "ActionBlock"),
            ).also {
                trace += ImportTrace("actionBlock[$index]", it.id.value, "IMPORTED", "${actions.size} actions")
            }
        }

        val automations = macros.mapIndexed { index, obj ->
            val sourceId = obj.string("m_GUID", "guid", "id") ?: "macro-$index"
            val name = obj.string("m_name", "name") ?: "Imported Macro ${index + 1}"
            val events = obj.array("m_triggerList", "triggerList", "triggers").mapIndexed { i, item ->
                mapSourceFeature(item, SourceFeatureKind.EVENT, CompatFeatureIds.SOURCE_EVENT, "macro[$index].trigger[$i]", issues)
            }
            val conditions = obj.array("m_constraintList", "constraintList", "constraints").mapIndexed { i, item ->
                PredicateNode.Condition(mapSourceFeature(item, SourceFeatureKind.CONDITION, CompatFeatureIds.SOURCE_CONDITION, "macro[$index].constraint[$i]", issues))
            }
            val actions = mapActions(
                obj.array("m_actionList", "actionList", "actions"),
                "macro[$index]",
                issues,
                blockAliases,
            )
            val automation = Automation(
                id = AutomationId("import-md-$sourceId"),
                name = name,
                enabled = obj.bool("m_enabled") ?: obj.bool("enabled") ?: true,
                activation = Activation(events = events, condition = if (conditions.isEmpty()) null else PredicateNode.All(conditions)),
                onEvent = actions,
                description = obj.string("m_description", "description"),
                source = SourceMetadata(id, sourceId = sourceId, sourceType = "Macro"),
            )
            trace += ImportTrace("macro[$index]", automation.id.value, "IMPORTED", "${events.size} triggers, ${actions.size} actions, ${conditions.size} constraints")
            automation
        }
        ImportResult(id, true, ImportBundle(automations = automations, flows = flows), issues, trace)
    }.getOrElse { error ->
        ImportResult(id, false, issues = listOf(CompatibilityIssue(ImportSeverity.ERROR, input.fileName ?: "input", message = error.message ?: "MacroDroid import failed")))
    }

    private fun findMacros(root: JsonElement): List<JsonObject> = when (root) {
        is JsonArray -> root.mapNotNull { it as? JsonObject }
        is JsonObject -> {
            val singleWrapped = root["macro"] as? JsonObject
            if (singleWrapped != null) listOf(singleWrapped)
            else {
                val direct = listOf("macroList", "macros", "m_macroList").firstNotNullOfOrNull { root[it] as? JsonArray }
                direct?.mapNotNull { it as? JsonObject }
                    ?: if ("m_actionList" in root || "m_triggerList" in root) listOf(root) else emptyList()
            }
        }
        else -> emptyList()
    }

    private fun findActionBlocks(root: JsonElement, macros: List<JsonObject>): List<JsonObject> {
        val blocks = linkedMapOf<String, JsonObject>()
        fun add(array: JsonArray?) {
            array?.mapNotNull { it as? JsonObject }?.forEachIndexed { index, block ->
                blocks.putIfAbsent(blockIdentifier(block, index), block)
            }
        }
        (root as? JsonObject)?.let { obj ->
            add(obj["exportedActionBlocks"] as? JsonArray)
            add(obj["actionBlocks"] as? JsonArray)
            (obj["macro"] as? JsonObject)?.let { macro ->
                add(macro["exportedActionBlocks"] as? JsonArray)
                add(macro["actionBlocks"] as? JsonArray)
            }
        }
        macros.forEach { macro ->
            add(macro["exportedActionBlocks"] as? JsonArray)
            add(macro["actionBlocks"] as? JsonArray)
        }
        return blocks.values.toList()
    }

    private fun buildActionBlockAliases(root: JsonElement, macros: List<JsonObject>): Map<String, FlowId> {
        val aliases = linkedMapOf<String, FlowId>()
        findActionBlocks(root, macros).forEachIndexed { index, block ->
            val primary = blockIdentifier(block, index)
            val flowId = FlowId("import-md-block-$primary")
            listOf("m_GUID", "guid", "id", "actionBlockId", "m_actionBlockId")
                .mapNotNull { block.primitiveText(it) }
                .plus(primary)
                .forEach { aliases[it] = flowId }
        }
        return aliases
    }

    private fun blockIdentifier(block: JsonObject, index: Int): String =
        listOf("m_GUID", "guid", "id", "actionBlockId", "m_actionBlockId")
            .firstNotNullOfOrNull { block.primitiveText(it) }
            ?: "block-$index"

    private fun mapActions(
        items: List<JsonElement>,
        parentPath: String,
        issues: MutableList<CompatibilityIssue>,
        blockAliases: Map<String, FlowId>,
    ): List<ActionNode> = items.mapIndexed { index, item ->
        val path = "$parentPath.action[$index]"
        val obj = item as? JsonObject ?: JsonObject(emptyMap())
        val sourceType = obj.string("m_classType", "classType", "type") ?: "Unknown"
        if (sourceType == "ActionBlockAction") {
            val sourceBlockId = obj.primitiveText("actionBlockId") ?: obj.primitiveText("m_actionBlockId")
            val target = sourceBlockId?.let(blockAliases::get)
            if (target != null) {
                ActionNode.CallFlow(NodeId(UUID.randomUUID().toString()), target)
            } else {
                issues += CompatibilityIssue(ImportSeverity.WARNING, path, sourceType, "Action Block target could not be resolved; source payload preserved")
                ActionNode.Action(NodeId(UUID.randomUUID().toString()), sourceFeature(CompatFeatureIds.SOURCE_ACTION, id, sourceType, item.toString()))
            }
        } else {
            ActionNode.Action(NodeId(UUID.randomUUID().toString()), mapSourceFeature(item, SourceFeatureKind.ACTION, CompatFeatureIds.SOURCE_ACTION, path, issues))
        }
    }

    private fun mapSourceFeature(
        item: JsonElement,
        kind: SourceFeatureKind,
        fallbackId: String,
        path: String,
        issues: MutableList<CompatibilityIssue>,
    ): FeatureRef {
        val obj = item as? JsonObject ?: JsonObject(emptyMap())
        val sourceType = obj.string("m_classType", "classType", "type") ?: "Unknown"

        val native = if (kind == SourceFeatureKind.ACTION) {
            // Per-action constraints cannot be dropped during conversion.
            if (obj.array("m_constraintList", "constraintList", "constraints").isEmpty())
                MacroDroidMappings.nativeAction(obj, id, sourceType, item.toString())
            else null
        } else {
            MacroDroidMappings.nativeContext(obj, kind, id, sourceType, item.toString())
        }
        if (native != null) return native

        val mapped = mapper.targetId(sourceType, kind)
        if (mapped == null) {
            issues += CompatibilityIssue(
                ImportSeverity.WARNING,
                path,
                sourceType,
                "No native YAuto mapping yet; source payload preserved",
            )
        } else {
            issues += CompatibilityIssue(
                ImportSeverity.WARNING,
                path,
                sourceType,
                "Native feature type is known but required source fields or semantics could not be translated; source payload preserved",
                suggestedFeatureId = mapped,
            )
        }
        // Never assign a native typeId unless nativeAction/nativeContext produced a complete native
        // configuration. A compatibility node cannot accidentally execute with missing/default data.
        return sourceFeature(fallbackId, id, sourceType, item.toString())
    }

    private fun JsonObject.string(vararg keys: String): String? = keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.contentOrNull }
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.array(vararg keys: String): List<JsonElement> = keys.firstNotNullOfOrNull { this[it] as? JsonArray }?.toList().orEmpty()
    private fun JsonObject.primitiveText(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
