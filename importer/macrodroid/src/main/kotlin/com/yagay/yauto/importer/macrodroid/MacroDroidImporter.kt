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
        val root = json.parseToJsonElement(input.utf8OrNull() ?: error(userText("import.not_utf8_json")))
        val macros = findMacros(root)
        require(macros.isNotEmpty()) { userText("import.macrodroid.none") }

        val issues = mutableListOf<CompatibilityIssue>()
        val trace = mutableListOf<ImportTrace>()
        val blockAliases = buildActionBlockAliases(root, macros)
        val flows = findActionBlocks(root, macros).mapIndexed { index, block ->
            val sourceId = blockIdentifier(block, index)
            val flowId = blockAliases[sourceId] ?: FlowId("import-md-block-$sourceId")
            val name = block.string("m_name", "name", "actionBlockName") ?: userText("import.macrodroid.block_name", index + 1)
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
                trace += ImportTrace(
                    "actionBlock[$index]",
                    it.id.value,
                    "IMPORTED",
                    userText("import.trace.actions", actions.size),
                )
            }
        }

        val automations = macros.mapIndexed { index, obj ->
            val sourceId = obj.string("m_GUID", "guid", "id") ?: "macro-$index"
            val name = obj.string("m_name", "name") ?: userText("import.macrodroid.macro_name", index + 1)
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
            trace += ImportTrace(
                "macro[$index]",
                automation.id.value,
                "IMPORTED",
                userText("import.trace.macrodroid_macro", events.size, actions.size, conditions.size),
            )
            automation
        }
        ImportResult(id, true, ImportBundle(automations = automations, flows = flows), issues, trace)
    }.getOrElse { error ->
        ImportResult(id, false, issues = listOf(CompatibilityIssue(ImportSeverity.ERROR, input.fileName ?: "input", message = error.message ?: userText("import.macrodroid.failed"))))
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
    ): List<ActionNode> {
        val out = mutableListOf<ActionNode>()
        var index = 0
        while (index < items.size) {
            val item = items[index]
            val obj = item as? JsonObject ?: JsonObject(emptyMap())
            val sourceType = obj.string("m_classType", "classType", "type") ?: "Unknown"
            val path = parentPath + ".action[" + index + "]"

            if (sourceType == "IfConditionAction" && obj.bool("m_isDisabled") != true) {
                val parsed = parseConditional(items, index, parentPath, issues, blockAliases)
                if (parsed != null) {
                    out += parsed.node
                    index = parsed.nextIndex
                    continue
                }
            }

            if (sourceType == "WaitUntilTriggerAction" && obj.bool("m_isDisabled") != true) {
                val parsed = parseWaitUntilTrigger(item, path, issues)
                if (parsed != null) {
                    out += parsed
                    index++
                    continue
                }
            }

            if (sourceType == "LoopAction" && obj.bool("m_isDisabled") != true) {
                val parsed = parseLoop(items, index, parentPath, issues, blockAliases)
                if (parsed != null) {
                    out += parsed.node
                    index = parsed.nextIndex
                    continue
                }
            }

            when (sourceType) {
                "BreakFromLoopAction" -> out += ActionNode.Break(NodeId(UUID.randomUUID().toString()))
                "ContinueLoopAction" -> out += ActionNode.Continue(NodeId(UUID.randomUUID().toString()))
                "ExitMacroAction", "StopMacroAction" -> out += ActionNode.Return(
                    NodeId(UUID.randomUUID().toString()),
                    ConfigValue.NullValue,
                )
                "EmptyAction", "SeparatorAction", "ActionGroupAction", "ActionGroupEndAction" ->
                    out += ActionNode.Action(
                        NodeId(UUID.randomUUID().toString()),
                        FeatureRef("core.noop"),
                    )
                "ElseAction", "ElseIfConditionAction", "EndIfAction", "EndLoopAction" -> {
                    // Stray branch markers are preserved instead of being silently discarded.
                    out += compatibilityAction(item, path, sourceType, issues)
                }
                else -> out += mapSingleAction(item, path, issues, blockAliases)
            }
            index++
        }
        return out
    }

    private data class ParsedLoop(val node: ActionNode, val nextIndex: Int)

    private data class ParsedConditional(val node: ActionNode, val nextIndex: Int)
    private data class ConditionalSegment(
        val marker: JsonObject?,
        val start: Int,
        val end: Int,
    )


    private fun parseWaitUntilTrigger(
        item: JsonElement,
        path: String,
        issues: MutableList<CompatibilityIssue>,
    ): ActionNode? {
        val obj = item as? JsonObject ?: return null
        val triggerItems = obj.array(
            "m_triggersToWaitFor",
            "triggersToWaitFor",
            "m_triggerList",
            "triggerList",
            "triggers",
        )
        if (triggerItems.isEmpty()) return null
        val events = triggerItems.mapIndexed { index, trigger ->
            mapSourceFeature(
                trigger,
                SourceFeatureKind.EVENT,
                CompatFeatureIds.SOURCE_EVENT,
                path + ".waitTrigger[" + index + "]",
                issues,
            )
        }
        val timeoutEnabled = obj.bool("m_timeoutEnabled")
            ?: obj.bool("timeoutEnabled")
            ?: false
        val timeoutSeconds = obj.number("m_timeoutSeconds", "timeoutSeconds")
            ?.coerceAtLeast(0.0)
            ?: 0.0
        val continueOnTimeout = obj.bool("m_continueOnTimeout")
            ?: obj.bool("continueOnTimeout")
            ?: false
        val base = ActionNode.WaitEvent(
            id = NodeId(UUID.randomUUID().toString()),
            events = events,
            timeoutMs = (timeoutSeconds * 1_000.0).toLong().coerceAtMost(7L * 24L * 60L * 60L * 1_000L),
            unlimited = !timeoutEnabled,
            continueOnTimeout = continueOnTimeout,
        )
        val constraint = predicateFromConstraints(obj, path + ".constraint", issues)
        return if (constraint == null) base
        else ActionNode.If(
            NodeId(UUID.randomUUID().toString()),
            constraint,
            listOf(base),
        )
    }

    private fun parseLoop(
        items: List<JsonElement>,
        startIndex: Int,
        parentPath: String,
        issues: MutableList<CompatibilityIssue>,
        blockAliases: Map<String, FlowId>,
    ): ParsedLoop? {
        val loop = items.getOrNull(startIndex) as? JsonObject ?: return null
        var depth = 0
        var endIndex = -1
        var index = startIndex + 1
        while (index < items.size) {
            val obj = items[index] as? JsonObject
            when (obj?.string("m_classType", "classType", "type")) {
                "LoopAction" -> depth++
                "EndLoopAction" -> {
                    if (depth == 0) {
                        endIndex = index
                        break
                    }
                    depth--
                }
            }
            index++
        }
        if (endIndex < 0) return null

        val body = mapActions(
            items.subList(startIndex + 1, endIndex),
            parentPath + ".loop[" + startIndex + "]",
            issues,
            blockAliases,
        )
        val option = loop.number("m_option", "option")?.toInt() ?: return null
        val node = when (option) {
            0 -> {
                val count = loop.number("m_fixedOptionCount", "fixedOptionCount")?.toInt() ?: return null
                if (count <= 0) return null
                ActionNode.Repeat(
                    NodeId(UUID.randomUUID().toString()),
                    count.coerceAtMost(100_000),
                    body,
                )
            }
            1 -> {
                val predicate = predicateFromConstraints(
                    loop,
                    parentPath + ".loop[" + startIndex + "].condition",
                    issues,
                ) ?: return null
                ActionNode.While(NodeId(UUID.randomUUID().toString()), predicate, body)
            }
            2 -> {
                val predicate = predicateFromConstraints(
                    loop,
                    parentPath + ".loop[" + startIndex + "].condition",
                    issues,
                ) ?: return null
                ActionNode.DoWhile(NodeId(UUID.randomUUID().toString()), predicate, body)
            }
            3 -> ActionNode.While(
                NodeId(UUID.randomUUID().toString()),
                PredicateNode.Literal(true),
                body,
            )
            else -> return null
        }
        return ParsedLoop(node, endIndex + 1)
    }

    private fun parseConditional(
        items: List<JsonElement>,
        startIndex: Int,
        parentPath: String,
        issues: MutableList<CompatibilityIssue>,
        blockAliases: Map<String, FlowId>,
    ): ParsedConditional? {
        val first = items.getOrNull(startIndex) as? JsonObject ?: return null
        val firstPredicate = predicateFromConstraints(
            first,
            parentPath + ".action[" + startIndex + "].condition",
            issues,
        ) ?: return null

        val segments = mutableListOf<ConditionalSegment>()
        var depth = 0
        var segmentMarker: JsonObject? = first
        var segmentStart = startIndex + 1
        var endIfIndex = -1
        var index = startIndex + 1

        while (index < items.size) {
            val obj = items[index] as? JsonObject
            val type = obj?.string("m_classType", "classType", "type").orEmpty()
            when (type) {
                "IfConditionAction" -> depth++
                "EndIfAction" -> {
                    if (depth == 0) {
                        segments += ConditionalSegment(segmentMarker, segmentStart, index)
                        endIfIndex = index
                        break
                    }
                    depth--
                }
                "ElseAction", "ElseIfConditionAction" -> if (depth == 0) {
                    segments += ConditionalSegment(segmentMarker, segmentStart, index)
                    segmentMarker = obj
                    segmentStart = index + 1
                }
            }
            index++
        }
        if (endIfIndex < 0 || segments.isEmpty()) return null

        var elseBranch: List<ActionNode> = emptyList()
        for (segmentIndex in segments.indices.reversed()) {
            val segment = segments[segmentIndex]
            val actions = mapActions(
                items.subList(segment.start, segment.end),
                parentPath + ".if[" + startIndex + "].branch[" + segmentIndex + "]",
                issues,
                blockAliases,
            )
            val marker = segment.marker
            val markerType = marker?.string("m_classType", "classType", "type").orEmpty()
            when {
                markerType == "ElseAction" -> elseBranch = actions
                markerType == "IfConditionAction" -> {
                    elseBranch = listOf(
                        ActionNode.If(
                            NodeId(UUID.randomUUID().toString()),
                            firstPredicate,
                            actions,
                            elseBranch,
                        )
                    )
                }
                markerType == "ElseIfConditionAction" -> {
                    val predicate = predicateFromConstraints(
                        marker ?: return null,
                        parentPath + ".if[" + startIndex + "].elseif[" + segmentIndex + "]",
                        issues,
                    ) ?: return null
                    elseBranch = listOf(
                        ActionNode.If(
                            NodeId(UUID.randomUUID().toString()),
                            predicate,
                            actions,
                            elseBranch,
                        )
                    )
                }
                else -> return null
            }
        }
        return ParsedConditional(elseBranch.singleOrNull() ?: return null, endIfIndex + 1)
    }

    private fun mapSingleAction(
        item: JsonElement,
        path: String,
        issues: MutableList<CompatibilityIssue>,
        blockAliases: Map<String, FlowId>,
    ): ActionNode {
        val obj = item as? JsonObject ?: JsonObject(emptyMap())
        val sourceType = obj.string("m_classType", "classType", "type") ?: "Unknown"
        val base = if (sourceType == "ActionBlockAction") {
            val sourceBlockId = obj.primitiveText("actionBlockId") ?: obj.primitiveText("m_actionBlockId")
            val target = sourceBlockId?.let(blockAliases::get)
            if (target != null) {
                ActionNode.CallFlow(NodeId(UUID.randomUUID().toString()), target)
            } else {
                issues += CompatibilityIssue(
                    ImportSeverity.WARNING,
                    path,
                    sourceType,
                    userText("import.macrodroid.block_unresolved"),
                )
                compatibilityAction(item, path, sourceType, issues, addIssue = false)
            }
        } else {
            ActionNode.Action(
                NodeId(UUID.randomUUID().toString()),
                mapSourceFeature(
                    item,
                    SourceFeatureKind.ACTION,
                    CompatFeatureIds.SOURCE_ACTION,
                    path,
                    issues,
                ),
                enabled = obj.bool("m_isDisabled") != true,
                comment = obj.string("m_comment", "comment"),
                failurePolicy = ActionFailurePolicy.CONTINUE,
            )
        }

        val predicate = predicateFromConstraints(obj, path + ".constraint", issues)
        return if (predicate == null) base
        else ActionNode.If(
            NodeId(UUID.randomUUID().toString()),
            predicate,
            listOf(base),
        )
    }

    private fun predicateFromConstraints(
        obj: JsonObject,
        path: String,
        issues: MutableList<CompatibilityIssue>,
    ): PredicateNode? {
        val constraints = obj.array("m_constraintList", "constraintList", "constraints")
            .filterNot { (it as? JsonObject)?.bool("m_isDisabled") == true }
        if (constraints.isEmpty()) return null
        val nodes = constraints.mapIndexed { index, item ->
            PredicateNode.Condition(
                mapSourceFeature(
                    item,
                    SourceFeatureKind.CONDITION,
                    CompatFeatureIds.SOURCE_CONDITION,
                    path + "[" + index + "]",
                    issues,
                )
            )
        }
        if (nodes.size == 1) return nodes.first()
        return if (obj.bool("m_isOrCondition") == true) PredicateNode.Any(nodes)
        else PredicateNode.All(nodes)
    }

    private fun compatibilityAction(
        item: JsonElement,
        path: String,
        sourceType: String,
        issues: MutableList<CompatibilityIssue>,
        addIssue: Boolean = true,
    ): ActionNode.Action {
        if (addIssue) {
            issues += CompatibilityIssue(
                ImportSeverity.WARNING,
                path,
                sourceType,
                userText("import.macrodroid.no_mapping"),
            )
        }
        return ActionNode.Action(
            NodeId(UUID.randomUUID().toString()),
            sourceFeature(CompatFeatureIds.SOURCE_ACTION, id, sourceType, item.toString()),
        )
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
            MacroDroidMappings.nativeAction(obj, id, sourceType, item.toString())
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
                userText("import.macrodroid.no_mapping"),
            )
        } else {
            issues += CompatibilityIssue(
                ImportSeverity.WARNING,
                path,
                sourceType,
                userText("import.macrodroid.partial_mapping"),
                suggestedFeatureId = mapped,
            )
        }
        // Never assign a native typeId unless nativeAction/nativeContext produced a complete native
        // configuration. A compatibility node cannot accidentally execute with missing/default data.
        return sourceFeature(fallbackId, id, sourceType, item.toString())
    }

    private fun JsonObject.string(vararg keys: String): String? = keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.contentOrNull }
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.number(vararg keys: String): Double? =
        keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.doubleOrNull }
    private fun JsonObject.array(vararg keys: String): List<JsonElement> = keys.firstNotNullOfOrNull { this[it] as? JsonArray }?.toList().orEmpty()
    private fun JsonObject.primitiveText(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
