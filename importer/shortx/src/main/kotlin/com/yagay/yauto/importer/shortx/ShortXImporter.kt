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
        if ("shortx" in name || name.endsWith(".rule") || name.endsWith(".pb") || name.endsWith(".proto")) return 60
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
            is JsonObject -> listOfNotNull(root["rules"] as? JsonArray, root["ruleList"] as? JsonArray).firstOrNull()?.mapNotNull { it as? JsonObject } ?: listOf(root)
            else -> emptyList()
        }
        fromRules(ruleObjects.mapIndexed { i, obj -> RuleStub(
            id = obj.str("id") ?: "rule-$i",
            title = obj.str("title") ?: userText("import.shortx.rule_name", i + 1),
            description = obj.str("description"),
            enabled = obj.bool("isEnabled") ?: true,
            facts = (obj["facts"] as? JsonArray)?.mapIndexed { n, v -> jsonAny(v, "json.fact.$n") }.orEmpty(),
            conditions = (obj["conditions"] as? JsonArray)?.mapIndexed { n, v -> jsonAny(v, "json.condition.$n") }.orEmpty(),
            actions = (obj["actions"] as? JsonArray)?.mapIndexed { n, v -> jsonAny(v, "json.action.$n") }.orEmpty(),
        ) })
    }.getOrElse { failure(input, it) }

    private fun jsonAny(element: JsonElement, fallbackType: String): AnyStub {
        val obj = element as? JsonObject
        val type = obj?.let {
            sequenceOf("typeUrl", "type_url", "@type", "type", "className")
                .mapNotNull { key -> (it[key] as? JsonPrimitive)?.contentOrNull }
                .firstOrNull()
        } ?: fallbackType
        // JSON exports do not necessarily contain the binary protobuf payload, so keep the JSON
        // itself as raw bytes. Native protobuf mappings are only attempted for binary Any payloads.
        return AnyStub(type, element.toString().encodeToByteArray(), isJson = true)
    }

    private fun importProto(input: ImportInput): ImportResult = runCatching {
        val rules = ShortXProtoReader.readRules(input.bytes)
        if (rules.isEmpty()) error(userText("import.shortx.structure_unrecognized"))
        fromRules(rules)
    }.getOrElse { failure(input, it) }

    private fun fromRules(rules: List<RuleStub>): ImportResult {
        val issues = mutableListOf<CompatibilityIssue>()
        val trace = mutableListOf<ImportTrace>()
        val automations = rules.mapIndexed { index, rule ->
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
                    if (any.isJson) any.value.toString(Charsets.UTF_8) else Base64.getEncoder().encodeToString(any.value),
                )
            }

            val events = rule.facts.mapIndexedNotNull { i, v ->
                if (!ShortXMappings.factEnabled(v)) return@mapIndexedNotNull null
                val feature = ShortXMappings.nativeFact(v, id)
                if (feature != null) {
                    trace += ImportTrace("rule[$index].fact[$i]", feature.typeId, "MAPPED", v.typeUrl)
                    feature
                } else {
                    preserve(
                        v,
                        CompatFeatureIds.SOURCE_EVENT,
                        "rule[$index].fact[$i]",
                        ShortXMappings.suggestedEventFeature(v.typeUrl),
                    )
                }
            }
            val predicates = rule.conditions.mapIndexedNotNull { i, v ->
                if (!ShortXMappings.conditionEnabled(v)) return@mapIndexedNotNull null
                val feature = ShortXMappings.nativeCondition(v, id)
                if (feature != null) {
                    trace += ImportTrace("rule[$index].condition[$i]", feature.typeId, "MAPPED", v.typeUrl)
                    PredicateNode.Condition(feature)
                } else {
                    PredicateNode.Condition(
                        preserve(
                            v,
                            CompatFeatureIds.SOURCE_CONDITION,
                            "rule[$index].condition[$i]",
                            ShortXMappings.suggestedConditionFeature(v.typeUrl),
                        )
                    )
                }
            }
            val actions = rule.actions.mapIndexed { i, v ->
                val feature = ShortXMappings.nativeAction(v, id)
                if (feature != null) {
                    trace += ImportTrace("rule[$index].action[$i]", feature.typeId, "MAPPED", v.typeUrl)
                    ActionNode.Action(
                        NodeId(UUID.randomUUID().toString()),
                        feature,
                        enabled = ShortXMappings.enabled(v),
                        comment = ShortXMappings.note(v),
                        // Verified against ShortX's ActionOnError protobuf enum: Continue=0,
                        // Break=1. nativeAction() currently accepts only missing/default (Continue)
                        // metadata and deliberately preserves non-default policies as compatibility
                        // nodes, so every action reaching this branch must continue after failure.
                        failurePolicy = ActionFailurePolicy.CONTINUE,
                    )
                } else {
                    val suggested = ShortXMappings.suggestedActionFeature(v.typeUrl)
                    ActionNode.Action(
                        NodeId(UUID.randomUUID().toString()),
                        preserve(v, CompatFeatureIds.SOURCE_ACTION, "rule[$index].action[$i]", suggested),
                    )
                }
            }
            val automation = Automation(
                AutomationId("import-shortx-${rule.id}"),
                rule.title.ifBlank { userText("import.shortx.rule_name", index + 1) },
                enabled = rule.enabled,
                activation = Activation(events = events, condition = if (predicates.isEmpty()) null else PredicateNode.All(predicates)),
                onEvent = actions,
                description = rule.description,
                source = SourceMetadata(id, rule.id, "Rule"),
            )
            trace += ImportTrace(
                "rule[$index]",
                automation.id.value,
                "IMPORTED",
                userText("import.trace.shortx_rule", events.size, predicates.size, actions.size),
            )
            automation
        }
        return ImportResult(id, true, ImportBundle(automations = automations), issues, trace)
    }

    private fun failure(input: ImportInput, error: Throwable) = ImportResult(id, false, issues = listOf(CompatibilityIssue(ImportSeverity.ERROR, input.fileName ?: "input", message = error.message ?: userText("import.shortx.failed"))))
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
}

