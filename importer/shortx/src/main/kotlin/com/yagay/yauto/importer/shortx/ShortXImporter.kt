package com.yagay.yauto.importer.shortx

import com.yagay.yauto.core.importer.*
import com.yagay.yauto.core.model.*
import kotlinx.serialization.json.*
import java.util.Base64
import java.util.Locale
import java.util.UUID

class ShortXImporter : AutomationImporter {
    override val id = "shortx"
    override val displayName = "ShortX"
    private val json = Json { ignoreUnknownKeys = true }

    override fun confidence(input: ImportInput): Int {
        val text = input.utf8OrNull()?.trimStart().orEmpty()
        if (text.startsWith("{") && listOf("facts", "conditions", "actions", "ruleList").count { it in text } >= 2) return 90
        val name = input.fileName.orEmpty().lowercase(Locale.ROOT)
        if (
            "shortx" in name ||
            name.endsWith(".rule") ||
            name.endsWith(".pb") ||
            name.endsWith(".proto") ||
            name.endsWith(".function") ||
            name.endsWith(".da")
        ) return 60
        return if (input.bytes.isNotEmpty() && input.bytes.size < 20_000_000) 5 else 0
    }

    override fun import(input: ImportInput): ImportResult {
        val text = input.utf8OrNull()?.trimStart()
        if (text != null && (text.startsWith("{") || text.startsWith("["))) return importJson(input, text)
        return importProto(input)
    }

    private fun importJson(input: ImportInput, text: String): ImportResult = runCatching {
        val root = json.parseToJsonElement(text)
        val ruleObjects = when (root) {
            is JsonArray -> root.mapNotNull { it as? JsonObject }
            is JsonObject -> listOfNotNull(root["rules"] as? JsonArray, root["ruleList"] as? JsonArray)
                .firstOrNull()
                ?.mapNotNull { it as? JsonObject }
                ?: listOf(root)
            else -> emptyList()
        }
        val rules = ruleObjects.mapIndexed { index, obj ->
            RuleStub(
                id = obj.str("id") ?: "rule-" + index,
                title = obj.str("title") ?: userText("import.shortx.rule_name", index + 1),
                description = obj.str("description"),
                enabled = obj.bool("isEnabled") ?: true,
                facts = (obj["facts"] as? JsonArray)?.mapIndexed { n, value -> jsonAny(value, "json.fact." + n) }.orEmpty(),
                conditions = (obj["conditions"] as? JsonArray)?.mapIndexed { n, value -> jsonAny(value, "json.condition." + n) }.orEmpty(),
                actions = (obj["actions"] as? JsonArray)?.mapIndexed { n, value -> jsonAny(value, "json.action." + n) }.orEmpty(),
            )
        }
        fromContent(ShortXContent(rules = rules))
    }.getOrElse { failure(input, it) }

    private fun jsonAny(element: JsonElement, fallbackType: String): AnyStub {
        val obj = element as? JsonObject
        val type = obj?.let {
            sequenceOf("typeUrl", "type_url", "@type", "type", "className")
                .mapNotNull { key -> (it[key] as? JsonPrimitive)?.contentOrNull }
                .firstOrNull()
        } ?: fallbackType
        return AnyStub(type, element.toString().encodeToByteArray(), isJson = true)
    }

    private fun importProto(input: ImportInput): ImportResult = runCatching {
        val content = ShortXProtoReader.read(input.bytes)
        if (content.rules.isEmpty() && content.functions.isEmpty() && content.directActions.isEmpty()) {
            error(userText("import.shortx.structure_unrecognized"))
        }
        fromContent(content)
    }.getOrElse { failure(input, it) }

