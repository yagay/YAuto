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
        val score = listOf("m_triggerList", "m_actionList", "m_constraintList", "macroList", "macros").count { it in text }
        return score * 20
    }

    override fun import(input: ImportInput): ImportResult = runCatching {
        val root = json.parseToJsonElement(input.utf8OrNull() ?: error("Not UTF-8 JSON"))
        val macros = findMacros(root)
        val issues = mutableListOf<CompatibilityIssue>()
        val trace = mutableListOf<ImportTrace>()
        val automations = macros.mapIndexed { index, obj ->
            val sourceId = obj.string("m_GUID") ?: obj.string("guid") ?: obj.string("id") ?: "macro-$index"
            val name = obj.string("m_name") ?: obj.string("name") ?: "Imported Macro ${index + 1}"
            val events = obj.array("m_triggerList", "triggerList", "triggers").mapIndexed { i, item ->
                mapSourceFeature(item, SourceFeatureKind.EVENT, CompatFeatureIds.SOURCE_EVENT, "macro[$index].trigger[$i]", issues)
            }
            val conditions = obj.array("m_constraintList", "constraintList", "constraints").mapIndexed { i, item ->
                PredicateNode.Condition(mapSourceFeature(item, SourceFeatureKind.CONDITION, CompatFeatureIds.SOURCE_CONDITION, "macro[$index].constraint[$i]", issues))
            }
            val actions = obj.array("m_actionList", "actionList", "actions").mapIndexed { i, item ->
                val feature = mapSourceFeature(item, SourceFeatureKind.ACTION, CompatFeatureIds.SOURCE_ACTION, "macro[$index].action[$i]", issues)
                ActionNode.Action(NodeId(UUID.randomUUID().toString()), feature)
            }
            val automation = Automation(
                id = AutomationId("import-md-$sourceId"),
                name = name,
                enabled = obj.bool("m_enabled") ?: obj.bool("enabled") ?: true,
                activation = Activation(events = events, condition = if (conditions.isEmpty()) null else PredicateNode.All(conditions)),
                onEvent = actions,
                description = obj.string("m_description") ?: obj.string("description"),
                source = SourceMetadata(id, sourceId = sourceId, sourceType = "Macro"),
            )
            trace += ImportTrace("macro[$index]", automation.id.value, "IMPORTED", "${events.size} triggers, ${actions.size} actions, ${conditions.size} constraints")
            automation
        }
        ImportResult(id, true, ImportBundle(automations = automations), issues, trace)
    }.getOrElse { error ->
        ImportResult(id, false, issues = listOf(CompatibilityIssue(ImportSeverity.ERROR, input.fileName ?: "input", message = error.message ?: "MacroDroid import failed")))
    }

    private fun findMacros(root: JsonElement): List<JsonObject> = when (root) {
        is JsonArray -> root.mapNotNull { it as? JsonObject }
        is JsonObject -> {
            val direct = listOf("macroList", "macros", "m_macroList").firstNotNullOfOrNull { root[it] as? JsonArray }
            direct?.mapNotNull { it as? JsonObject }
                ?: if ("m_actionList" in root || "m_triggerList" in root) listOf(root) else emptyList()
        }
        else -> emptyList()
    }

    private fun mapSourceFeature(item: JsonElement, kind: SourceFeatureKind, fallbackId: String, path: String, issues: MutableList<CompatibilityIssue>): FeatureRef {
        val obj = item as? JsonObject ?: JsonObject(emptyMap())
        val sourceType = obj.string("m_classType") ?: obj.string("classType") ?: obj.string("type") ?: "Unknown"
        val mapped = mapper.targetId(sourceType, kind)
        if (mapped == null) issues += CompatibilityIssue(ImportSeverity.WARNING, path, sourceType, "No native YAuto mapping yet; source payload preserved")
        return sourceFeature(mapped ?: fallbackId, id, sourceType, item.toString())
    }

    private fun JsonObject.string(vararg keys: String): String? = keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.contentOrNull }
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.array(vararg keys: String): List<JsonElement> = keys.firstNotNullOfOrNull { this[it] as? JsonArray }?.toList().orEmpty()
}
