package com.yagay.yauto.importer.tasker

import com.yagay.yauto.core.importer.*
import com.yagay.yauto.core.model.*
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

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
            val name = task.childText("nme") ?: "Imported Task ${i + 1}"
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
            trace += ImportTrace("task[${def.id}]", flow.id.value, "IMPORTED", "${actionNodes.size} actions")
        }

        val automations = mutableListOf<Automation>()
        val profiles = doc.getElementsByTagName("Profile")
        for (i in 0 until profiles.length) {
            val profile = profiles.item(i) as? Element ?: continue
            val profileId = profile.childText("id") ?: profile.getAttribute("sr").ifBlank { "profile-$i" }.removePrefix("prof")
            val name = profile.childText("nme") ?: "Imported Profile ${i + 1}"
            val contexts = profile.elementChildren().filter { it.tagName in setOf("Event", "State", "App", "Time", "Location", "Day") }
            val events = mutableListOf<FeatureRef>()
            val states = mutableListOf<FeatureRef>()
            contexts.forEachIndexed { ci, context ->
                val code = context.childText("code") ?: "unknown"
                val path = "profile[$profileId].context[$ci]"
                val isEvent = context.tagName == "Event"
                val fallback = if (isEvent) CompatFeatureIds.SOURCE_EVENT else CompatFeatureIds.SOURCE_STATE
                val feature = sourceFeature(fallback, id, "${context.tagName}:$code", context.toCompactXml())
                if (isEvent) events += feature else states += feature
                issues += CompatibilityIssue(ImportSeverity.WARNING, path, "${context.tagName}:$code", "Tasker context preserved; native mapping can be added independently")
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
            trace += ImportTrace("profile[$profileId]", automation.id.value, "IMPORTED", "${contexts.size} contexts")
        }
        ImportResult(id, true, ImportBundle(automations, flowsByTaskId.values.toList()), issues, trace)
    }.getOrElse { error ->
        ImportResult(id, false, issues = listOf(CompatibilityIssue(ImportSeverity.ERROR, input.fileName ?: "input", message = error.message ?: "Tasker import failed")))
    }

    private fun mapTaskActions(
        task: TaskDef,
        flowAliases: Map<String, FlowId>,
        issues: MutableList<CompatibilityIssue>,
    ): List<ActionNode> = task.element.children("Action").mapIndexed { index, action ->
        val code = action.childText("code") ?: "unknown"
        val path = "task[${task.id}].action[$index]"
        val raw = action.toCompactXml()

        if (code == "130") {
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
                issues += CompatibilityIssue(ImportSeverity.WARNING, path, "code:$code", "Perform Task target could not be resolved; source action preserved")
                compatibilityAction(code, raw)
            }
        } else {
            val native = TaskerMappings.nativeAction(action, code, id, raw)
            if (native != null) {
                ActionNode.Action(NodeId(UUID.randomUUID().toString()), native)
            } else {
                issues += CompatibilityIssue(ImportSeverity.WARNING, path, "code:$code", "Tasker action preserved; native mapping can be added independently")
                compatibilityAction(code, raw)
            }
        }
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
    private fun Element.toCompactXml(): String = buildString {
        append('<').append(tagName)
        if (hasAttribute("sr")) append(" sr=\"").append(getAttribute("sr")).append("\"")
        append('>')
        elementChildren().take(30).forEach { child ->
            append('<').append(child.tagName)
            if (child.hasAttribute("sr")) append(" sr=\"").append(child.getAttribute("sr")).append("\"")
            if (child.hasAttribute("val")) append(" val=\"").append(child.getAttribute("val")).append("\"")
            append('>').append(child.textContent.trim().take(512)).append("</").append(child.tagName).append('>')
        }
        append("</").append(tagName).append('>')
    }.take(32_000)
}