    private fun fromContent(content: ShortXContent): ImportResult {
        val issues = mutableListOf<CompatibilityIssue>()
        val trace = mutableListOf<ImportTrace>()

        fun preserve(any: AnyStub, fallback: String, path: String, suggested: String? = null): FeatureRef {
            issues += CompatibilityIssue(
                ImportSeverity.WARNING,
                path,
                any.typeUrl,
                userText("import.shortx.payload_preserved"),
                suggestedFeatureId = suggested,
            )
            return sourceFeature(
                fallback,
                id,
                any.typeUrl.ifBlank { "UnknownAny" },
                if (any.isJson) any.value.toString(Charsets.UTF_8)
                else Base64.getEncoder().encodeToString(any.value),
            )
        }

        fun mapPredicate(any: AnyStub, path: String): PredicateNode {
            if (!ShortXMappings.conditionEnabled(any)) return PredicateNode.Literal(true)
            val shortType = shortName(any.typeUrl)
            if (shortType == "TRUE" || shortType == "True") {
                return PredicateNode.Literal(!ShortXMappings.conditionInverted(any))
            }
            if (shortType == "FALSE" || shortType == "False") {
                return PredicateNode.Literal(ShortXMappings.conditionInverted(any))
            }
            val feature = ShortXMappings.nativeCondition(any, id)
            if (feature != null) {
                trace += ImportTrace(path, feature.typeId, "MAPPED", any.typeUrl)
                val predicate = PredicateNode.Condition(feature)
                return if (ShortXMappings.conditionInverted(any)) PredicateNode.None(listOf(predicate)) else predicate
            }
            val preserved = PredicateNode.Condition(
                preserve(
                    any,
                    CompatFeatureIds.SOURCE_CONDITION,
                    path,
                    ShortXMappings.suggestedConditionFeature(any.typeUrl),
                )
            )
            return if (ShortXMappings.conditionInverted(any)) PredicateNode.None(listOf(preserved)) else preserved
        }

        lateinit var mapAction: (AnyStub, String) -> ActionNode
        mapAction = { any, path ->
            val structural = ShortXStructuralMappings.convert(any, ::mapPredicate, mapAction, path)
            if (structural != null) {
                trace += ImportTrace(path, structural::class.simpleName ?: "ActionNode", "STRUCTURAL", any.typeUrl)
                structural
            } else {
                val feature = ShortXMappings.nativeAction(any, id)
                if (feature != null) {
                    trace += ImportTrace(path, feature.typeId, "MAPPED", any.typeUrl)
                    ActionNode.Action(
                        NodeId(UUID.randomUUID().toString()),
                        feature,
                        enabled = ShortXMappings.enabled(any),
                        comment = ShortXMappings.note(any),
                        failurePolicy = if (ShortXMappings.actionBreaksOnError(any)) ActionFailurePolicy.STOP else ActionFailurePolicy.CONTINUE,
                    )
                } else {
                    ActionNode.Action(
                        NodeId(UUID.randomUUID().toString()),
                        preserve(
                            any,
                            CompatFeatureIds.SOURCE_ACTION,
                            path,
                            ShortXMappings.suggestedActionFeature(any.typeUrl),
                        ),
                        // Compatibility nodes must preserve execution metadata as well as payload.
                        // Otherwise a disabled source action can silently become enabled on import.
                        enabled = ShortXMappings.enabled(any),
                        comment = ShortXMappings.note(any),
                        failurePolicy = if (ShortXMappings.actionBreaksOnError(any)) ActionFailurePolicy.STOP else ActionFailurePolicy.CONTINUE,
                    )
                }
            }
        }

        val flows = buildList {
            content.functions.forEachIndexed { index, function ->
                val actions = function.actions.mapIndexed { actionIndex, any ->
                    mapAction(any, "function[" + index + "].action[" + actionIndex + "]")
                }
                add(
                    Flow(
                        id = shortXFlowId("function", function.id),
                        name = function.name.ifBlank { "ShortX Function " + function.id },
                        inputs = function.parameters.map(::toFlowParameter),
                        outputs = if (function.returnType.isBlank()) emptyList()
                        else listOf(FlowParameter("result", ValueType.ANY)),
                        actions = actions,
                        description = function.comments,
                        source = SourceMetadata(id, function.id, "Function"),
                    )
                )
                trace += ImportTrace(
                    "function[" + index + "]",
                    shortXFlowId("function", function.id).value,
                    "IMPORTED",
                    userText("import.trace.shortx_function", function.name),
                )
            }
            content.directActions.forEachIndexed { index, direct ->
                val actions = direct.actions.mapIndexed { actionIndex, any ->
                    mapAction(any, "directAction[" + index + "].action[" + actionIndex + "]")
                }
                add(
                    Flow(
                        id = shortXFlowId("da", direct.id),
                        name = direct.title.ifBlank { "ShortX Direct Action " + direct.id },
                        inputs = direct.parameters.map(::toFlowParameter),
                        actions = actions,
                        description = direct.description,
                        source = SourceMetadata(id, direct.id, "DirectAction"),
                    )
                )
                trace += ImportTrace(
                    "directAction[" + index + "]",
                    shortXFlowId("da", direct.id).value,
                    "IMPORTED",
                    userText("import.trace.shortx_direct_action", direct.title),
                )
            }
        }

        val chipInteractionAutomations = mutableListOf<Automation>()
        val automations = content.rules.mapIndexed { index, rule ->
            val events = rule.facts.mapIndexedNotNull { factIndex, any ->
                if (!ShortXMappings.factEnabled(any)) return@mapIndexedNotNull null
                val feature = ShortXMappings.nativeFact(any, id)
                if (feature != null) {
                    trace += ImportTrace(
                        "rule[" + index + "].fact[" + factIndex + "]",
                        feature.typeId,
                        "MAPPED",
                        any.typeUrl,
                    )
                    feature
                } else {
                    preserve(
                        any,
                        CompatFeatureIds.SOURCE_EVENT,
                        "rule[" + index + "].fact[" + factIndex + "]",
                        ShortXMappings.suggestedEventFeature(any.typeUrl),
                    )
                }
            }

            val predicates = rule.conditions.mapIndexedNotNull { conditionIndex, any ->
                if (!ShortXMappings.conditionEnabled(any)) null
                else mapPredicate(any, "rule[" + index + "].condition[" + conditionIndex + "]")
            }

            val actions = rule.actions.mapIndexed { actionIndex, any ->
                val path = "rule[" + index + "].action[" + actionIndex + "]"
                // A ShortX chip can contain two independent action lists. Import them as
                // native, chip-filtered event rules rather than throwing away callbacks.
                // The global ShortX hide command hides whichever imported chip is active.
                val chipId = "sx_${index}_${actionIndex}"
                val chip = if (ShortXMappings.enabled(any))
                    ShortXMappings.nativeChipInteraction(any, id, chipId) else null
                if (chip == null) {
                    mapAction(any, path)
                } else {
                    fun handlerRule(gesture: String, children: List<AnyStub>) {
                        if (children.isEmpty()) return
                        val nodes = children.mapIndexed { n, child ->
                            mapAction(child, "$path.$gesture[$n]")
                        }
                        chipInteractionAutomations += Automation(
                            id = AutomationId("import-shortx-" + rule.id + "-chip-" + actionIndex + "-" + gesture),
                            name = rule.title + " / chip " + gesture,
                            enabled = rule.enabled,
                            activation = Activation(events = listOf(FeatureRef(
                                typeId = "android.event.status_chip_interaction",
                                config = mapOf(
                                    "chipId" to ConfigValue.StringValue(chipId),
                                    "gesture" to ConfigValue.StringValue(gesture),
                                ),
                            ))),
                            onEvent = nodes,
                            source = SourceMetadata(id, rule.id + ":chip:" + actionIndex + ":" + gesture, "ChipInteraction"),
                        )
                    }
                    handlerRule("click", chip.click)
                    handlerRule("long_click", chip.longClick)
                    trace += ImportTrace(path, chip.feature.typeId, "MAPPED", any.typeUrl)
                    ActionNode.Action(
                        NodeId(UUID.randomUUID().toString()),
                        chip.feature,
                        enabled = ShortXMappings.enabled(any),
                        comment = ShortXMappings.note(any),
                        failurePolicy = if (ShortXMappings.actionBreaksOnError(any))
                            ActionFailurePolicy.STOP else ActionFailurePolicy.CONTINUE,
                    )
                }
            }

            val automation = Automation(
                AutomationId("import-shortx-" + rule.id),
                rule.title.ifBlank { userText("import.shortx.rule_name", index + 1) },
                enabled = rule.enabled,
                activation = Activation(
                    events = events,
                    condition = if (predicates.isEmpty()) null else PredicateNode.All(predicates),
                ),
                onEvent = actions,
                description = rule.description,
                source = SourceMetadata(id, rule.id, "Rule"),
            )
            trace += ImportTrace(
                "rule[" + index + "]",
                automation.id.value,
                "IMPORTED",
                userText("import.trace.shortx_rule", events.size, predicates.size, actions.size),
            )
            automation
        }

        return ImportResult(
            importerId = id,
            success = true,
            bundle = ImportBundle(automations = automations + chipInteractionAutomations, flows = flows),
            issues = issues,
            trace = trace,
        )
    }

