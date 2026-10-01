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
        val flowsByTaskId = linkedMapOf<String, Flow>()
        val taskNodes = doc.getElementsByTagName("Task")
        for (i in 0 until taskNodes.length) {
            val task = taskNodes.item(i) as? Element ?: continue
            val taskId = task.childText("id") ?: task.getAttribute("sr").ifBlank { "task-$i" }
            val name = task.childText("nme") ?: "Imported Task ${i + 1}"
            val actionNodes = task.children("Action").mapIndexed { ai, action ->
                val code = action.childText("code") ?: "unknown"
                issues += CompatibilityIssue(ImportSeverity.WARNING, "task[$taskId].action[$ai]", "code:$code", "Tasker action preserved; native mapping can be added independently")
                ActionNode.Action(
                    NodeId(UUID.randomUUID().toString()),
                    sourceFeature(CompatFeatureIds.SOURCE_ACTION, id, "TaskerAction:$code", action.toCompactXml()),
                )
            }
            val flow = Flow(FlowId("import-tasker-$taskId"), name, actions = actionNodes, source = SourceMetadata(id, taskId, "Task"))
            flowsByTaskId[taskId] = flow
            trace += ImportTrace("task[$taskId]", flow.id.value, "IMPORTED", "${actionNodes.size} actions")
        }

        val automations = mutableListOf<Automation>()
        val profiles = doc.getElementsByTagName("Profile")
        for (i in 0 until profiles.length) {
            val profile = profiles.item(i) as? Element ?: continue
            val profileId = profile.childText("id") ?: profile.getAttribute("sr").ifBlank { "profile-$i" }
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
                val flow = flowsByTaskId[taskId] ?: return emptyList()
                return listOf(ActionNode.CallFlow(NodeId(UUID.randomUUID().toString()), flow.id))
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

    private fun Element.childText(name: String): String? = elementChildren().firstOrNull { it.tagName == name }?.textContent?.trim()?.takeIf { it.isNotEmpty() }
    private fun Element.children(name: String): List<Element> = elementChildren().filter { it.tagName == name }
    private fun Element.elementChildren(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
    private fun Element.toCompactXml(): String = buildString {
        append('<').append(tagName)
        if (hasAttribute("sr")) append(" sr=\"").append(getAttribute("sr")).append("\"")
        append('>')
        elementChildren().take(20).forEach { child -> append('<').append(child.tagName).append('>').append(child.textContent.trim().take(512)).append("</").append(child.tagName).append('>') }
        append("</").append(tagName).append('>')
    }.take(32_000)
}
