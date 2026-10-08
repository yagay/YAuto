package com.yagay.yauto.importer.shortx

import com.yagay.yauto.core.model.ActionNode
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.NodeId
import com.yagay.yauto.core.model.PredicateNode
import java.util.UUID

/**
 * Converts ShortX control-flow Any payloads into YAuto's native ActionNode tree.
 *
 * Conversion is deliberately lossless/conservative: unsupported async modes, loop limits or quit
 * policies return null so the importer preserves the original protobuf compatibility node.
 */
internal object ShortXStructuralMappings {
    fun convert(
        any: AnyStub,
        predicate: (AnyStub, String) -> PredicateNode,
        action: (AnyStub, String) -> ActionNode,
        path: String,
    ): ActionNode? {
        if (any.isJson) return null
        val type = shortName(any.typeUrl)
        val fields = runCatching { ProtoFields(any.value) }.getOrNull() ?: return null
        if (fields.varint(98) == 1L) return null
        return when (type) {
            "NoAction" -> featureAction("core.noop")
            "BreakActionExecute", "Brk" -> ActionNode.Break(nodeId())
            "StopAllActions" -> ActionNode.Return(nodeId(), ConfigValue.NullValue)
            "SetFunctionReturnValue" -> ActionNode.Return(
                nodeId(),
                ConfigValue.StringValue(fields.string(1).orEmpty()),
            )
            "ExecuteFunction" -> convertFlowCall(fields, "function")
            "FromDA" -> convertFlowCall(fields, "da")
            "IfThenElse" -> convertIf(fields, predicate, action, path)
            "ForEach" -> convertForEach(fields, action, path)
            "ForEachPkgSet" -> convertForEachPackageSet(fields, action, path)
            "SwitchCase" -> convertSwitchCases(fields, predicate, action, path)
            "WaitUtilConditionMatch" -> convertWaitUntil(fields, predicate, path)
            "WhileLoop" -> convertWhile(fields, predicate, action, path)
            else -> null
        }
    }


    private fun convertFlowCall(fields: ProtoFields, kind: String): ActionNode? {
        val sourceId = fields.string(1)?.trim().orEmpty()
        if (sourceId.isBlank()) return null
        val input = linkedMapOf<String, ConfigValue>()
        fields.allBytes(2).forEach { bytes ->
            val parameter = ProtoFields(bytes)
            val name = parameter.string(1)?.trim().orEmpty()
            if (name.isBlank()) return@forEach
            input[name] = ConfigValue.StringValue(parameter.string(2).orEmpty())
        }
        return ActionNode.CallFlow(
            id = nodeId(),
            flowId = shortXFlowId(kind, sourceId),
            input = input,
        )
    }

    private fun convertIf(
        fields: ProtoFields,
        predicate: (AnyStub, String) -> PredicateNode,
        action: (AnyStub, String) -> ActionNode,
        path: String,
    ): ActionNode? {
        val asyncThen = fields.varint(6) ?: 0L
        val asyncElse = fields.varint(7) ?: 0L
        if (asyncThen != 0L || asyncElse != 0L) return null
        val conditions = fields.allBytes(1).mapIndexed { index, bytes ->
            predicate(decodeAny(bytes), path + ".if.condition[" + index + "]")
        }
        if (conditions.isEmpty()) return null
        val condition = combine(conditions, fields.varint(2) ?: 0L) ?: return null
        val thenActions = fields.allBytes(4).mapIndexed { index, bytes ->
            action(decodeAny(bytes), path + ".if.action[" + index + "]")
        }
        val elseActions = fields.allBytes(5).mapIndexed { index, bytes ->
            action(decodeAny(bytes), path + ".else.action[" + index + "]")
        }
        return ActionNode.If(nodeId(), condition, thenActions, elseActions)
    }

    private fun convertForEach(
        fields: ProtoFields,
        action: (AnyStub, String) -> ActionNode,
        path: String,
    ): ActionNode? {
        if ((fields.varint(4) ?: 0L) != 0L) return null
        val raw = fields.string(1) ?: return null
        // Runtime-variable interpolation would make a static YAuto ForEach lossy, so preserve it.
        if ('%' in raw || '{' in raw || raw.contains("\${")) return null
        val delimiters = fields.allStrings(2).filter(String::isNotEmpty)
        val values = when {
            delimiters.isEmpty() -> raw.lineSequence().toList()
            else -> {
                val regex = delimiters.joinToString("|") { Regex.escape(it) }.toRegex()
                raw.split(regex)
            }
        }.map(String::trim).filter(String::isNotEmpty)
        if (values.isEmpty()) return null
        val children = fields.allBytes(3).mapIndexed { index, bytes ->
            action(decodeAny(bytes), path + ".foreach.action[" + index + "]")
        }
        return ActionNode.ForEach(
            id = nodeId(),
            values = values.map { ConfigValue.StringValue(it) },
            variableName = "shortxItem",
            actions = children,
        )
    }