    private fun toFlowParameter(parameter: FuncParameterStub): FlowParameter =
        FlowParameter(
            name = parameter.name.ifBlank { "arg" },
            type = ValueType.ANY,
            required = parameter.required,
            defaultValue = if (parameter.defaultValue.isBlank()) ConfigValue.NullValue
            else ConfigValue.StringValue(parameter.defaultValue),
        )

    private fun failure(input: ImportInput, error: Throwable) =
        ImportResult(
            id,
            false,
            issues = listOf(
                CompatibilityIssue(
                    ImportSeverity.ERROR,
                    input.fileName ?: "input",
                    message = error.message ?: userText("import.shortx.failed"),
                )
            ),
        )

    private fun shortName(typeUrl: String): String =
        typeUrl.substringAfterLast('/').substringAfterLast('.').substringAfterLast('$')

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
}

internal data class AnyStub(
    val typeUrl: String,
    val value: ByteArray,
    val isJson: Boolean = false,
)

internal data class RuleStub(
    val id: String,
    val title: String,
    val description: String? = null,
    val enabled: Boolean = true,
    val facts: List<AnyStub> = emptyList(),
    val conditions: List<AnyStub> = emptyList(),
    val actions: List<AnyStub> = emptyList(),
)

internal data class FuncParameterStub(
    val name: String,
    val defaultValue: String = "",
    val required: Boolean = false,
    val comments: String = "",
)

