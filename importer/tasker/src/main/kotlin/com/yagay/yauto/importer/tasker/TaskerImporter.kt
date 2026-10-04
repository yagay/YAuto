package com.yagay.yauto.importer.tasker

import com.yagay.yauto.core.importer.*
import com.yagay.yauto.core.model.*
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import java.io.StringWriter

class TaskerImporter : AutomationImporter {
    override val id = "tasker"
    override val displayName = "Tasker"

    override fun confidence(input: ImportInput): Int {
        val text = input.utf8OrNull()?.trimStart() ?: return 0
        if (!text.startsWith("<")) return 0
        return when {
            "TaskerData" in text -> 100
            "<Task" in text || "<Profile" in text -> 65
            else -> 0
        }
    }

    override fun import(input: ImportInput): ImportResult = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(input.bytes))
        val issues = mutableListOf<CompatibilityIssue>()
        val trace = mutableListOf<ImportTrace>()

        val taskNodes = doc.getElementsByTagName("Task")
        val taskDefs = (0 until taskNodes.length).mapNotNull { i ->
            val task = taskNodes.item(i) as? Element ?: return@mapNotNull null
            val taskId = task.childText("id") ?: task.getAttribute("sr").ifBlank { "task-$i" }.removePrefix("task")
            val name = task.childText("nme") ?: userText("import.tasker.task_name", i + 1)
            TaskDef(task, taskId, name, FlowId("import-tasker-$taskId"))
        }
        val flowAliases = buildMap<String, FlowId> {
            taskDefs.forEach { def ->
                put(def.id, def.flowId)
                put(def.name, def.flowId)
                put("task${def.id}", def.flowId)
            }
        }

        val flowsByTaskId = linkedMapOf<String, Flow>()
        taskDefs.forEach { def ->
            val actionNodes = mapTaskActions(def, flowAliases, issues)
            val flow = Flow(
                id = def.flowId,
                name = def.name,
                inputs = listOf(
                    FlowParameter("%par1", ValueType.ANY),
                    FlowParameter("%par2", ValueType.ANY),
                ),
                actions = actionNodes,
                source = SourceMetadata(id, def.id, "Task"),
            )
            flowsByTaskId[def.id] = flow
            trace += ImportTrace(
                "task[${def.id}]",
                flow.id.value,
                "IMPORTED",
                userText("import.trace.actions", actionNodes.size),
            )
        }

        val automations = mutableListOf<Automation>()
        val profiles = doc.getElementsByTagName("Profile")
        for (i in 0 until profiles.length) {
            val profile = profiles.item(i) as? Element ?: continue
            val profileId = profile.childText("id") ?: profile.getAttribute("sr").ifBlank { "profile-$i" }.removePrefix("prof")
            val name = profile.childText("nme") ?: userText("import.tasker.profile_name", i + 1)
            val contexts = profile.elementChildren().filter { it.tagName in setOf("Event", "State", "App", "Time", "Location", "Day") }
            val events = mutableListOf<FeatureRef>()
            val states = mutableListOf<FeatureRef>()
            contexts.forEachIndexed { ci, context ->
                val code = context.childText("code") ?: "unknown"
                val path = "profile[" + profileId + "].context[" + ci + "]"
                val isEvent = context.tagName == "Event"
                val raw = context.toCompactXml()
                val matter = taskerMatterFeature(context, if (isEvent) "event" else "condition", raw)
                val plugin = taskerPluginFeature(context, if (isEvent) "event" else "condition", raw)
                val native = matter ?: plugin ?: TaskerMappings.nativeContext(context, code, context.tagName, id, raw)
                if (native != null) {
                    if (isEvent) events += native else states += native
                    trace += ImportTrace(
                        path,
                        native.typeId,
                        when {
                            matter != null -> "MATTER"
                            plugin != null -> "PLUGIN"
                            else -> "MAPPED"
                        },
                        context.tagName + ":" + code,
                    )
                } else {
                    val fallback = if (isEvent) CompatFeatureIds.SOURCE_EVENT else CompatFeatureIds.SOURCE_STATE
                    val feature = sourceFeature(fallback, id, context.tagName + ":" + code, raw)
                    if (isEvent) events += feature else states += feature
                    val suggested = if (isEvent) TaskerFeatureSuggestions.event(code) else TaskerFeatureSuggestions.state(code)
                    issues += CompatibilityIssue(
                        ImportSeverity.WARNING,
                        path,
                        context.tagName + ":" + code,
                        userText("import.tasker.context_preserved"),
                        suggestedFeatureId = suggested,
                    )
                }
            }

            fun flowCall(childName: String): List<ActionNode> {
                val taskId = profile.childText(childName) ?: return emptyList()
                val flowId = flowAliases[taskId] ?: flowAliases["task$taskId"] ?: return emptyList()
                return listOf(ActionNode.CallFlow(NodeId(UUID.randomUUID().toString()), flowId))
            }

            val enter = flowCall("mid0")
            val exit = flowCall("mid1")
            val automation = Automation(
                AutomationId("import-tasker-$profileId"),
                name,
                enabled = true,
                activation = Activation(events = events, states = states),
                onEnter = if (states.isNotEmpty()) enter else emptyList(),
                onEvent = if (events.isNotEmpty()) enter else emptyList(),
                onExit = exit,
                source = SourceMetadata(id, profileId, "Profile"),
            )
            automations += automation
            trace += ImportTrace(
                "profile[$profileId]",
                automation.id.value,
                "IMPORTED",
                userText("import.trace.tasker_profile", contexts.size),
            )
        }
        ImportResult(id, true, ImportBundle(automations, flowsByTaskId.values.toList()), issues, trace)
    }.getOrElse { error ->
        ImportResult(id, false, issues = listOf(CompatibilityIssue(ImportSeverity.ERROR, input.fileName ?: "input", message = error.message ?: userText("import.tasker.failed"))))
    }

    private fun mapTaskActions(
        task: TaskDef,
        flowAliases: Map<String, FlowId>,
        issues: MutableList<CompatibilityIssue>,
    ): List<ActionNode> {
        val actions = task.element.children("Action")
        return mapActionRange(actions, 0, actions.size, task, flowAliases, issues)
    }

    private fun mapActionRange(
        actions: List<Element>,
        start: Int,
        end: Int,
        task: TaskDef,
        flowAliases: Map<String, FlowId>,
        issues: MutableList<CompatibilityIssue>,
    ): List<ActionNode> {
        val output = mutableListOf<ActionNode>()
        var index = start
        while (index < end) {
            val action = actions[index]
            val code = action.childText("code") ?: "unknown"
            val path = "task[" + task.id + "].action[" + index + "]"

            if (code == "37") {
                val parsed = parseTaskerIf(actions, index, end, task, flowAliases, issues)
                if (parsed != null) {
                    output += parsed.node
                    index = parsed.nextIndex
                    continue
                }
            }
            if (code == "39") {
                val parsed = parseTaskerFor(actions, index, end, task, flowAliases, issues)
                if (parsed != null) {
                    output += parsed.node
                    index = parsed.nextIndex
                    continue
                }
            }
            if (code == "35") {
                val conditionList = action.children("ConditionList").firstOrNull()
                val predicate = conditionList?.let(::taskerConditionList)
                if (predicate != null) {
                    output += ActionNode.WaitUntil(
                        id = NodeId(UUID.randomUUID().toString()),
                        condition = predicate,
                        pollIntervalMs = TaskerMappings.waitUntilPollIntervalMs(action),
                        unlimited = true,
                    )
                    index++
                    continue
                }
            }

            when (code) {
                "38", "40", "43" -> {
                    // Stray control marker: preserve rather than silently changing execution.
                    issues += CompatibilityIssue(
                        ImportSeverity.WARNING,
                        path,
                        "code:" + code,
                        userText("import.tasker.action_preserved"),
                    )
                    output += compatibilityAction(code, action.toCompactXml())
                }
                "126" -> {
                    output += ActionNode.Return(
                        NodeId(UUID.randomUUID().toString()),
                        ConfigValue.StringValue(TaskerMappings.returnValue(action).orEmpty()),
                    )
                }
                "137" -> {
                    val target = TaskerMappings.stopTaskTarget(action)
                    if (target.isNullOrBlank()) {
                        output += ActionNode.Return(NodeId(UUID.randomUUID().toString()), ConfigValue.NullValue)
                    } else {
                        output += ActionNode.Action(
                            NodeId(UUID.randomUUID().toString()),
                            sourceFeature(
                                "core.automation.cancel",
                                id,
                                "TaskerAction:" + code,
                                action.toCompactXml(),
                                extra = mapOf("target" to ConfigValue.StringValue(target)),
                            ),
                        )
                    }
                }
                else -> output += mapTaskerLeafAction(action, code, path, flowAliases, issues)
            }
            index++
        }
        return output
    }

    private data class ParsedIf(val node: ActionNode.If, val nextIndex: Int)

    private fun parseTaskerIf(
        actions: List<Element>,
        start: Int,
        end: Int,
        task: TaskDef,
        flowAliases: Map<String, FlowId>,
        issues: MutableList<CompatibilityIssue>,
    ): ParsedIf? {
        val conditionList = actions[start].children("ConditionList").firstOrNull() ?: return null
        val predicate = taskerConditionList(conditionList) ?: return null

        var depth = 0
        var elseIndex = -1
        var endIfIndex = -1
        var index = start + 1
        while (index < end) {
            when (actions[index].childText("code")) {
                "37" -> depth++
                "38" -> {
                    if (depth == 0) {
                        endIfIndex = index
                        break
                    }
                    depth--
                }
                "43" -> if (depth == 0 && elseIndex < 0) elseIndex = index
            }
            index++
        }
        if (endIfIndex < 0) return null

        val thenEnd = if (elseIndex >= 0) elseIndex else endIfIndex
        val thenActions = mapActionRange(actions, start + 1, thenEnd, task, flowAliases, issues)
        val elseActions = if (elseIndex >= 0) {
            mapActionRange(actions, elseIndex + 1, endIfIndex, task, flowAliases, issues)
        } else emptyList()

        return ParsedIf(
            ActionNode.If(
                NodeId(UUID.randomUUID().toString()),
                predicate,
                thenActions,
                elseActions,
            ),
            endIfIndex + 1,
        )
    }

    private data class ParsedFor(val node: ActionNode.ForEach, val nextIndex: Int)

    private fun parseTaskerFor(
        actions: List<Element>,
        start: Int,
        end: Int,
        task: TaskDef,
        flowAliases: Map<String, FlowId>,
        issues: MutableList<CompatibilityIssue>,
    ): ParsedFor? {
        val action = actions[start]
        if (TaskerMappings.forMode(action) != 0L) return null
        val variable = TaskerMappings.forVariable(action) ?: return null
        val rawItems = TaskerMappings.forItems(action) ?: return null

        var depth = 0
        var endFor = -1
        var index = start + 1
        while (index < end) {
            when (actions[index].childText("code")) {
                "39" -> depth++
                "40" -> {
                    if (depth == 0) {
                        endFor = index
                        break
                    }
                    depth--
                }
            }
            index++
        }
        if (endFor < 0) return null

        val body = mapActionRange(actions, start + 1, endFor, task, flowAliases, issues)
        val trimmed = rawItems.trim()
        val arrayMatch = Regex("""^(%[A-Za-z0-9_]+)\(\)$""").matchEntire(trimmed)
        if (arrayMatch != null) {
            return ParsedFor(
                ActionNode.ForEach(
                    id = NodeId(UUID.randomUUID().toString()),
                    variableName = variable,
                    actions = body,
                    sourceVariable = arrayMatch.groupValues[1],
                ),
                endFor + 1,
            )
        }

        val range = Regex("""^(-?\d+)\s*:\s*(-?\d+)$""").matchEntire(trimmed)
        val values = if (range != null) {
            val from = range.groupValues[1].toIntOrNull() ?: return null
            val to = range.groupValues[2].toIntOrNull() ?: return null
            val count = kotlin.math.abs(to.toLong() - from.toLong()) + 1L
            if (count > 10_000L) return null
            if (from <= to) (from..to).map { ConfigValue.NumberValue(it.toDouble()) }
            else (from downTo to).map { ConfigValue.NumberValue(it.toDouble()) }
        } else {
            if ('%' in trimmed) return null
            trimmed.split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map { ConfigValue.StringValue(it) }
        }
        if (values.isEmpty()) return null

        return ParsedFor(
            ActionNode.ForEach(
                id = NodeId(UUID.randomUUID().toString()),
                values = values,
                variableName = variable,
                actions = body,
            ),
            endFor + 1,
        )
    }

    private fun mapTaskerLeafAction(
        action: Element,
        code: String,
        path: String,
        flowAliases: Map<String, FlowId>,
        issues: MutableList<CompatibilityIssue>,
    ): ActionNode {
        val raw = action.toCompactXml()
        val matterFeature = taskerMatterFeature(action, "action", raw)
        val pluginFeature = taskerPluginFeature(action, "action", raw)
        val base: ActionNode = if (matterFeature != null) {
            ActionNode.Action(NodeId(UUID.randomUUID().toString()), matterFeature)
        } else if (pluginFeature != null) {
            ActionNode.Action(NodeId(UUID.randomUUID().toString()), pluginFeature)
        } else if (code == "130") {
            val targetName = TaskerMappings.performTaskTarget(action)
            val target = targetName?.let(flowAliases::get)
            if (target != null) {
                val input = buildMap<String, ConfigValue> {
                    TaskerMappings.performTaskParam1(action)?.let { put("%par1", ConfigValue.StringValue(it)) }
                    TaskerMappings.performTaskParam2(action)?.let { put("%par2", ConfigValue.StringValue(it)) }
                }
                ActionNode.CallFlow(
                    NodeId(UUID.randomUUID().toString()),
                    target,
                    input = input,
                    resultVariable = TaskerMappings.performTaskResultVariable(action),
                )
            } else {
                issues += CompatibilityIssue(
                    ImportSeverity.WARNING,
                    path,
                    "code:" + code,
                    userText("import.tasker.target_unresolved"),
                )
                compatibilityAction(code, raw)
            }
        } else {
            val native = TaskerMappings.nativeAction(action, code, id, raw)
            if (native != null) {
                ActionNode.Action(NodeId(UUID.randomUUID().toString()), native)
            } else {
                issues += CompatibilityIssue(
                    ImportSeverity.WARNING,
                    path,
                    "code:" + code,
                    userText("import.tasker.action_preserved"),
                    suggestedFeatureId = TaskerFeatureSuggestions.action(code),
                )
                compatibilityAction(code, raw)
            }
        }

        val list = action.children("ConditionList").firstOrNull() ?: return base
        val predicate = taskerConditionList(list) ?: return base
        return ActionNode.If(
            NodeId(UUID.randomUUID().toString()),
            predicate,
            listOf(base),
        )
    }

    private fun taskerConditionList(list: Element): PredicateNode? {
        val conditions = list.elementChildren().filter { it.tagName == "Condition" }
        if (conditions.isEmpty()) return null
        val nodes = conditions.map { condition ->
            val lhs = condition.childText("lhs").orEmpty()
            val op = condition.childText("op")?.toIntOrNull() ?: return null
            val rhs = condition.childText("rhs").orEmpty()
            PredicateNode.Condition(
                FeatureRef(
                    "tasker.condition.compare",
                    mapOf(
                        "lhs" to ConfigValue.StringValue(lhs),
                        "operator" to ConfigValue.NumberValue(op.toDouble()),
                        "rhs" to ConfigValue.StringValue(rhs),
                    ),
                )
            )
        }.toMutableList()

        if (nodes.size == 1) return nodes.first()
        val operators = (0 until nodes.lastIndex).map { index ->
            list.childText("bool" + index)?.trim().orEmpty().ifBlank { "And" }
        }.toMutableList()
        if (operators.size != nodes.size - 1) return null

        val precedence = listOf(
            setOf("And2"),
            setOf("Or2"),
            setOf("Xor2"),
            setOf("And"),
            setOf("Or"),
            setOf("Xor"),
        )
        precedence.forEach { level ->
            var i = 0
            while (i < operators.size) {
                if (operators[i] !in level) {
                    i++
                    continue
                }
                val left = nodes[i]
                val right = nodes[i + 1]
                val combined = when (operators[i]) {
                    "And", "And2" -> PredicateNode.All(listOf(left, right))
                    "Or", "Or2" -> PredicateNode.Any(listOf(left, right))
                    "Xor", "Xor2" -> PredicateNode.Any(
                        listOf(
                            PredicateNode.All(listOf(left, PredicateNode.None(listOf(right)))),
                            PredicateNode.All(listOf(PredicateNode.None(listOf(left)), right)),
                        )
                    )
                    else -> return null
                }
                nodes[i] = combined
                nodes.removeAt(i + 1)
                operators.removeAt(i)
            }
        }
        return nodes.singleOrNull()
    }

    private fun taskerMatterFeature(
        element: Element,
        kind: String,
        raw: String,
    ): FeatureRef? {
        val lower = raw.lowercase(Locale.ROOT)
        if (
            "matter_light" !in lower &&
            "matterlight" !in lower &&
            "mattdevi" !in lower
        ) return null

        val values = element.descendantsIncludingSelf().associate { node ->
            val key = buildString {
                append(node.tagName.lowercase(Locale.ROOT))
                listOf("name", "key", "n", "sr").forEach { attr ->
                    if (node.hasAttribute(attr)) {
                        append('|')
                        append(node.getAttribute(attr).lowercase(Locale.ROOT))
                    }
                }
            }
            key to node.textContent.orEmpty().trim()
        }

        fun named(vararg tokens: String): String? =
            values.entries.firstOrNull { (key, value) ->
                value.isNotBlank() && tokens.any { token -> key.contains(token) }
            }?.value

        val device = named("deviceid", "device_id", "matterdevice", "mattdevi", "device")
            ?: element.stringArg(0)
            ?: return null
        val stateRaw = named("set", "onoff", "state", "command")
            ?: element.stringArg(1).orEmpty()
        val state = when (stateRaw.trim().lowercase(Locale.ROOT)) {
            "on", "1", "true" -> "on"
            "off", "0", "false" -> "off"
            else -> "toggle"
        }
        val color = named("colour", "color", "rgb")
            ?: element.stringArg(2).orEmpty()
        val brightness = named("brightness", "bright", "level")
            ?.filter { it.isDigit() || it == '.' || it == '-' }
            ?.toDoubleOrNull()
            ?: element.stringArg(3)?.toDoubleOrNull()

        val target = when (kind) {
            "action" -> "android.matter.light"
            "condition" -> "android.condition.matter_light"
            else -> return null
        }
        return sourceFeature(
            target,
            id,
            "TaskerMatterLight",
            raw,
            extra = buildMap {
                put("backend", ConfigValue.StringValue("chip_tool"))
                put("deviceId", ConfigValue.StringValue(device))
                put("endpointId", ConfigValue.NumberValue(1.0))
                if (kind == "action") {
                    put("set", ConfigValue.StringValue(state))
                    if (color.isNotBlank()) put("color", ConfigValue.StringValue(color))
                    brightness?.coerceIn(0.0, 100.0)?.let {
                        put("brightness", ConfigValue.NumberValue(it))
                    }
                } else {
                    put("expected", ConfigValue.StringValue(if (state == "off") "off" else "on"))
                }
            },
        )
    }

    private fun Element.descendantsIncludingSelf(): List<Element> = buildList {
        fun walk(node: Element) {
            add(node)
            node.elementChildren().forEach(::walk)
        }
        walk(this@descendantsIncludingSelf)
    }

    private fun taskerPluginFeature(
        element: Element,
        kind: String,
        raw: String,
    ): FeatureRef? {
        val bundle = element.argElement(0)
            ?.takeIf { it.tagName == "Bundle" }
            ?: element.children("Bundle").firstOrNull { it.getAttribute("sr") == "arg0" }
            ?: return null
        val packageName = element.stringArg(1)?.trim().orEmpty()
        if (!TASKER_PACKAGE.matches(packageName)) return null
        val configActivity = element.stringArg(2)?.trim().orEmpty()
        val timeoutSeconds = element.intArg(3)?.coerceIn(1L, 120L) ?: 10L
        val bundleJson = bundle.taskerBundleJson()
        val target = when (kind) {
            "action" -> "android.plugin.locale.action"
            "condition" -> "android.plugin.locale.condition"
            "event" -> "android.event.plugin_locale"
            else -> return null
        }
        return sourceFeature(
            target,
            id,
            "TaskerPlugin:" + packageName,
            raw,
            extra = buildMap {
                put("package", ConfigValue.StringValue(packageName))
                put("bundleJson", ConfigValue.StringValue(bundleJson))
                put("timeoutMs", ConfigValue.NumberValue(timeoutSeconds * 1_000.0))
                if (configActivity.isNotBlank()) {
                    put("configActivity", ConfigValue.StringValue(configActivity))
                }
                if (kind == "action") put("ordered", ConfigValue.BooleanValue(true))
            },
        )
    }

    private fun Element.taskerBundleJson(): String {
        val vals = children("Vals").firstOrNull() ?: return "{}"
        val all = vals.elementChildren()
        val types = all.asSequence()
            .filter { it.tagName.endsWith("-type") }
            .associate { it.tagName.removeSuffix("-type") to it.textContent.orEmpty().trim() }
        return all.asSequence()
            .filterNot { it.tagName.endsWith("-type") }
            .joinToString(prefix = "{", postfix = "}", separator = ",") { child ->
                val key = child.tagName
                val rawValue = child.textContent.orEmpty()
                val type = types[key].orEmpty()
                jsonString(key) + ":" + taskerJsonValue(rawValue, type)
            }
    }

    private fun taskerJsonValue(value: String, type: String): String = when (type) {
        "java.lang.Boolean", "boolean" ->
            if (value.equals("true", true) || value == "1") "true" else "false"
        "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte",
        "int", "long", "short", "byte" -> value.trim().toLongOrNull()?.toString() ?: jsonString(value)
        "java.lang.Float", "java.lang.Double", "float", "double" ->
            value.trim().toDoubleOrNull()?.toString() ?: jsonString(value)
        else -> jsonString(value)
    }

    private fun jsonString(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    private fun Element.argElement(index: Int): Element? =
        elementChildren().firstOrNull { it.getAttribute("sr") == "arg" + index }

    private fun Element.stringArg(index: Int): String? {
        val arg = argElement(index) ?: return null
        return when {
            arg.hasAttribute("val") -> arg.getAttribute("val")
            else -> arg.textContent?.trim()
        }?.takeIf(String::isNotEmpty)
    }

    private fun Element.intArg(index: Int): Long? {
        val arg = argElement(index) ?: return null
        return arg.getAttribute("val").takeIf(String::isNotBlank)?.toLongOrNull()
            ?: arg.textContent?.trim()?.toLongOrNull()
    }

    private companion object {
        val TASKER_PACKAGE = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    }

    private fun compatibilityAction(code: String, raw: String): ActionNode.Action = ActionNode.Action(
        NodeId(UUID.randomUUID().toString()),
        sourceFeature(CompatFeatureIds.SOURCE_ACTION, id, "TaskerAction:$code", raw),
    )

    private data class TaskDef(
        val element: Element,
        val id: String,
        val name: String,
        val flowId: FlowId,
    )

    private fun Element.childText(name: String): String? = elementChildren().firstOrNull { it.tagName == name }?.textContent?.trim()?.takeIf { it.isNotEmpty() }
    private fun Element.children(name: String): List<Element> = elementChildren().filter { it.tagName == name }
    private fun Element.elementChildren(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
    private fun Element.toCompactXml(): String {
        val writer = StringWriter()
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        }.transform(DOMSource(this), StreamResult(writer))
        return writer.toString()
    }
}