    private fun convertForEachPackageSet(
        fields: ProtoFields,
        action: (AnyStub, String) -> ActionNode,
        path: String,
    ): ActionNode? {
        val source = fields.string(1)?.trim().orEmpty()
        if (source.isBlank()) return null
        val children = fields.allBytes(2).mapIndexed { index, bytes ->
            action(decodeAny(bytes), path + ".foreach_pkg.action[" + index + "]")
        }
        return ActionNode.ForEach(
            id = nodeId(),
            variableName = "shortxPackage",
            actions = children,
            sourceVariable = source,
        )
    }

    private fun convertSwitchCases(
        fields: ProtoFields,
        predicate: (AnyStub, String) -> PredicateNode,
        action: (AnyStub, String) -> ActionNode,
        path: String,
    ): ActionNode? {
        val cases = fields.allBytes(1).map(::ProtoFields)
        if (cases.isEmpty()) return null
        // A break-after-case ShortX switch is equivalent to a nested YAuto if/else chain.
        if (cases.any { (it.varint(9) ?: 0L) != 0L || it.varint(8) == 1L || it.varint(7) != 1L }) return null
        val defaultActions = fields.bytes(2)?.let(::ProtoFields)?.allBytes(4).orEmpty()
            .mapIndexed { index, bytes -> action(decodeAny(bytes), path + ".switch.default[" + index + "]") }

        var elseBranch: List<ActionNode> = defaultActions
        for (index in cases.indices.reversed()) {
            val item = cases[index]
            val conditions = item.allBytes(1).mapIndexed { condIndex, bytes ->
                predicate(decodeAny(bytes), path + ".switch.case[" + index + "].condition[" + condIndex + "]")
            }
            if (conditions.isEmpty()) return null
            val combined = combine(conditions, item.varint(2) ?: 0L) ?: return null
            val actions = item.allBytes(4).mapIndexed { actionIndex, bytes ->
                action(decodeAny(bytes), path + ".switch.case[" + index + "].action[" + actionIndex + "]")
            }
            elseBranch = listOf(ActionNode.If(nodeId(), combined, actions, elseBranch))
        }
        return elseBranch.singleOrNull()
    }

    private fun convertWaitUntil(
        fields: ProtoFields,
        predicate: (AnyStub, String) -> PredicateNode,
        path: String,
    ): ActionNode? {
        if (fields.varint(9) == 1L || fields.allBytes(5).isNotEmpty() || fields.allBytes(6).isNotEmpty()) return null
        val conditions = fields.allBytes(1).mapIndexed { index, bytes ->
            predicate(decodeAny(bytes), path + ".wait.condition[" + index + "]")
        }
        if (conditions.isEmpty()) return null
        val condition = combine(conditions, fields.varint(2) ?: 0L) ?: return null
        val timeout = fields.string(10)?.toLongOrNull()
            ?: fields.varint(4)
            ?: 60_000L
        return ActionNode.WaitUntil(
            id = nodeId(),
            condition = condition,
            timeoutMs = timeout.coerceIn(1L, 86_400_000L),
            pollIntervalMs = 250L,
        )
    }

    private fun convertWhile(
        fields: ProtoFields,
        predicate: (AnyStub, String) -> PredicateNode,
        action: (AnyStub, String) -> ActionNode,
        path: String,
    ): ActionNode? {
        val delay = fields.varint(5) ?: 0L
        val repeatTimes = fields.varint(6) ?: 0L
        val async = fields.varint(7) ?: 0L
        if (delay != 0L || repeatTimes != 0L || async != 0L) return null
        val conditions = fields.allBytes(1).mapIndexed { index, bytes ->
            predicate(decodeAny(bytes), path + ".while.condition[" + index + "]")
        }
        if (conditions.isEmpty()) return null
        val condition = combine(conditions, fields.varint(3) ?: 0L) ?: return null
        val children = fields.allBytes(2).mapIndexed { index, bytes ->
            action(decodeAny(bytes), path + ".while.action[" + index + "]")
        }
        return ActionNode.While(nodeId(), condition, children)
    }

    private fun combine(nodes: List<PredicateNode>, operator: Long): PredicateNode? = when (operator) {
        0L -> if (nodes.size == 1) nodes.first() else PredicateNode.All(nodes)
        1L -> if (nodes.size == 1) nodes.first() else PredicateNode.Any(nodes)
        else -> null
    }

    private fun decodeAny(bytes: ByteArray): AnyStub {
        val fields = ProtoFields(bytes)
        val type = fields.string(1).orEmpty()
        val value = fields.bytes(2) ?: bytes
        return AnyStub(type, value)
    }

    private fun featureAction(typeId: String) =
        ActionNode.Action(nodeId(), FeatureRef(typeId), enabled = true)

    private fun nodeId() = NodeId(UUID.randomUUID().toString())

    private fun shortName(typeUrl: String): String = typeUrl
        .substringAfterLast('/')
        .substringAfterLast('.')
        .substringAfterLast('$')
}