internal data class FunctionStub(
    val id: String,
    val name: String,
    val returnType: String = "",
    val parameters: List<FuncParameterStub> = emptyList(),
    val actions: List<AnyStub> = emptyList(),
    val comments: String? = null,
)

internal data class DirectActionStub(
    val id: String,
    val title: String,
    val description: String? = null,
    val parameters: List<FuncParameterStub> = emptyList(),
    val actions: List<AnyStub> = emptyList(),
)

internal data class ShortXContent(
    val rules: List<RuleStub> = emptyList(),
    val functions: List<FunctionStub> = emptyList(),
    val directActions: List<DirectActionStub> = emptyList(),
)

internal fun shortXFlowId(kind: String, sourceId: String): FlowId =
    FlowId("import-shortx-" + kind + "-" + sourceId)

internal object ShortXProtoReader {
    fun read(bytes: ByteArray): ShortXContent {
        val rules = readRules(bytes)
        val functions = readFunctions(bytes)
        val directActions = readDirectActions(bytes)
        return ShortXContent(
            rules = rules.distinctBy { it.id },
            functions = functions.distinctBy { it.id },
            directActions = directActions.distinctBy { it.id },
        )
    }

    fun readRules(bytes: ByteArray): List<RuleStub> {
        val top = Wire(bytes).fields()
        val directList = decodeRuleList(bytes)
        if (directList.isNotEmpty()) return directList

        val fromRuleSets = top
            .filter { it.number == 1 && it.wire == 2 }
            .flatMap { setField ->
                val setFields = Wire(setField.bytes ?: byteArrayOf()).fields()
                setFields
                    .filter { it.number == 7 && it.wire == 2 }
                    .flatMap { decodeRuleList(it.bytes ?: byteArrayOf()) }
            }
        if (fromRuleSets.isNotEmpty()) return fromRuleSets

        return listOfNotNull(decodeRule(bytes))
    }