internal data class AnyStub(val typeUrl: String, val value: ByteArray, val isJson: Boolean = false)
internal data class RuleStub(
    val id: String,
    val title: String,
    val description: String? = null,
    val enabled: Boolean = true,
    val facts: List<AnyStub> = emptyList(),
    val conditions: List<AnyStub> = emptyList(),
    val actions: List<AnyStub> = emptyList(),
)

/** Minimal protobuf wire reader for the stable outer Rule fields only. Unknown fields are skipped. */
internal object ShortXProtoReader {
    fun readRules(bytes: ByteArray): List<RuleStub> {
        val top = Wire(bytes).fields()
        // RuleList: repeated Rule rules = 1
        val direct = top.filter { it.number == 1 && it.wire == 2 }.mapNotNull { decodeRule(it.bytes ?: byteArrayOf()) }.filter { it.id.isNotBlank() || it.title.isNotBlank() }
        if (direct.isNotEmpty()) return direct
        // Raw Rule
        decodeRule(bytes)?.let { if (it.id.isNotBlank() || it.title.isNotBlank()) return listOf(it) }
        // RuleSetList -> RuleSet(field 1) -> RuleList(field 7)
        val fromSets = mutableListOf<RuleStub>()
        top.filter { it.number == 1 && it.wire == 2 }.forEach { setField ->
            val setFields = Wire(setField.bytes ?: byteArrayOf()).fields()
            setFields.filter { it.number == 7 && it.wire == 2 }.forEach { listField ->
                fromSets += Wire(listField.bytes ?: byteArrayOf()).fields().filter { it.number == 1 && it.wire == 2 }.mapNotNull { decodeRule(it.bytes ?: byteArrayOf()) }
            }
        }
        return fromSets
    }

    private fun decodeRule(bytes: ByteArray): RuleStub? = runCatching {
        val fields = Wire(bytes).fields()
        fun text(number: Int) = fields.firstOrNull { it.number == number && it.wire == 2 }?.bytes?.toString(Charsets.UTF_8).orEmpty()
        fun bool(number: Int, default: Boolean) = fields.firstOrNull { it.number == number && it.wire == 0 }?.varint?.let { it != 0L } ?: default
        fun anys(number: Int) = fields.filter { it.number == number && it.wire == 2 }.map { decodeAny(it.bytes ?: byteArrayOf()) }
        RuleStub(text(4), text(9), text(10).ifBlank { null }, bool(11, true), anys(1), anys(2), anys(3))
    }.getOrNull()

    private fun decodeAny(bytes: ByteArray): AnyStub {
        val fields = Wire(bytes).fields()
        val type = fields.firstOrNull { it.number == 1 && it.wire == 2 }?.bytes?.toString(Charsets.UTF_8).orEmpty()
        val value = fields.firstOrNull { it.number == 2 && it.wire == 2 }?.bytes ?: bytes
        return AnyStub(type, value)
    }
}

internal class Wire(private val data: ByteArray) {
    data class Field(val number: Int, val wire: Int, val varint: Long? = null, val bytes: ByteArray? = null)
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
                1 -> { if (p + 8 > data.size) break; p += 8; out += Field(number, wire) }
                2 -> {
                    val length = (readVarint() ?: break).toInt()
                    if (length < 0 || p + length > data.size) break
                    out += Field(number, wire, bytes = data.copyOfRange(p, p + length)); p += length
                }
                5 -> { if (p + 4 > data.size) break; p += 4; out += Field(number, wire) }
                else -> break
            }
        }
        return out
    }
    private fun readVarint(): Long? {
        var result = 0L; var shift = 0
        while (p < data.size && shift < 64) {
            val b = data[p++].toInt() and 0xff
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        return null
    }
}
