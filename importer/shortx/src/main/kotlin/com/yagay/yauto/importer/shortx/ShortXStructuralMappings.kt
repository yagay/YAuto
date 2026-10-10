package com.yagay.yauto.importer.shortx

import com.yagay.yauto.core.model.ActionNode
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.NodeId
import com.yagay.yauto.core.model.PredicateNode
import java.util.UUID
import kotlinx.serialization.json.*

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
        if (any.isJson) return convertJson(any, predicate, action, path)
        val type = shortName(any.typeUrl)
        val fields = runCatching { ProtoFields(any.value) }.getOrNull() ?: return null
        // Structured ActionNode variants cannot store ShortX disabled state, error policy,
        // notes or other source-only action metadata; preserve those Any payloads verbatim.
        if ((96..99).any { field ->
                fields.has(field) && when (field) {
                    97, 98 -> fields.varint(field) != 0L
                    else -> true
                }
            }) return null
        return when (type) {
            // NoAction stays in ShortXMappings, which also checks its business fields.
            "BreakActionExecute", "Brk" ->
                if (fields.onlyStructuralFields()) ActionNode.Break(nodeId()) else null
            "StopAllActions" ->
                if (fields.onlyStructuralFields()) ActionNode.Return(nodeId(), ConfigValue.NullValue) else null
            "SetFunctionReturnValue" ->
                if (!fields.onlyStructuralFields(1)) null else fields.string(1)?.let {
                    ActionNode.Return(nodeId(), ConfigValue.StringValue(it))
                }
            "ExecuteFunction" -> if (fields.onlyStructuralFields(1, 2)) convertFlowCall(fields, "function") else null
            "FromDA" -> if (fields.onlyStructuralFields(1, 2)) convertFlowCall(fields, "da") else null
            "IfThenElse" -> if (fields.onlyStructuralFields(1, 2, 4, 5, 6, 7)) convertIf(fields, predicate, action, path) else null
            "ForEach" -> if (fields.onlyStructuralFields(1, 2, 3, 4)) convertForEach(fields, action, path) else null
            "ForEachPkgSet" -> if (fields.onlyStructuralFields(1, 2)) convertForEachPackageSet(fields, action, path) else null
            "SwitchCase" -> if (fields.onlyStructuralFields(1, 2)) convertSwitchCases(fields, predicate, action, path) else null
            "WaitUtilConditionMatch" -> if (fields.onlyStructuralFields(1, 2, 4, 5, 6, 9, 10)) convertWaitUntil(fields, predicate, path) else null
            "WhileLoop" -> if (fields.onlyStructuralFields(1, 2, 3, 5, 6, 7)) convertWhile(fields, predicate, action, path) else null
            else -> null
        }
    }



    /**
     * JSON ShortX export uses the same documented control-flow action types as protobuf.
     * Convert only modes whose effects YAuto can represent. In particular, ActionNode's
     * structural variants have no metadata field, so do not discard source IDs, annotations,
     * non-default failure policy, async execution, MVEL payloads or unknown JSON properties.
     */
    private fun convertJson(
        any: AnyStub,
        predicate: (AnyStub, String) -> PredicateNode,
        action: (AnyStub, String) -> ActionNode,
        path: String,
    ): ActionNode? {
        val obj = runCatching {
            Json.parseToJsonElement(any.value.toString(Charsets.UTF_8)) as? JsonObject
        }.getOrNull() ?: return null
        return when (shortName(any.typeUrl)) {
            "StopAllActions" -> if (safeJsonFields(obj)) ActionNode.Return(nodeId()) else null
            "BreakActionExecute", "Brk" -> {
                if (!safeJsonFields(obj, "scope")) null
                else if (obj["scope"] == null || jsonEnumNumber(obj["scope"],
                        "BreakActionExecuteScope_Current", "Current") == 0L) ActionNode.Break(nodeId())
                else null
            }
            "SetFunctionReturnValue" -> {
                if (!safeJsonFields(obj, "value")) null
                else jsonString(obj["value"])?.let { ActionNode.Return(nodeId(), ConfigValue.StringValue(it)) }
            }
            "ExecuteFunction", "FromDA" -> {
                val idField = if (shortName(any.typeUrl) == "FromDA") "daId" else "functionId"
                if (!safeJsonFields(obj, idField, "funcParameterInputs")) null
                else jsonFlowCall(obj, idField, if (idField == "daId") "da" else "function")
            }
            "IfThenElse" -> {
                if (!safeJsonFields(obj, "_if", "_ifCondOp", "_ifCondOpPayload", "_ifActions",
                        "_elseActions", "_ifActionAsyncMode", "_elseActionAsyncMode") ||
                    !jsonEmptyOperatorPayload(obj["_ifCondOpPayload"]) ||
                    !jsonSync(obj["_ifActionAsyncMode"]) || !jsonSync(obj["_elseActionAsyncMode"])) null
                else {
                    val conditions = jsonActionList(obj, "_if") ?: return null
                    if (conditions.isEmpty()) return null
                    val conditionsNative = conditions.mapIndexed { i, child ->
                        predicate(child, path + ".if.condition[" + i + "]")
                    }
                    val op = jsonConditionOperator(obj["_ifCondOp"]) ?: return null
                    val cond = combine(conditionsNative, op) ?: return null
                    val thenList = jsonActionList(obj, "_ifActions") ?: return null
                    val elseList = jsonActionList(obj, "_elseActions") ?: return null
                    ActionNode.If(nodeId(), cond,
                        thenList.mapIndexed { i, child -> action(child, path + ".if.action[" + i + "]") },
                        elseList.mapIndexed { i, child -> action(child, path + ".else.action[" + i + "]") })
                }
            }
            "WhileLoop" -> {
                if (!safeJsonFields(obj, "conditions", "actions", "condOp", "condOpPayload",
                        "delay", "repeatTimes", "actionAsyncMode") ||
                    !jsonEmptyOperatorPayload(obj["condOpPayload"]) ||
                    jsonNonnegativeZero(obj["delay"]) != true ||
                    jsonNonnegativeZero(obj["repeatTimes"]) != true ||
                    !jsonSync(obj["actionAsyncMode"])) null
                else {
                    val conditions = jsonActionList(obj, "conditions") ?: return null
                    if (conditions.isEmpty()) return null
                    val cond = combine(conditions.mapIndexed { i, child ->
                        predicate(child, path + ".while.condition[" + i + "]")
                    }, jsonConditionOperator(obj["condOp"]) ?: return null) ?: return null
                    val children = jsonActionList(obj, "actions") ?: return null
                    ActionNode.While(nodeId(), cond,
                        children.mapIndexed { i, child -> action(child, path + ".while.action[" + i + "]") })
                }
            }
            else -> null
        }
    }

    private fun jsonFlowCall(obj: JsonObject, idField: String, kind: String): ActionNode? {
        val sourceId = jsonString(obj[idField])?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val params = obj["funcParameterInputs"] ?: JsonArray(emptyList())
        val array = params as? JsonArray ?: return null
        if (array.size > 100) return null
        val input = linkedMapOf<String, ConfigValue>()
        array.forEach { element ->
            val param = element as? JsonObject ?: return null
            if (param.keys.any { it !in setOf("name", "value") }) return null
            val name = jsonString(param["name"])?.takeIf(String::isNotBlank) ?: return null
            val value = jsonString(param["value"]) ?: return null
            if (name in input) return null
            input[name] = ConfigValue.StringValue(value)
        }
        return ActionNode.CallFlow(nodeId(), shortXFlowId(kind, sourceId), input)
    }

    private fun safeJsonFields(obj: JsonObject, vararg business: String): Boolean {
        val allowed = setOf("@type", "typeUrl", "type_url", "type", "className",
            "id", "note", "isDisabled", "actionOnError") + business
        if (obj.keys.any { it !in allowed }) return false
        if (obj["id"] != null && !jsonString(obj["id"]).isNullOrEmpty()) return false
        if (obj["note"] != null && !jsonString(obj["note"]).isNullOrEmpty()) return false
        if (obj["isDisabled"] != null && (obj["isDisabled"] as? JsonPrimitive)?.booleanOrNull != false) return false
        if (obj["actionOnError"] != null &&
            jsonEnumNumber(obj["actionOnError"], "Continue", "ActionOnError_Continue") != 0L) return false
        return true
    }

    private fun jsonString(value: JsonElement?): String? =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun jsonEnumNumber(value: JsonElement?, vararg zeroNames: String): Long? {
        if (value == null) return 0L
        val raw = (value as? JsonPrimitive)?.content ?: return null
        return raw.toLongOrNull() ?: if (zeroNames.any { raw.equals(it, ignoreCase = true) }) 0L else null
    }

    private fun jsonConditionOperator(value: JsonElement?): Long? {
        if (value == null) return 0L
        val raw = (value as? JsonPrimitive)?.content ?: return null
        return when (raw.substringAfterLast('_').uppercase()) {
            "ALL", "0" -> 0L
            "ANY", "1" -> 1L
            "NONE", "2" -> 2L
            else -> null // MVEL requires a different evaluator and source context.
        }
    }

    private fun jsonSync(value: JsonElement?): Boolean = value == null ||
        jsonEnumNumber(value, "Sync", "ActionAsyncMode_Sync") == 0L

    private fun jsonNonnegativeZero(value: JsonElement?): Boolean =
        jsonEnumNumber(value) == 0L

    private fun jsonEmptyOperatorPayload(value: JsonElement?): Boolean {
        if (value == null) return true
        val payload = value as? JsonObject ?: return false
        return payload.isEmpty() || (payload.keys == setOf("expression") &&
            jsonString(payload["expression"]) == "")
    }

    private fun jsonActionList(obj: JsonObject, key: String): List<AnyStub>? {
        val array = obj[key] ?: return emptyList()
        val elements = array as? JsonArray ?: return null
        if (elements.size > 1000) return null
        return elements.map { item ->
            val anyObj = item as? JsonObject ?: return null
            val type = sequenceOf("@type", "typeUrl", "type_url", "type", "className")
                .mapNotNull { jsonString(anyObj[it]) }.firstOrNull()
                ?.takeIf(String::isNotBlank) ?: return null
            AnyStub(type, anyObj.toString().toByteArray(Charsets.UTF_8), isJson = true)
        }
    }

    private fun convertFlowCall(fields: ProtoFields, kind: String): ActionNode? {
        val sourceId = fields.string(1)?.trim().orEmpty()
        if (sourceId.isBlank()) return null
        val input = linkedMapOf<String, ConfigValue>()
        fields.allBytes(2).forEach { bytes ->
            val parameter = ProtoFields(bytes)
            val name = parameter.string(1)?.trim().orEmpty()
            if (!parameter.onlyStructuralFields(1, 2) || name.isBlank() || name in input) return null
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
        if (cases.any {
                !it.onlyStructuralFields(1, 2, 4, 7, 8, 9) ||
                    (it.varint(9) ?: 0L) != 0L || it.varint(8) == 1L || it.varint(7) != 1L
            }) return null
        if (fields.bytes(2)?.let { !ProtoFields(it).onlyStructuralFields(4) } == true) return null
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
        val sourceTimeout = fields.string(10)
        val timeout = if (sourceTimeout != null) {
            sourceTimeout.toLongOrNull() ?: return null
        } else fields.varint(4) ?: 60_000L
        // Never silently change the imported source timeout through clamping.
        if (timeout !in 1L..86_400_000L) return null
        return ActionNode.WaitUntil(
            id = nodeId(),
            condition = condition,
            timeoutMs = timeout,
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
        2L -> PredicateNode.None(nodes) // ShortX ConditionOperator.NONE: none of the conditions may match.
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