    fun readFunctions(bytes: ByteArray): List<FunctionStub> {
        val list = decodeFunctionList(bytes)
        if (list.isNotEmpty()) return list
        return listOfNotNull(decodeFunction(bytes))
    }

    fun readDirectActions(bytes: ByteArray): List<DirectActionStub> {
        val directList = decodeDirectActionList(bytes)
        if (directList.isNotEmpty()) return directList

        val top = Wire(bytes).fields()
        val fromSets = top
            .filter { it.number == 1 && it.wire == 2 }
            .flatMap { setField ->
                val setFields = Wire(setField.bytes ?: byteArrayOf()).fields()
                setFields
                    .filter { it.number == 7 && it.wire == 2 }
                    .flatMap { decodeDirectActionList(it.bytes ?: byteArrayOf()) }
            }
        if (fromSets.isNotEmpty()) return fromSets

        return listOfNotNull(decodeDirectAction(bytes))
    }

    private fun decodeRuleList(bytes: ByteArray): List<RuleStub> =
        Wire(bytes).fields()
            .filter { it.number == 1 && it.wire == 2 }
            .mapNotNull { decodeRule(it.bytes ?: byteArrayOf()) }
            .filter { it.id.isNotBlank() && it.title.isNotBlank() }

    private fun decodeRule(bytes: ByteArray): RuleStub? = runCatching {
        val fields = Wire(bytes).fields()
        fun text(number: Int) = textField(fields, number)
        fun bool(number: Int, default: Boolean) =
            fields.firstOrNull { it.number == number && it.wire == 0 }?.varint?.let { it != 0L } ?: default
        fun anys(number: Int) =
            fields.filter { it.number == number && it.wire == 2 }
                .mapNotNull { decodeAnyOrNull(it.bytes ?: byteArrayOf()) }

        val id = text(4)
        val title = text(9)
        if (!looksLikeId(id) || !looksLikeText(title)) return@runCatching null
        RuleStub(
            id = id,
            title = title,
            description = text(10).ifBlank { null },
            enabled = bool(11, true),
            facts = anys(1),
            conditions = anys(2),
            actions = anys(3),
        )
    }.getOrNull()

    private fun decodeFunctionList(bytes: ByteArray): List<FunctionStub> =
        Wire(bytes).fields()
            .filter { it.number == 1 && it.wire == 2 }
            .mapNotNull { decodeFunction(it.bytes ?: byteArrayOf()) }
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }

    private fun decodeFunction(bytes: ByteArray): FunctionStub? = runCatching {
        val fields = Wire(bytes).fields()
        val id = textField(fields, 1)
        val name = textField(fields, 2)
        if (!looksLikeId(id) || !looksLikeText(name)) return@runCatching null
        val parameters = fields
            .filter { it.number == 4 && it.wire == 2 }
            .mapNotNull { decodeParameter(it.bytes ?: byteArrayOf()) }
        val actions = fields
            .filter { it.number == 5 && it.wire == 2 }
            .mapNotNull { decodeAnyOrNull(it.bytes ?: byteArrayOf()) }
        FunctionStub(
            id = id,
            name = name,
            returnType = textField(fields, 3),
            parameters = parameters,
            actions = actions,
            comments = textField(fields, 8).ifBlank { null },
        )
    }.getOrNull()

    private fun decodeDirectActionList(bytes: ByteArray): List<DirectActionStub> =
        Wire(bytes).fields()
            .filter { it.number == 1 && it.wire == 2 }
            .mapNotNull { decodeDirectAction(it.bytes ?: byteArrayOf()) }
            .filter { it.id.isNotBlank() && it.title.isNotBlank() }

    private fun decodeDirectAction(bytes: ByteArray): DirectActionStub? = runCatching {
        val fields = Wire(bytes).fields()
        val id = textField(fields, 2)
        val title = textField(fields, 6)
        if (!looksLikeId(id) || !looksLikeText(title)) return@runCatching null
        DirectActionStub(
            id = id,
            title = title,
            description = textField(fields, 7).ifBlank { null },
            parameters = fields
                .filter { it.number == 12 && it.wire == 2 }
                .mapNotNull { decodeParameter(it.bytes ?: byteArrayOf()) },
            actions = fields
                .filter { it.number == 1 && it.wire == 2 }
                .mapNotNull { decodeAnyOrNull(it.bytes ?: byteArrayOf()) },
        )
    }.getOrNull()

    private fun decodeParameter(bytes: ByteArray): FuncParameterStub? = runCatching {
        val fields = Wire(bytes).fields()
        val name = textField(fields, 1)
        if (name.isBlank()) return@runCatching null
        FuncParameterStub(
            name = name,
            defaultValue = textField(fields, 2),
            required = fields.firstOrNull { it.number == 3 && it.wire == 0 }?.varint == 1L,
            comments = textField(fields, 4),
        )
    }.getOrNull()

    private fun decodeAnyOrNull(bytes: ByteArray): AnyStub? = runCatching {
        val fields = Wire(bytes).fields()
        val type = textField(fields, 1)
        val value = fields.firstOrNull { it.number == 2 && it.wire == 2 }?.bytes
            ?: return@runCatching null
        if (!looksLikeText(type)) return@runCatching null
        AnyStub(type, value)
    }.getOrNull()

    private fun textField(fields: List<Wire.Field>, number: Int): String =
        fields.firstOrNull { it.number == number && it.wire == 2 }
            ?.bytes
            ?.toString(Charsets.UTF_8)
            .orEmpty()

    private fun looksLikeId(value: String): Boolean =
        value.isNotBlank() && value.length <= 256 && value.all { it.code in 32..126 }

    private fun looksLikeText(value: String): Boolean =
        value.isNotBlank() && value.length <= 4096 && value.count { it.isISOControl() } <= 1
}

internal class Wire(private val data: ByteArray) {
    data class Field(
        val number: Int,
        val wire: Int,
        val varint: Long? = null,
        val bytes: ByteArray? = null,
    )

    private var p = 0

    fun fields(): List<Field> {
        val out = mutableListOf<Field>()
        while (p < data.size) {
            val tag = readVarint() ?: break
            val number = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            if (number <= 0) break
            when (wire) {
                0 -> out += Field(number, wire, varint = readVarint() ?: break)
                1 -> {
                    if (p + 8 > data.size) break
                    p += 8
                    out += Field(number, wire)
                }
                2 -> {
                    val length = (readVarint() ?: break).toInt()
                    if (length < 0 || p + length > data.size) break
                    out += Field(number, wire, bytes = data.copyOfRange(p, p + length))
                    p += length
                }
                5 -> {
                    if (p + 4 > data.size) break
                    p += 4
                    out += Field(number, wire)
                }
                else -> break
            }
        }
        return out
    }

    private fun readVarint(): Long? {
        var result = 0L
        var shift = 0
        while (p < data.size && shift < 64) {
            val value = data[p++].toInt() and 0xff
            result = result or ((value and 0x7f).toLong() shl shift)
            if (value and 0x80 == 0) return result
            shift += 7
        }
        return null
    }
}
